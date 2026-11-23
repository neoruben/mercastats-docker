package io.github.neoruben.mercadonastats.server.db

import io.github.neoruben.mercadonastats.data.InsertTicketStatus
import io.github.neoruben.mercadonastats.parser.MercadonaTicketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DatabaseAndImportTest {

    private val sampleTicketText = """
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

    @Test
    fun enforcesDeduplicationAndMultiUserIsolation() {
        val dbFile = File.createTempFile("mercastats-db-test-", ".db")
        try {
            val db = Database(dbFile.absolutePath)
            val user1 = db.createUser("alice", "Alice", "alice@example.com", "hash1", isAdmin = true, authSource = "local")
            val user2 = db.createUser("bob", "Bob", "bob@example.com", "hash2", isAdmin = false, authSource = "local")

            val parsed = MercadonaTicketParser.parse(sampleTicketText)

            assertEquals(
                InsertTicketStatus.INSERTED,
                db.insertTicketIfNew(user1.id, parsed, pdfSha256 = "sha-1", messageId = "<msg-1@example.com>")
            )
            // Duplicado por message-id
            assertEquals(
                InsertTicketStatus.DUPLICATE_GMAIL_MESSAGE,
                db.insertTicketIfNew(user1.id, parsed.copy(ticketNumber = "OTHER"), pdfSha256 = "sha-2", messageId = "<msg-1@example.com>")
            )
            // Duplicado por ticket_number
            assertEquals(
                InsertTicketStatus.DUPLICATE_TICKET_NUMBER,
                db.insertTicketIfNew(user1.id, parsed, pdfSha256 = "sha-3", messageId = null)
            )
            // Duplicado por pdf_sha256
            assertEquals(
                InsertTicketStatus.DUPLICATE_PDF_HASH,
                db.insertTicketIfNew(user1.id, parsed.copy(ticketNumber = "OTHER-2"), pdfSha256 = "sha-1", messageId = null)
            )

            // Otro usuario puede importar el mismo ticket sin colisión
            assertEquals(
                InsertTicketStatus.INSERTED,
                db.insertTicketIfNew(user2.id, parsed, pdfSha256 = "sha-1", messageId = "<msg-1@example.com>")
            )

            val aliceTickets = db.getAllTickets(user1.id)
            val bobTickets = db.getAllTickets(user2.id)
            assertEquals(1, aliceTickets.size)
            assertEquals(1, bobTickets.size)
            assertEquals(2, aliceTickets[0].items.size)

            // Bob no puede leer el ticket de Alice
            assertNotNull(db.getTicketById(user1.id, aliceTickets[0].id))
            assertNull(db.getTicketById(user2.id, aliceTickets[0].id))
        } finally {
            dbFile.delete()
        }
    }
}
