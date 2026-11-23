package io.github.neoruben.mercadonastats.parser

import java.util.Locale

/**
 * Parser de tickets digitales PDF de Mercadona (soporta texto en castellano y catalán).
 */
object MercadonaTicketParser {

    private val DATE_TIME_REGEX = Regex("""(\d{2})/(\d{2})/(\d{4})\s+(\d{2}:\d{2})""")
    private val TICKET_NUMBER_REGEX = Regex("""FACTURA\s+SIMPLIFICADA:\s*([0-9-]+)""", RegexOption.IGNORE_CASE)
    private val TOTAL_REGEX = Regex("""TOTAL\s*\(€\)\s+(\d+[.,]\d{2})""", RegexOption.IGNORE_CASE)

    // Ejemplos:
    // "2 AIGUA MINERAL 8L 0,95 1,90"
    // "1 LLEIXIU 0,95"
    private val ITEM_LINE_WITH_UNIT_PRICE = Regex("""^(\d+)\s+(.+?)\s+(\d+[.,]\d{2})\s+(\d+[.,]\d{2})$""")
    private val ITEM_LINE_SINGLE = Regex("""^(\d+)\s+(.+?)\s+(\d+[.,]\d{2})$""")

    // Ejemplo de segunda línea de producto al peso:
    // "0,248 kg 8,90 €/kg 2,21"
    private val WEIGHT_LINE_REGEX = Regex("""^(\d+[.,]\d+)\s*kg\s+(\d+[.,]\d{2})\s*€/kg\s+(\d+[.,]\d{2})$""", RegexOption.IGNORE_CASE)

    // Ejemplo de línea de IVA:
    // "4% 6,79 0,27 7,06"
    private val VAT_LINE_REGEX = Regex("""^(\d{1,2})%\s+(\d+[.,]\d{2})\s+(\d+[.,]\d{2})\s+(\d+[.,]\d{2})$""")
    private val VAT_TOTAL_LINE_REGEX = Regex("""^TOTAL\s+(\d+[.,]\d{2})\s+(\d+[.,]\d{2})\s+(\d+[.,]\d{2})$""", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): ParsedTicket {
        val lines = rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotEmpty() }

        val fullText = lines.joinToString("\n")

        val dateMatch = DATE_TIME_REGEX.find(fullText)
            ?: throw IllegalArgumentException("No se encontró la fecha y hora en el ticket.")
        val day = dateMatch.groupValues[1]
        val month = dateMatch.groupValues[2]
        val year = dateMatch.groupValues[3]
        val time = dateMatch.groupValues[4]
        val dateDisplay = "$day/$month/$year $time"
        val dateTimeIso = "$year-$month-$day $time"

        val ticketNumber = TICKET_NUMBER_REGEX.find(fullText)?.groupValues?.get(1)?.trim()
            ?: throw IllegalArgumentException("No se encontró el número de FACTURA SIMPLIFICADA.")

        val totalAmount = TOTAL_REGEX.find(fullText)?.groupValues?.get(1)?.toEuroDouble()
            ?: throw IllegalArgumentException("No se encontró el TOTAL (€) del ticket.")

        val (storeName, storeAddress) = extractStoreInfo(lines)
        val items = extractItems(lines)
        val (vatBreakdown, vatTotalFromTable) = extractVatBreakdown(lines)

        val totalVat = vatTotalFromTable ?: vatBreakdown.sumOf { it.vatAmount }.round2()

