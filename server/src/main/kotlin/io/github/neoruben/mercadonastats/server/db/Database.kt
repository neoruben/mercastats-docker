package io.github.neoruben.mercadonastats.server.db

import io.github.neoruben.mercadonastats.data.InsertTicketStatus
import io.github.neoruben.mercadonastats.data.StoredTicket
import io.github.neoruben.mercadonastats.parser.ParsedTicket
import io.github.neoruben.mercadonastats.parser.ParsedTicketItem
import io.github.neoruben.mercadonastats.parser.ParsedVatBreakdown
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class UserRecord(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String?,
    val passwordHash: String?,
    val isAdmin: Boolean,
    val authSource: String,
    val createdAtMs: Long
)

data class GmailTokenRecord(
    val userId: Long,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val updatedAtMs: Long
)

data class SyncStateRecord(
    val userId: Long,
    val source: String,
    val lastRunMs: Long,
    val status: String,
    val message: String
)

/**
 * Repositorio SQLite (via sqlite-jdbc) compatible con el esquema de Android
 * (`tickets`, `ticket_items`, `ticket_vat`) añadiendo soporte multiusuario (`user_id`),
 * sesiones locales, tokens opcionales de Gmail OAuth y registro de sincronizaciones.
 */
class Database(private val dbPath: String) {

    private val writeLock = ReentrantLock()
    private val jdbcUrl = "jdbc:sqlite:$dbPath"

