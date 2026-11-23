package io.github.neoruben.mercadonastats.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class MercadonaTicketParserTest {

    @Test
    fun parseCatalanFormatWithWeightedItem() {
        val sampleText = """
            MERCADONA, S.A. A-46103834
            C/ EXEMPLE, 10
            08001 BARCELONA
            TELÈFON: 930000000
            03/02/2025 10:12 OP: 1234567
            FACTURA SIMPLIFICADA: 0000-001-000123
            Cnt. Descripció P. Unit Import
            2 AIGUA MINERAL 8L 0,95 1,90
            1 LLEIXIU 0,95
            1 REFRESC COLA 2L 2,19
            1 CEBES MALLA 1,50
            1 PA DE MOTLLO 1,60
            1 RAÏM BLANC 3,96
            2 SARDINES EN OLI 2,95 5,90
            1 TOVALLONS 1,10
            1 PAPER DE CUINA 1,65
            1 INFUSIÓ 1,15
            TOMÀQUET
            0,248 kg 8,90 €/kg 2,21
            TOTAL (€) 24,11
            TARGETA BANCÀRIA 24,11
            IVA BASE IMP. (€) QUOTA (€) TOTAL (€)
            4% 6,79 0,27 7,06
            10% 10,15 1,01 11,16
            21% 4,87 1,02 5,89
            TOTAL 21,81 2,30 24,11
        """.trimIndent()

        val parsed = MercadonaTicketParser.parse(sampleText)

        assertEquals("0000-001-000123", parsed.ticketNumber)
        assertEquals("03/02/2025 10:12", parsed.dateDisplay)
        assertEquals("2025-02-03 10:12", parsed.dateTimeIso)
        assertEquals("C/ EXEMPLE, 10, 08001 BARCELONA", parsed.storeAddress)
        assertEquals(24.11, parsed.totalAmount, 0.001)
        assertEquals(2.30, parsed.totalVatAmount, 0.001)
        assertEquals(11, parsed.items.size)

        val first = parsed.items.first()
        assertEquals(2.0, first.quantity, 0.001)
        assertEquals("AIGUA MINERAL 8L", first.description)
        assertEquals(0.95, first.unitPrice ?: 0.0, 0.001)
        assertEquals(1.90, first.totalPrice, 0.001)

        val weighted = parsed.items.last()
        assertEquals(0.248, weighted.quantity, 0.0001)
        assertEquals("kg", weighted.unit)
        assertEquals("TOMÀQUET", weighted.description)
        assertEquals(8.90, weighted.unitPrice ?: 0.0, 0.001)
        assertEquals(2.21, weighted.totalPrice, 0.001)

        assertEquals(24.11, parsed.items.sumOf { it.totalPrice }, 0.01)
        assertEquals(3, parsed.vatBreakdown.size)
    }

    @Test
    fun parseSpanishFormatTicket() {
        val sampleText = """
            MERCADONA, S.A. A-46103834
            AVDA. DE LA PRUEBA, 10
            28001 MADRID
            TELÉFONO: 910000000
            10/10/2026 11:05 OP: 123456
            FACTURA SIMPLIFICADA: 1234-001-999888
            Cant. Descripción P. Unit Importe
            2 LECHE ENTERA 0,90 1,80
            1 PAN BARRA 0,75
            TOTAL (€) 2,55
            TARJETA BANCARIA 2,55
            IVA BASE IMP. (€) CUOTA (€) TOTAL (€)
            4% 2,45 0,10 2,55
            TOTAL 2,45 0,10 2,55
        """.trimIndent()

        val parsed = MercadonaTicketParser.parse(sampleText)
        assertEquals("1234-001-999888", parsed.ticketNumber)
        assertEquals("2026-10-10 11:05", parsed.dateTimeIso)
        assertEquals(2.55, parsed.totalAmount, 0.001)
        assertEquals(0.10, parsed.totalVatAmount, 0.001)
        assertEquals(2, parsed.items.size)
        assertEquals(1, parsed.vatBreakdown.size)
    }

    @Test
    fun parseTicketWithSplitHeaderLines() {
        val sampleText = """
            MERCADONA, S.A. A-46103834
            CL PRUEBA, 1
            08001 BARCELONA
            05/03/2025 09:30 OP: 111222
            FACTURA SIMPLIFICADA: 0000-002-111222
            Cnt.
            Descripció P. Unit Import
            1 LLET SENCERA P6 9,36
            TOTAL (€) 9,36
            IVA BASE IMP. (€) QUOTA (€) TOTAL (€)
            4% 9,00 0,36 9,36
            TOTAL 9,00 0,36 9,36
        """.trimIndent()

        val parsed = MercadonaTicketParser.parse(sampleText)
        assertEquals(1, parsed.items.size)
        assertEquals("LLET SENCERA P6", parsed.items[0].description)
        assertEquals(9.36, parsed.items[0].totalPrice, 0.001)
    }
}
