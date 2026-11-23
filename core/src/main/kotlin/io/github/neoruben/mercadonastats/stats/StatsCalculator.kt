package io.github.neoruben.mercadonastats.stats

import io.github.neoruben.mercadonastats.data.StoredTicket
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

data class PeriodSpendStat(
    val periodKey: String,   // Ej: "2026-W47" o "2026-11"
    val periodLabel: String, // Ej: "Sem 47 (2026)" o "Nov 2026"
    val totalAmount: Double,
    val totalVatAmount: Double,
    val ticketCount: Int
)

data class ProductSpendStat(
    val productKey: String,
    val displayName: String,
    val unit: String?,
    val totalQuantity: Double,
    val purchaseCount: Int,
    val totalAmount: Double
)

/** Precio de un producto en una compra concreta (un punto de su evolución de precio). */
data class PricePoint(
    val ticketId: Long,
    val dateTimeIso: String,
    val unitPrice: Double, // €/unidad, o €/kg si se vende al peso
    val quantity: Double,
    val unit: String?
)

data class SpendSummary(
    val totalSpent: Double,
    val totalVat: Double,
    val ticketCount: Int,
    val averageTicket: Double,
    val weeklyStats: List<PeriodSpendStat>,
    val monthlyStats: List<PeriodSpendStat>,
    val productStats: List<ProductSpendStat> = emptyList()
)

object StatsCalculator {

    private val MONTH_NAMES_ES = mapOf(
        1 to "Ene", 2 to "Feb", 3 to "Mar", 4 to "Abr",
        5 to "May", 6 to "Jun", 7 to "Jul", 8 to "Ago",
        9 to "Sep", 10 to "Oct", 11 to "Nov", 12 to "Dic"
    )

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Clave con la que se agrupan los productos (misma que [ProductSpendStat.productKey]). */
    fun normalizeProductKey(description: String): String =
        description.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)

    /**
     * Evolución del precio de un producto: un punto por ticket en que aparece, ordenados por fecha.
     * Usa el precio unitario impreso en el ticket y, si no lo hay, importe / cantidad.
     */
    fun priceHistory(tickets: List<StoredTicket>, productKey: String): List<PricePoint> {
        val key = normalizeProductKey(productKey)
        return tickets.sortedBy { it.dateTimeIso }.mapNotNull { ticket ->
            val lines = ticket.items.filter { normalizeProductKey(it.description) == key }
            if (lines.isEmpty()) return@mapNotNull null
            val quantity = lines.sumOf { it.quantity }
            val unitPrice = lines.firstNotNullOfOrNull { it.unitPrice }
                ?: if (quantity > 0) lines.sumOf { it.totalPrice } / quantity else null
            if (unitPrice == null || unitPrice <= 0) return@mapNotNull null
            PricePoint(
                ticketId = ticket.id,
                dateTimeIso = ticket.dateTimeIso,
                unitPrice = unitPrice,
                quantity = quantity,
                unit = lines.firstNotNullOfOrNull { it.unit }
            )
        }
    }

    fun calculate(tickets: List<StoredTicket>): SpendSummary {
        if (tickets.isEmpty()) {
            return SpendSummary(
                totalSpent = 0.0,
                totalVat = 0.0,
                ticketCount = 0,
                averageTicket = 0.0,
                weeklyStats = emptyList(),
                monthlyStats = emptyList()
            )
        }

        val totalSpent = tickets.sumOf { it.totalAmount }
        val totalVat = tickets.sumOf { it.totalVatAmount }
        val avg = totalSpent / tickets.size

        val weekFields = WeekFields.ISO
        val weeklyMap = linkedMapOf<String, MutableList<StoredTicket>>()
        val monthlyMap = linkedMapOf<String, MutableList<StoredTicket>>()
        val productAggMap = linkedMapOf<String, ProductAccumulator>()

        val sortedAsc = tickets.sortedBy { it.dateTimeIso }
        for (ticket in sortedAsc) {
            val seenInThisTicket = hashSetOf<String>()
            for (item in ticket.items) {
                val normalized = item.description
                    .trim()
                    .replace(Regex("\\s+"), " ")
                    .uppercase(Locale.ROOT)
                if (normalized.isBlank()) continue

                val acc = productAggMap.getOrPut(normalized) {
                    ProductAccumulator(
                        displayName = item.description.trim().replace(Regex("\\s+"), " "),
                        unit = item.unit
                    )
                }
                acc.totalQuantity += item.quantity
                acc.totalAmount += item.totalPrice
                if (acc.unit == null && item.unit != null) {
                    acc.unit = item.unit
                }
                if (seenInThisTicket.add(normalized)) {
                    acc.purchaseCount += 1
                }
            }

            val datePart = ticket.dateTimeIso.substringBefore(" ")
            val localDate = runCatching {
                LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrNull() ?: continue

            val weekYear = localDate.get(weekFields.weekBasedYear())
            val weekNum = localDate.get(weekFields.weekOfWeekBasedYear())
            val weekKey = String.format(Locale.US, "%04d-W%02d", weekYear, weekNum)
            weeklyMap.getOrPut(weekKey) { mutableListOf() }.add(ticket)

            val monthKey = String.format(Locale.US, "%04d-%02d", localDate.year, localDate.monthValue)
            monthlyMap.getOrPut(monthKey) { mutableListOf() }.add(ticket)
        }

        val weeklyStats = weeklyMap.entries.map { (key, list) ->
            val year = key.substringBefore("-W")
            val week = key.substringAfter("-W").toInt()
            PeriodSpendStat(
                periodKey = key,
                periodLabel = "S$week '${year.takeLast(2)}",
                totalAmount = list.sumOf { it.totalAmount },
                totalVatAmount = list.sumOf { it.totalVatAmount },
                ticketCount = list.size
            )
        }

        val monthlyStats = monthlyMap.entries.map { (key, list) ->
            val year = key.substringBefore("-")
            val month = key.substringAfter("-").toInt()
            val monthName = MONTH_NAMES_ES[month] ?: key
            PeriodSpendStat(
                periodKey = key,
                periodLabel = "$monthName ${year.takeLast(2)}",
                totalAmount = list.sumOf { it.totalAmount },
                totalVatAmount = list.sumOf { it.totalVatAmount },
                ticketCount = list.size
            )
        }

        val productStats = productAggMap.entries.map { (key, acc) ->
            ProductSpendStat(
                productKey = key,
                displayName = acc.displayName,
                unit = acc.unit,
                totalQuantity = acc.totalQuantity,
                purchaseCount = acc.purchaseCount,
                totalAmount = acc.totalAmount
            )
        }.sortedByDescending { it.totalAmount }

        return SpendSummary(
            totalSpent = totalSpent,
            totalVat = totalVat,
            ticketCount = tickets.size,
            averageTicket = avg,
            weeklyStats = weeklyStats,
            monthlyStats = monthlyStats,
            productStats = productStats
        )
    }

    private class ProductAccumulator(
        val displayName: String,
        var unit: String?,
        var totalQuantity: Double = 0.0,
        var purchaseCount: Int = 0,
        var totalAmount: Double = 0.0
    )
}
