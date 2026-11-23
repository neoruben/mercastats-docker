package io.github.neoruben.mercadonastats.parser

data class ParsedTicketItem(
    val quantity: Double,
    val unit: String?, // null para unidades, "kg" para peso
    val description: String,
    val unitPrice: Double?,
    val totalPrice: Double
)

data class ParsedVatBreakdown(
    val vatRatePercent: Int,
    val baseAmount: Double,
    val vatAmount: Double,
    val totalAmount: Double
)

data class ParsedTicket(
    val ticketNumber: String,
    val storeName: String,
    val storeAddress: String,
    val dateTimeIso: String, // YYYY-MM-DD HH:mm
    val dateDisplay: String, // DD/MM/YYYY HH:mm
    val totalAmount: Double,
    val totalVatAmount: Double,
    val items: List<ParsedTicketItem>,
    val vatBreakdown: List<ParsedVatBreakdown>
)