        return ParsedTicket(
            ticketNumber = ticketNumber,
            storeName = storeName,
            storeAddress = storeAddress,
            dateTimeIso = dateTimeIso,
            dateDisplay = dateDisplay,
            totalAmount = totalAmount,
            totalVatAmount = totalVat,
            items = items,
            vatBreakdown = vatBreakdown
        )
    }

    private fun extractStoreInfo(lines: List<String>): Pair<String, String> {
        val mercadonaIndex = lines.indexOfFirst { it.contains("MERCADONA", ignoreCase = true) }
        if (mercadonaIndex == -1) {
            return "MERCADONA" to "Dirección desconocida"
        }

        val storeName = lines[mercadonaIndex]
        val addressLines = mutableListOf<String>()
        for (i in (mercadonaIndex + 1) until lines.size) {
            val line = lines[i]
            if (line.startsWith("TEL", ignoreCase = true) ||
                DATE_TIME_REGEX.containsMatchIn(line) ||
                line.contains("FACTURA", ignoreCase = true)
            ) {
                break
            }
            addressLines.add(line)
        }
        return storeName to addressLines.joinToString(", ").ifBlank { "Dirección desconocida" }
    }

    private fun extractItems(lines: List<String>): List<ParsedTicketItem> {
        var headerIdx = lines.indexOfFirst { line ->
            (line.contains("Descrip", ignoreCase = true) &&
                (line.contains("Import", ignoreCase = true) || line.contains("Unit", ignoreCase = true) ||
                    line.contains("Cnt", ignoreCase = true) || line.contains("Cant", ignoreCase = true)))
        }
        if (headerIdx == -1) {
            // Respaldo: si la cabecera se partió o tiene otro formato, empezar tras FACTURA SIMPLIFICADA
            headerIdx = lines.indexOfFirst { it.contains("FACTURA SIMPLIFICADA", ignoreCase = true) }
        }
        val endIdx = lines.indexOfFirst { line ->
            TOTAL_REGEX.containsMatchIn(line)
        }

        if (headerIdx == -1 || endIdx == -1 || endIdx <= headerIdx) {
            return emptyList()
        }

        val items = mutableListOf<ParsedTicketItem>()
        var pendingWeightProductName: String? = null

        for (i in (headerIdx + 1) until endIdx) {
            val line = lines[i]

            // Ignorar posibles fragmentos sueltos de cabecera de tabla
            if (line.equals("Cnt.", ignoreCase = true) ||
                line.equals("Cant.", ignoreCase = true) ||
                line.contains("Descrip", ignoreCase = true) && line.contains("Import", ignoreCase = true)
            ) {
                continue
            }

            val weightMatch = WEIGHT_LINE_REGEX.matchEntire(line)
            if (weightMatch != null) {
                val weightKg = weightMatch.groupValues[1].toEuroDouble()
                val pricePerKg = weightMatch.groupValues[2].toEuroDouble()
                val totalPrice = weightMatch.groupValues[3].toEuroDouble()
                val desc = pendingWeightProductName ?: "Producto al peso"
                items.add(
                    ParsedTicketItem(
                        quantity = weightKg,
                        unit = "kg",
                        description = desc,
                        unitPrice = pricePerKg,
                        totalPrice = totalPrice
                    )
                )
                pendingWeightProductName = null
                continue
            }

            val withUnitMatch = ITEM_LINE_WITH_UNIT_PRICE.matchEntire(line)
            if (withUnitMatch != null) {
                pendingWeightProductName = null
                val qty = withUnitMatch.groupValues[1].toDouble()
                val desc = withUnitMatch.groupValues[2].trim()
                val unitPrice = withUnitMatch.groupValues[3].toEuroDouble()
                val totalPrice = withUnitMatch.groupValues[4].toEuroDouble()
                items.add(
                    ParsedTicketItem(
                        quantity = qty,
                        unit = null,
                        description = desc,
                        unitPrice = unitPrice,
                        totalPrice = totalPrice
                    )
                )
                continue
            }

            val singleMatch = ITEM_LINE_SINGLE.matchEntire(line)
            if (singleMatch != null) {
                pendingWeightProductName = null
                val qty = singleMatch.groupValues[1].toDouble()
                val desc = singleMatch.groupValues[2].trim()
                val totalPrice = singleMatch.groupValues[3].toEuroDouble()
                items.add(
                    ParsedTicketItem(
                        quantity = qty,
                        unit = null,
                        description = desc,
                        unitPrice = if (qty == 1.0) totalPrice else (totalPrice / qty).round2(),
                        totalPrice = totalPrice
                    )
                )
                continue
            }

            // Si no coincide con ninguna línea con importe, es el nombre de un producto al peso
            // cuya línea de peso/importe viene inmediatamente después (ej. "TOMÀQUET").
            pendingWeightProductName = if (pendingWeightProductName == null) {
                line
            } else {
                "$pendingWeightProductName $line"
            }
        }

        return items
    }

    private fun extractVatBreakdown(lines: List<String>): Pair<List<ParsedVatBreakdown>, Double?> {
        val vatHeaderIdx = lines.indexOfFirst { line ->
            line.startsWith("IVA", ignoreCase = true) && line.contains("BASE IMP", ignoreCase = true)
        }
        if (vatHeaderIdx == -1) return emptyList<ParsedVatBreakdown>() to null

        val breakdown = mutableListOf<ParsedVatBreakdown>()
        var totalVatFromSummary: Double? = null

        for (i in (vatHeaderIdx + 1) until lines.size) {
            val line = lines[i]
            val vatMatch = VAT_LINE_REGEX.matchEntire(line)
            if (vatMatch != null) {
                breakdown.add(
                    ParsedVatBreakdown(
                        vatRatePercent = vatMatch.groupValues[1].toInt(),
                        baseAmount = vatMatch.groupValues[2].toEuroDouble(),
                        vatAmount = vatMatch.groupValues[3].toEuroDouble(),
                        totalAmount = vatMatch.groupValues[4].toEuroDouble()
                    )
                )
                continue
            }

            val totalMatch = VAT_TOTAL_LINE_REGEX.matchEntire(line)
            if (totalMatch != null) {
                totalVatFromSummary = totalMatch.groupValues[2].toEuroDouble()
                break
            }
        }

        return breakdown to totalVatFromSummary
    }

    private fun String.toEuroDouble(): Double {
        return replace(",", ".").toDouble()
    }

    private fun Double.round2(): Double {
        return String.format(Locale.US, "%.2f", this).toDouble()
    }
}
