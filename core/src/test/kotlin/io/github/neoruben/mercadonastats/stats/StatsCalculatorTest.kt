package io.github.neoruben.mercadonastats.stats

import io.github.neoruben.mercadonastats.data.StoredTicket
import io.github.neoruben.mercadonastats.parser.ParsedTicketItem
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsCalculatorTest {

    private fun fakeTicket(
        id: Long,
        dateIso: String,
        total: Double,
        vat: Double,
        items: List<ParsedTicketItem> = emptyList()
    ) = StoredTicket(
        id = id,
        ticketNumber = "T-$id",
        pdfSha256 = "hash-$id",
        gmailMessageId = "msg-$id",
        storeName = "MERCADONA",
        storeAddress = "CL PRUEBA",
        dateTimeIso = dateIso,
        dateDisplay = dateIso,
        totalAmount = total,
        totalVatAmount = vat,
        items = items,
        vatBreakdown = emptyList()
    )

    @Test
    fun aggregatesWeeklyAndMonthlyTotals() {
        val tickets = listOf(
            fakeTicket(1, "2026-11-02 10:00", 20.00, 2.00), // W45, Nov
            fakeTicket(2, "2026-11-04 18:30", 30.00, 3.00), // W45, Nov
            fakeTicket(3, "2026-11-12 10:00", 50.00, 5.00), // W46, Nov
            fakeTicket(4, "2026-10-20 12:00", 40.00, 4.00)  // W43, Oct
        )

        val summary = StatsCalculator.calculate(tickets)

        assertEquals(4, summary.ticketCount)
        assertEquals(140.00, summary.totalSpent, 0.001)
        assertEquals(14.00, summary.totalVat, 0.001)
        assertEquals(35.00, summary.averageTicket, 0.001)

        assertEquals(2, summary.monthlyStats.size)
        assertEquals("2026-10", summary.monthlyStats[0].periodKey)
        assertEquals(40.00, summary.monthlyStats[0].totalAmount, 0.001)
        assertEquals("2026-11", summary.monthlyStats[1].periodKey)
        assertEquals(100.00, summary.monthlyStats[1].totalAmount, 0.001)
        assertEquals(3, summary.monthlyStats[1].ticketCount)

        assertEquals(3, summary.weeklyStats.size)
        assertEquals("2026-W43", summary.weeklyStats[0].periodKey)
        assertEquals("2026-W45", summary.weeklyStats[1].periodKey)
        assertEquals(50.00, summary.weeklyStats[1].totalAmount, 0.001)
        assertEquals("2026-W46", summary.weeklyStats[2].periodKey)
        assertEquals(50.00, summary.weeklyStats[2].totalAmount, 0.001)
    }

    @Test
    fun aggregatesProductsNormalizedAcrossTickets() {
        val tickets = listOf(
            fakeTicket(
                1, "2026-11-02 10:00", 5.00, 0.20,
                listOf(
                    ParsedTicketItem(2.0, null, "  leche  entera ", 0.90, 1.80),
                    ParsedTicketItem(1.0, null, "PAN BARRA", 0.75, 0.75)
                )
            ),
            fakeTicket(
                2, "2026-11-05 11:00", 3.60, 0.14,
                listOf(
                    ParsedTicketItem(4.0, null, "LECHE ENTERA", 0.90, 3.60)
                )
            )
        )

        val summary = StatsCalculator.calculate(tickets)
        assertEquals(2, summary.productStats.size)
        val leche = summary.productStats.first()
        assertEquals("LECHE ENTERA", leche.productKey)
        assertEquals(6.0, leche.totalQuantity, 0.001)
        assertEquals(2, leche.purchaseCount)
        assertEquals(5.40, leche.totalAmount, 0.001)
    }

    @Test
    fun priceHistoryUsesUnitPriceOrDerivesItAndSortsByDate() {
        val tickets = listOf(
            fakeTicket(2, "2026-03-01 10:00", 3.0, 0.1, listOf(ParsedTicketItem(2.0, null, "Yogur natural", null, 2.50))),
            fakeTicket(1, "2026-01-01 10:00", 3.0, 0.1, listOf(
                ParsedTicketItem(1.0, null, "YOGUR NATURAL", 1.10, 1.10),
                ParsedTicketItem(1.0, null, "PAN", 0.75, 0.75)
            )),
            fakeTicket(3, "2026-02-01 10:00", 3.0, 0.1, listOf(ParsedTicketItem(1.0, null, "PAN", 0.80, 0.80)))
        )

        val history = StatsCalculator.priceHistory(tickets, "yogur natural")
        assertEquals(listOf(1L, 2L), history.map { it.ticketId })
        assertEquals(1.10, history[0].unitPrice, 0.001)
        assertEquals(1.25, history[1].unitPrice, 0.001)
        assertEquals(2.0, history[1].quantity, 0.001)
    }

    @Test
    fun weekLabelKeepsWeekNumberWhenItContains20() {
        val summary = StatsCalculator.calculate(listOf(fakeTicket(1, "2026-05-13 10:00", 10.0, 1.0)))
        assertEquals("S20 '26", summary.weeklyStats.single().periodLabel)
    }
}