    init {
        if (dbPath != ":memory:") {
            val file = File(dbPath).absoluteFile
            file.parentFile?.mkdirs()
        }
        initSchema()
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys = ON")
                stmt.execute("PRAGMA busy_timeout = 5000")
            }
            return block(conn)
        }
    }

    private fun <T> withWriteTransaction(block: (Connection) -> T): T = writeLock.withLock {
        withConnection { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    private fun initSchema() = writeLock.withLock {
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = WAL")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE,
                        display_name TEXT NOT NULL,
                        email TEXT,
                        password_hash TEXT,
                        is_admin INTEGER NOT NULL DEFAULT 0,
                        auth_source TEXT NOT NULL DEFAULT 'local',
                        created_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                        token_hash TEXT PRIMARY KEY,
                        user_id INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tickets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        ticket_number TEXT NOT NULL,
                        pdf_sha256 TEXT NOT NULL,
                        gmail_message_id TEXT,
                        store_name TEXT NOT NULL,
                        store_address TEXT NOT NULL,
                        date_time_iso TEXT NOT NULL,
                        date_display TEXT NOT NULL,
                        total_amount REAL NOT NULL,
                        total_vat_amount REAL NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
                        UNIQUE(user_id, ticket_number),
                        UNIQUE(user_id, pdf_sha256),
                        UNIQUE(user_id, gmail_message_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ticket_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ticket_id INTEGER NOT NULL,
                        quantity REAL NOT NULL,
                        unit TEXT,
                        description TEXT NOT NULL,
                        unit_price REAL,
                        total_price REAL NOT NULL,
                        FOREIGN KEY(ticket_id) REFERENCES tickets(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ticket_vat (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ticket_id INTEGER NOT NULL,
                        vat_rate_percent INTEGER NOT NULL,
                        base_amount REAL NOT NULL,
                        vat_amount REAL NOT NULL,
                        total_amount REAL NOT NULL,
                        FOREIGN KEY(ticket_id) REFERENCES tickets(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS gmail_oauth_tokens (
                        user_id INTEGER PRIMARY KEY,
                        access_token TEXT NOT NULL,
                        refresh_token TEXT,
                        expires_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sync_state (
                        user_id INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        last_run_ms INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        message TEXT NOT NULL,
                        PRIMARY KEY(user_id, source),
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tickets_user_date ON tickets(user_id, date_time_iso DESC)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_items_ticket ON ticket_items(ticket_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_vat_ticket ON ticket_vat(ticket_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at_ms)")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------------

    fun countUsers(): Int = withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM users").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    fun findUserByUsername(username: String): UserRecord? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT id, username, display_name, email, password_hash, is_admin, auth_source, created_at_ms FROM users WHERE LOWER(username) = LOWER(?)"
        ).use { ps ->
            ps.setString(1, username.trim())
            ps.executeQuery().use { rs ->
                if (rs.next()) mapUser(rs) else null
            }
        }
    }

    fun findUserById(userId: Long): UserRecord? = withConnection { conn ->
        conn.prepareStatement(
            "SELECT id, username, display_name, email, password_hash, is_admin, auth_source, created_at_ms FROM users WHERE id = ?"
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                if (rs.next()) mapUser(rs) else null
            }
        }
    }

    fun listUsers(): List<UserRecord> = withConnection { conn ->
        val list = mutableListOf<UserRecord>()
        conn.prepareStatement(
            "SELECT id, username, display_name, email, password_hash, is_admin, auth_source, created_at_ms FROM users ORDER BY id ASC"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(mapUser(rs))
            }
        }
        list
    }

    fun createUser(
        username: String,
        displayName: String,
        email: String?,
        passwordHash: String?,
        isAdmin: Boolean,
        authSource: String
    ): UserRecord = withWriteTransaction { conn ->
        val now = System.currentTimeMillis()
        val cleanUsername = username.trim()
        val cleanDisplay = displayName.trim().ifBlank { cleanUsername }
        conn.prepareStatement(
            """
            INSERT INTO users (username, display_name, email, password_hash, is_admin, auth_source, created_at_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setString(1, cleanUsername)
            ps.setString(2, cleanDisplay)
            if (email.isNullOrBlank()) ps.setNull(3, Types.VARCHAR) else ps.setString(3, email.trim())
            if (passwordHash.isNullOrBlank()) ps.setNull(4, Types.VARCHAR) else ps.setString(4, passwordHash)
            ps.setInt(5, if (isAdmin) 1 else 0)
            ps.setString(6, authSource)
            ps.setLong(7, now)
            ps.executeUpdate()
            ps.generatedKeys.use { keys ->
                val id = if (keys.next()) keys.getLong(1) else error("No se pudo obtener el ID del usuario creado.")
                UserRecord(id, cleanUsername, cleanDisplay, email?.trim(), passwordHash, isAdmin, authSource, now)
            }
        }
    }

    fun updateUserPassword(userId: Long, passwordHash: String) = withWriteTransaction { conn ->
        conn.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?").use { ps ->
            ps.setString(1, passwordHash)
            ps.setLong(2, userId)
            ps.executeUpdate()
        }
    }

    fun updateUserProfile(userId: Long, displayName: String, email: String?) = withWriteTransaction { conn ->
        conn.prepareStatement("UPDATE users SET display_name = ?, email = ? WHERE id = ?").use { ps ->
            ps.setString(1, displayName.trim())
            if (email.isNullOrBlank()) ps.setNull(2, Types.VARCHAR) else ps.setString(2, email.trim())
            ps.setLong(3, userId)
            ps.executeUpdate()
        }
    }

    fun deleteUser(userId: Long): Boolean = withWriteTransaction { conn ->
        conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
            ps.setLong(1, userId)
            ps.executeUpdate() > 0
        }
    }

    // -------------------------------------------------------------------------
    // Sessions
    // -------------------------------------------------------------------------

    fun createSession(tokenHash: String, userId: Long, expiresAtMs: Long) = withWriteTransaction { conn ->
        conn.prepareStatement(
            "INSERT INTO sessions (token_hash, user_id, expires_at_ms, created_at_ms) VALUES (?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, tokenHash)
            ps.setLong(2, userId)
            ps.setLong(3, expiresAtMs)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    fun findUserBySessionTokenHash(tokenHash: String, nowMs: Long = System.currentTimeMillis()): UserRecord? =
        withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT u.id, u.username, u.display_name, u.email, u.password_hash, u.is_admin, u.auth_source, u.created_at_ms
                FROM sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at_ms > ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, tokenHash)
                ps.setLong(2, nowMs)
                ps.executeQuery().use { rs ->
                    if (rs.next()) mapUser(rs) else null
                }
            }
        }

    fun deleteSession(tokenHash: String) = withWriteTransaction { conn ->
        conn.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { ps ->
            ps.setString(1, tokenHash)
            ps.executeUpdate()
        }
    }

    fun deleteExpiredSessions(nowMs: Long = System.currentTimeMillis()) = withWriteTransaction { conn ->
        conn.prepareStatement("DELETE FROM sessions WHERE expires_at_ms <= ?").use { ps ->
            ps.setLong(1, nowMs)
            ps.executeUpdate()
        }
    }

    // -------------------------------------------------------------------------
    // Tickets (esquema compatible con TicketDatabaseHelper + user_id)
    // -------------------------------------------------------------------------

    fun getKnownMessageIds(userId: Long): Set<String> = withConnection { conn ->
        val ids = hashSetOf<String>()
        conn.prepareStatement(
            "SELECT gmail_message_id FROM tickets WHERE user_id = ? AND gmail_message_id IS NOT NULL"
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val value = rs.getString(1)
                    if (!value.isNullOrBlank()) ids.add(value)
                }
            }
        }
        ids
    }

    fun deleteTicketsWithoutItems(userId: Long): Int = withWriteTransaction { conn ->
        conn.prepareStatement(
            """
            DELETE FROM tickets
            WHERE user_id = ?
              AND NOT EXISTS (SELECT 1 FROM ticket_items WHERE ticket_items.ticket_id = tickets.id)
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeUpdate()
        }
    }

    fun insertTicketIfNew(
        userId: Long,
        ticket: ParsedTicket,
        pdfSha256: String,
        messageId: String?
    ): InsertTicketStatus = withWriteTransaction { conn ->
        val cleanMsgId = messageId?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanMsgId != null && ticketExists(conn, userId, "gmail_message_id = ?", cleanMsgId)) {
            return@withWriteTransaction InsertTicketStatus.DUPLICATE_GMAIL_MESSAGE
        }
        if (ticketExists(conn, userId, "ticket_number = ?", ticket.ticketNumber)) {
            return@withWriteTransaction InsertTicketStatus.DUPLICATE_TICKET_NUMBER
        }
        if (ticketExists(conn, userId, "pdf_sha256 = ?", pdfSha256)) {
            return@withWriteTransaction InsertTicketStatus.DUPLICATE_PDF_HASH
        }

        val ticketId = conn.prepareStatement(
            """
            INSERT INTO tickets (
                user_id, ticket_number, pdf_sha256, gmail_message_id,
                store_name, store_address, date_time_iso, date_display,
                total_amount, total_vat_amount, created_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setLong(1, userId)
            ps.setString(2, ticket.ticketNumber)
            ps.setString(3, pdfSha256)
            if (cleanMsgId == null) ps.setNull(4, Types.VARCHAR) else ps.setString(4, cleanMsgId)
            ps.setString(5, ticket.storeName)
            ps.setString(6, ticket.storeAddress)
            ps.setString(7, ticket.dateTimeIso)
            ps.setString(8, ticket.dateDisplay)
            ps.setDouble(9, ticket.totalAmount)
            ps.setDouble(10, ticket.totalVatAmount)
            ps.setLong(11, System.currentTimeMillis())
            ps.executeUpdate()
            ps.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("No se obtuvo ID al insertar ticket.")
            }
        }

        conn.prepareStatement(
            """
            INSERT INTO ticket_items (ticket_id, quantity, unit, description, unit_price, total_price)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            for (item in ticket.items) {
                ps.setLong(1, ticketId)
                ps.setDouble(2, item.quantity)
                if (item.unit == null) ps.setNull(3, Types.VARCHAR) else ps.setString(3, item.unit)
                ps.setString(4, item.description)
                if (item.unitPrice == null) ps.setNull(5, Types.REAL) else ps.setDouble(5, item.unitPrice!!)
                ps.setDouble(6, item.totalPrice)
                ps.addBatch()
            }
            ps.executeBatch()
        }

        conn.prepareStatement(
            """
            INSERT INTO ticket_vat (ticket_id, vat_rate_percent, base_amount, vat_amount, total_amount)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            for (vat in ticket.vatBreakdown) {
                ps.setLong(1, ticketId)
                ps.setInt(2, vat.vatRatePercent)
                ps.setDouble(3, vat.baseAmount)
                ps.setDouble(4, vat.vatAmount)
                ps.setDouble(5, vat.totalAmount)
                ps.addBatch()
            }
            ps.executeBatch()
        }

        InsertTicketStatus.INSERTED
    }

    fun getAllTickets(userId: Long): List<StoredTicket> = withConnection { conn ->
        val itemsByTicket = loadAllItemsForUser(conn, userId)
        val vatByTicket = loadAllVatForUser(conn, userId)
        val tickets = mutableListOf<StoredTicket>()

        conn.prepareStatement(
            """
            SELECT id, ticket_number, pdf_sha256, gmail_message_id, store_name, store_address,
                   date_time_iso, date_display, total_amount, total_vat_amount
            FROM tickets
            WHERE user_id = ?
            ORDER BY date_time_iso DESC, id DESC
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = rs.getLong(1)
                    tickets.add(
                        StoredTicket(
                            id = id,
                            ticketNumber = rs.getString(2),
                            pdfSha256 = rs.getString(3),
                            gmailMessageId = rs.getString(4),
                            storeName = rs.getString(5),
                            storeAddress = rs.getString(6),
                            dateTimeIso = rs.getString(7),
                            dateDisplay = rs.getString(8),
                            totalAmount = rs.getDouble(9),
                            totalVatAmount = rs.getDouble(10),
                            items = itemsByTicket[id].orEmpty(),
                            vatBreakdown = vatByTicket[id].orEmpty()
                        )
                    )
                }
            }
        }
        tickets
    }

    fun getTicketById(userId: Long, ticketId: Long): StoredTicket? = withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT id, ticket_number, pdf_sha256, gmail_message_id, store_name, store_address,
                   date_time_iso, date_display, total_amount, total_vat_amount
            FROM tickets
            WHERE user_id = ? AND id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, userId)
            ps.setLong(2, ticketId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@withConnection null
                StoredTicket(
                    id = ticketId,
                    ticketNumber = rs.getString(2),
                    pdfSha256 = rs.getString(3),
                    gmailMessageId = rs.getString(4),
                    storeName = rs.getString(5),
                    storeAddress = rs.getString(6),
                    dateTimeIso = rs.getString(7),
                    dateDisplay = rs.getString(8),
                    totalAmount = rs.getDouble(9),
                    totalVatAmount = rs.getDouble(10),
                    items = loadItems(conn, ticketId),
                    vatBreakdown = loadVat(conn, ticketId)
                )
            }
        }
    }

    fun deleteTicket(userId: Long, ticketId: Long): Boolean = withWriteTransaction { conn ->
        conn.prepareStatement("DELETE FROM tickets WHERE user_id = ? AND id = ?").use { ps ->
            ps.setLong(1, userId)
            ps.setLong(2, ticketId)
            ps.executeUpdate() > 0
        }
    }

    // -------------------------------------------------------------------------
    // Gmail OAuth tokens
    // -------------------------------------------------------------------------

    fun saveGmailTokens(userId: Long, accessToken: String, refreshToken: String?, expiresAtMs: Long) =
        withWriteTransaction { conn ->
            val now = System.currentTimeMillis()
            val existingRefresh = if (refreshToken.isNullOrBlank()) {
                getGmailTokensInternal(conn, userId)?.refreshToken
            } else {
                refreshToken
            }
            conn.prepareStatement(
                """
                INSERT INTO gmail_oauth_tokens (user_id, access_token, refresh_token, expires_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    access_token = excluded.access_token,
                    refresh_token = excluded.refresh_token,
                    expires_at_ms = excluded.expires_at_ms,
                    updated_at_ms = excluded.updated_at_ms
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setString(2, accessToken)
                if (existingRefresh.isNullOrBlank()) ps.setNull(3, Types.VARCHAR) else ps.setString(3, existingRefresh)
                ps.setLong(4, expiresAtMs)
                ps.setLong(5, now)
                ps.executeUpdate()
            }
        }

    fun getGmailTokens(userId: Long): GmailTokenRecord? = withConnection { conn ->
        getGmailTokensInternal(conn, userId)
    }

    fun listGmailConnectedUserIds(): List<Long> = withConnection { conn ->
        val ids = mutableListOf<Long>()
        conn.prepareStatement("SELECT user_id FROM gmail_oauth_tokens").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) ids.add(rs.getLong(1))
            }
        }
        ids
    }

    fun deleteGmailTokens(userId: Long) = withWriteTransaction { conn ->
        conn.prepareStatement("DELETE FROM gmail_oauth_tokens WHERE user_id = ?").use { ps ->
            ps.setLong(1, userId)
            ps.executeUpdate()
        }
    }

    // -------------------------------------------------------------------------
    // Sync state
    // -------------------------------------------------------------------------

    fun recordSyncState(userId: Long, source: String, status: String, message: String) =
        withWriteTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO sync_state (user_id, source, last_run_ms, status, message)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(user_id, source) DO UPDATE SET
                    last_run_ms = excluded.last_run_ms,
                    status = excluded.status,
                    message = excluded.message
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setString(2, source)
                ps.setLong(3, System.currentTimeMillis())
                ps.setString(4, status)
                ps.setString(5, message)
                ps.executeUpdate()
            }
        }

    fun getSyncStates(userId: Long): List<SyncStateRecord> = withConnection { conn ->
        val list = mutableListOf<SyncStateRecord>()
        conn.prepareStatement(
            "SELECT user_id, source, last_run_ms, status, message FROM sync_state WHERE user_id = ? ORDER BY source ASC"
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    list.add(
                        SyncStateRecord(
                            userId = rs.getLong(1),
                            source = rs.getString(2),
                            lastRunMs = rs.getLong(3),
                            status = rs.getString(4),
                            message = rs.getString(5)
                        )
                    )
                }
            }
        }
        list
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun ticketExists(conn: Connection, userId: Long, columnPredicate: String, value: String): Boolean {
        conn.prepareStatement("SELECT 1 FROM tickets WHERE user_id = ? AND $columnPredicate LIMIT 1").use { ps ->
            ps.setLong(1, userId)
            ps.setString(2, value)
            ps.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    private fun loadItems(conn: Connection, ticketId: Long): List<ParsedTicketItem> {
        val list = mutableListOf<ParsedTicketItem>()
        conn.prepareStatement(
            "SELECT quantity, unit, description, unit_price, total_price FROM ticket_items WHERE ticket_id = ? ORDER BY id ASC"
        ).use { ps ->
            ps.setLong(1, ticketId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val unitPriceObj = rs.getObject(4)
                    list.add(
                        ParsedTicketItem(
                            quantity = rs.getDouble(1),
                            unit = rs.getString(2),
                            description = rs.getString(3),
                            unitPrice = if (unitPriceObj == null) null else rs.getDouble(4),
                            totalPrice = rs.getDouble(5)
                        )
                    )
                }
            }
        }
        return list
    }

    private fun loadVat(conn: Connection, ticketId: Long): List<ParsedVatBreakdown> {
        val list = mutableListOf<ParsedVatBreakdown>()
        conn.prepareStatement(
            "SELECT vat_rate_percent, base_amount, vat_amount, total_amount FROM ticket_vat WHERE ticket_id = ? ORDER BY vat_rate_percent ASC"
        ).use { ps ->
            ps.setLong(1, ticketId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    list.add(
                        ParsedVatBreakdown(
                            vatRatePercent = rs.getInt(1),
                            baseAmount = rs.getDouble(2),
                            vatAmount = rs.getDouble(3),
                            totalAmount = rs.getDouble(4)
                        )
                    )
                }
            }
        }
        return list
    }

    private fun loadAllItemsForUser(conn: Connection, userId: Long): Map<Long, List<ParsedTicketItem>> {
        val map = mutableMapOf<Long, MutableList<ParsedTicketItem>>()
        conn.prepareStatement(
            """
            SELECT i.ticket_id, i.quantity, i.unit, i.description, i.unit_price, i.total_price
            FROM ticket_items i
            JOIN tickets t ON t.id = i.ticket_id
            WHERE t.user_id = ?
            ORDER BY i.ticket_id ASC, i.id ASC
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val ticketId = rs.getLong(1)
                    val unitPriceObj = rs.getObject(5)
                    map.getOrPut(ticketId) { mutableListOf() }.add(
                        ParsedTicketItem(
                            quantity = rs.getDouble(2),
                            unit = rs.getString(3),
                            description = rs.getString(4),
                            unitPrice = if (unitPriceObj == null) null else rs.getDouble(5),
                            totalPrice = rs.getDouble(6)
                        )
                    )
                }
            }
        }
        return map
    }

    private fun loadAllVatForUser(conn: Connection, userId: Long): Map<Long, List<ParsedVatBreakdown>> {
        val map = mutableMapOf<Long, MutableList<ParsedVatBreakdown>>()
        conn.prepareStatement(
            """
            SELECT v.ticket_id, v.vat_rate_percent, v.base_amount, v.vat_amount, v.total_amount
            FROM ticket_vat v
            JOIN tickets t ON t.id = v.ticket_id
            WHERE t.user_id = ?
            ORDER BY v.ticket_id ASC, v.vat_rate_percent ASC
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val ticketId = rs.getLong(1)
                    map.getOrPut(ticketId) { mutableListOf() }.add(
                        ParsedVatBreakdown(
                            vatRatePercent = rs.getInt(2),
                            baseAmount = rs.getDouble(3),
                            vatAmount = rs.getDouble(4),
                            totalAmount = rs.getDouble(5)
                        )
                    )
                }
            }
        }
        return map
    }

    private fun getGmailTokensInternal(conn: Connection, userId: Long): GmailTokenRecord? {
        conn.prepareStatement(
            "SELECT user_id, access_token, refresh_token, expires_at_ms, updated_at_ms FROM gmail_oauth_tokens WHERE user_id = ?"
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) {
                    GmailTokenRecord(
                        userId = rs.getLong(1),
                        accessToken = rs.getString(2),
                        refreshToken = rs.getString(3),
                        expiresAtMs = rs.getLong(4),
                        updatedAtMs = rs.getLong(5)
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun mapUser(rs: java.sql.ResultSet): UserRecord = UserRecord(
        id = rs.getLong(1),
        username = rs.getString(2),
        displayName = rs.getString(3),
        email = rs.getString(4),
        passwordHash = rs.getString(5),
        isAdmin = rs.getInt(6) != 0,
        authSource = rs.getString(7),
        createdAtMs = rs.getLong(8)
    )
}
