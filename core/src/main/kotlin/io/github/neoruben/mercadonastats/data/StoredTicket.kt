package io.github.neoruben.mercadonastats.data

import io.github.neoruben.mercadonastats.parser.ParsedTicketItem
import io.github.neoruben.mercadonastats.parser.ParsedVatBreakdown

data class StoredTicket(
    val id: Long,
    val ticketNumber: String,
    val pdfSha256: String,
    val gmailMessageId: String?,
    val storeName: String,
    val storeAddress: String,
    val dateTimeIso: String,
    val dateDisplay: String,
    val totalAmount: Double,
    val totalVatAmount: Double,
    val items: List<ParsedTicketItem>,
    val vatBreakdown: List<ParsedVatBreakdown>
)

enum class InsertTicketStatus {
    INSERTED,
    DUPLICATE_TICKET_NUMBER,
    DUPLICATE_PDF_HASH,
    DUPLICATE_GMAIL_MESSAGE
}
