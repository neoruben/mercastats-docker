package io.github.neoruben.mercadonastats.server.service

import io.github.neoruben.mercadonastats.data.InsertTicketStatus
import io.github.neoruben.mercadonastats.parser.MercadonaTicketParser
import io.github.neoruben.mercadonastats.server.db.Database
import io.github.neoruben.mercadonastats.server.pdf.JvmPdfTextExtractor
import io.github.neoruben.mercadonastats.stats.StatsCalculator
import org.slf4j.LoggerFactory

data class ImportItemResult(
    val filename: String,
    val status: String, // "inserted", "duplicate_ticket_number", "duplicate_pdf_hash", "duplicate_message_id", "error"
    val ticketNumber: String? = null,
    val totalAmount: Double? = null,
    val error: String? = null
)

data class BatchImportSummary(
    val total: Int,
    val inserted: Int,
    val duplicates: Int,
    val failed: Int,
    val results: List<ImportItemResult>
)

class TicketImportService(
    private val db: Database
) {
    private val logger = LoggerFactory.getLogger(TicketImportService::class.java)

    companion object {
        const val MAX_PDF_SIZE_BYTES = 15 * 1024 * 1024 // 15 MB por PDF
    }

    fun importPdfBytes(
        userId: Long,
        pdfBytes: ByteArray,
        filename: String,
        messageId: String? = null
    ): ImportItemResult {
        if (pdfBytes.isEmpty()) {
            return ImportItemResult(filename = filename, status = "error", error = "El archivo está vacío.")
        }
        if (pdfBytes.size > MAX_PDF_SIZE_BYTES) {
            return ImportItemResult(
                filename = filename,
                status = "error",
                error = "El archivo supera el tamaño máximo permitido (15 MB)."
            )
        }

        return try {
            val sha256 = StatsCalculator.sha256Hex(pdfBytes)
            val rawText = JvmPdfTextExtractor.extractText(pdfBytes)
            val parsed = MercadonaTicketParser.parse(rawText)
            val insertStatus = db.insertTicketIfNew(
                userId = userId,
                ticket = parsed,
                pdfSha256 = sha256,
                messageId = messageId
            )
            when (insertStatus) {
                InsertTicketStatus.INSERTED -> ImportItemResult(
                    filename = filename,
                    status = "inserted",
                    ticketNumber = parsed.ticketNumber,
                    totalAmount = parsed.totalAmount
                )

                InsertTicketStatus.DUPLICATE_TICKET_NUMBER -> ImportItemResult(
                    filename = filename,
                    status = "duplicate_ticket_number",
                    ticketNumber = parsed.ticketNumber,
                    totalAmount = parsed.totalAmount
                )

                InsertTicketStatus.DUPLICATE_PDF_HASH -> ImportItemResult(
                    filename = filename,
                    status = "duplicate_pdf_hash",
                    ticketNumber = parsed.ticketNumber,
                    totalAmount = parsed.totalAmount
                )

                InsertTicketStatus.DUPLICATE_GMAIL_MESSAGE -> ImportItemResult(
                    filename = filename,
                    status = "duplicate_message_id",
                    ticketNumber = parsed.ticketNumber,
                    totalAmount = parsed.totalAmount
                )
            }
        } catch (e: Exception) {
            logger.warn("Error importando '{}' para userId={}: {}", filename, userId, e.message)
            ImportItemResult(
                filename = filename,
                status = "error",
                error = e.message ?: "Error al procesar el PDF."
            )
        }
    }

    fun summarize(results: List<ImportItemResult>): BatchImportSummary {
        val inserted = results.count { it.status == "inserted" }
        val failed = results.count { it.status == "error" }
        val duplicates = results.size - inserted - failed
        return BatchImportSummary(
            total = results.size,
            inserted = inserted,
            duplicates = duplicates,
            failed = failed,
            results = results
        )
    }
}
