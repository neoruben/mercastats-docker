package io.github.neoruben.mercadonastats.server.service

import io.github.neoruben.mercadonastats.server.config.ImapConfig
import io.github.neoruben.mercadonastats.server.config.ServerConfig
import io.github.neoruben.mercadonastats.server.db.Database
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.FromStringTerm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

data class SyncRunResult(
    val source: String,
    val totalMatched: Int,
    val processedNew: Int,
    val inserted: Int,
    val duplicates: Int,
    val failed: Int,
    val message: String
)

/** Progreso de la sincronización en curso, para la barra de la web. */
data class ImapProgress(
    val phase: String,
    val done: Int,
    val total: Int,
    val inserted: Int,
    val duplicates: Int,
    val failed: Int
)

/**
 * Sincroniza tickets PDF desde un buzón IMAP (Gmail con contraseña de aplicación,
 * iCloud Mail, Fastmail, servidor propio, etc.) usando Jakarta Mail (Eclipse Angus).
 */
class ImapSyncService(
    private val config: ServerConfig,
    private val db: Database,
    private val importService: TicketImportService
) {
    private val logger = LoggerFactory.getLogger(ImapSyncService::class.java)
    private val syncing = AtomicBoolean(false)

    @Volatile
    var progress: ImapProgress? = null
        private set

    fun isSyncing(): Boolean = syncing.get()

    fun isConfigured(): Boolean = config.imap != null

    fun canUserTriggerSync(username: String, isAdmin: Boolean): Boolean {
        val imap = config.imap ?: return false
        return isAdmin || username.equals(imap.targetUsername, ignoreCase = true)
    }

    fun startScheduler(scope: CoroutineScope) {
        val imap = config.imap ?: return
        if (imap.syncIntervalMinutes <= 0) {
            logger.info("IMAP configurado pero sincronización periódica desactivada (IMAP_SYNC_INTERVAL_MINUTES=0).")
            return
        }

        logger.info(
            "Sincronización IMAP activa cada {} min ({} / carpeta '{}' / destino '{}').",
            imap.syncIntervalMinutes,
            imap.host,
            imap.folder,
            imap.targetUsername
        )

        scope.launch {
            // Pequeña espera inicial para no bloquear el arranque del servidor HTTP
            delay(10_000L)
            while (isActive) {
                try {
                    val result = syncNow()
                    logger.info("IMAP sync programado: {}", result.message)
                } catch (e: Exception) {
                    logger.error("Error en sincronización IMAP programada: {}", e.message)
                }
                delay(imap.syncIntervalMinutes * 60_000L)
            }
        }
    }

    suspend fun syncNow(explicitUserId: Long? = null): SyncRunResult = withContext(Dispatchers.IO) {
        val imap = config.imap
            ?: throw IllegalStateException("IMAP no está configurado. Define IMAP_HOST, IMAP_USER e IMAP_PASSWORD.")

        val targetUser = if (explicitUserId != null) {
            db.findUserById(explicitUserId)
        } else {
            db.findUserByUsername(imap.targetUsername)
        } ?: throw IllegalStateException("No existe el usuario destino '${imap.targetUsername}' para sincronización IMAP.")

        if (!syncing.compareAndSet(false, true)) {
            throw IllegalStateException("Ya hay una sincronización IMAP en curso.")
        }

        try {
            progress = ImapProgress("Conectando con el correo", 0, 0, 0, 0, 0)
            val result = executeImapSync(imap, targetUser.id)
            db.recordSyncState(targetUser.id, "imap", "ok", result.message)
            result
        } catch (e: Exception) {
            val msg = "Error IMAP: ${e.message ?: e.javaClass.simpleName}"
            db.recordSyncState(targetUser.id, "imap", "error", msg)
            throw e
        } finally {
            progress = null
            syncing.set(false)
        }
    }

    private fun executeImapSync(imap: ImapConfig, userId: Long): SyncRunResult {
        val protocol = if (imap.useSsl) "imaps" else "imap"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", imap.host)
            put("mail.$protocol.port", imap.port.toString())
            put("mail.$protocol.connectiontimeout", "15000")
            put("mail.$protocol.timeout", "30000")
            put("mail.$protocol.writetimeout", "15000")
            if (imap.useSsl) {
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.ssl.checkserveridentity", "true")
            } else {
                // Sin IMAPS el canal debe cifrarse con STARTTLS sí o sí: si el servidor no lo
                // ofrece se aborta antes de enviar la contraseña.
                put("mail.imap.starttls.enable", "true")
                put("mail.imap.starttls.required", "true")
                put("mail.imap.ssl.checkserveridentity", "true")
            }
        }

        val session = Session.getInstance(props)
        val store = session.getStore(protocol)
        try {
            store.connect(imap.host, imap.port, imap.username, imap.password)
            val folder = store.getFolder(imap.folder)
            if (!folder.exists()) {
                throw IllegalStateException("La carpeta IMAP '${imap.folder}' no existe.")
            }

            folder.open(Folder.READ_ONLY)
            try {
                progress = ImapProgress("Buscando tickets en '${imap.folder}'", 0, 0, 0, 0, 0)
                val matchedMessages = folder.search(FromStringTerm(imap.fromFilter))
                if (matchedMessages.isEmpty()) {
                    return SyncRunResult(
                        source = "imap",
                        totalMatched = 0,
                        processedNew = 0,
                        inserted = 0,
                        duplicates = 0,
                        failed = 0,
                        message = "Sin correos de ${imap.fromFilter} en '${imap.folder}'."
                    )
                }

                val fp = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add("Message-ID")
                    if (folder is UIDFolder) {
                        add(UIDFolder.FetchProfileItem.UID)
                    }
                }
                folder.fetch(matchedMessages, fp)

                db.deleteTicketsWithoutItems(userId)
                val knownMessageIds = db.getKnownMessageIds(userId)

                val uidFolder = folder as? UIDFolder
                var processedNew = 0
                var inserted = 0
                var duplicates = 0
                var failed = 0

                val pending = matchedMessages
                    .map { it to extractStableMessageId(it, imap.folder, uidFolder) }
                    .filter { (_, id) -> id !in knownMessageIds }

                fun report() {
                    progress = ImapProgress("Importando tickets", processedNew, pending.size, inserted, duplicates, failed)
                }
                report()

                for ((message, messageId) in pending) {
                    report()
                    processedNew++

                    try {
                        val attachments = mutableListOf<Pair<String, ByteArray>>()
                        collectPdfAttachments(message, attachments)

                        if (attachments.isEmpty()) {
                            failed++
                            continue
                        }

                        attachments.forEachIndexed { idx, (fileName, bytes) ->
                            // Si un mismo correo contiene más de un PDF, diferenciar su message-id
                            val perAttachmentMsgId = if (idx == 0) messageId else "$messageId#$idx"
                            val res = importService.importPdfBytes(userId, bytes, fileName, perAttachmentMsgId)
                            when (res.status) {
                                "inserted" -> inserted++
                                "error" -> failed++
                                else -> duplicates++
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Error procesando correo IMAP {}: {}", messageId, e.message)
                        failed++
                    }
                }
                report()

                val summaryMsg = if (processedNew == 0) {
                    "Todo al día: ${matchedMessages.size} correos revisados, sin tickets nuevos."
                } else {
                    buildString {
                        append("IMAP completado: $inserted nuevos")
                        if (duplicates > 0) append(", $duplicates duplicados")
                        if (failed > 0) append(", $failed sin PDF válido")
                    }
                }

                return SyncRunResult(
                    source = "imap",
                    totalMatched = matchedMessages.size,
                    processedNew = processedNew,
                    inserted = inserted,
                    duplicates = duplicates,
                    failed = failed,
                    message = summaryMsg
                )
            } finally {
                if (folder.isOpen) {
                    folder.close(false)
                }
            }
        } finally {
            if (store.isConnected) {
                store.close()
            }
        }
    }

    private fun extractStableMessageId(message: Message, folderName: String, uidFolder: UIDFolder?): String {
        val headerId = runCatching {
            message.getHeader("Message-ID")?.firstOrNull()?.trim()
        }.getOrNull()
        if (!headerId.isNullOrBlank()) {
            return headerId
        }
        val uid = runCatching { uidFolder?.getUID(message) }.getOrNull()
        return if (uid != null && uid > 0) {
            "imap:$folderName:uid-$uid"
        } else {
            "imap:$folderName:num-${message.messageNumber}"
        }
    }

    private fun collectPdfAttachments(part: Part, out: MutableList<Pair<String, ByteArray>>) {
        when {
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return
                for (i in 0 until multipart.count) {
                    collectPdfAttachments(multipart.getBodyPart(i), out)
                }
            }

            part.isMimeType("message/rfc822") -> {
                val nested = part.content as? Part ?: return
                collectPdfAttachments(nested, out)
            }

            else -> {
                val decodedName = part.fileName?.let { raw ->
                    runCatching { MimeUtility.decodeText(raw) }.getOrDefault(raw)
                }
                val isPdf = part.isMimeType("application/pdf") ||
                    (decodedName != null && decodedName.endsWith(".pdf", ignoreCase = true))

                if (isPdf) {
                    val bytes = part.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        out.add((decodedName?.ifBlank { "ticket.pdf" } ?: "ticket.pdf") to bytes)
                    }
                }
            }
        }
    }
}
