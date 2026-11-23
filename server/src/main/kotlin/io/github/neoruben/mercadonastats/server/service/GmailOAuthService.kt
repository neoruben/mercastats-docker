package io.github.neoruben.mercadonastats.server.service

import io.github.neoruben.mercadonastats.server.config.GmailOAuthConfig
import io.github.neoruben.mercadonastats.server.config.ServerConfig
import io.github.neoruben.mercadonastats.server.db.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Integración opcional de Gmail OAuth 2.0 (credenciales web propias del usuario) que reutiliza
 * la misma lógica REST contra `https://gmail.googleapis.com/gmail/v1/users/me` que la app Android
 * (`GmailMercadonaService.kt`), sin dependencias de Android ni `org.json`.
 */
class GmailOAuthService(
    private val config: ServerConfig,
    private val db: Database,
    private val importService: TicketImportService
) {
    private val logger = LoggerFactory.getLogger(GmailOAuthService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    private data class PendingOAuthState(val userId: Long, val expiresAtMs: Long)
    private val pendingStates = ConcurrentHashMap<String, PendingOAuthState>()
    private val syncingUsers = ConcurrentHashMap.newKeySet<Long>()

    companion object {
        const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
        const val DEFAULT_MERCADONA_QUERY = "from:ticket_digital@mail.mercadona.com"
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val GMAIL_API_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    }

    private data class PdfAttachmentInfo(
        val filename: String,
        val attachmentId: String,
        val sizeBytes: Int
    )

    fun isConfigured(): Boolean = config.gmailOAuth != null

    fun isConnected(userId: Long): Boolean = db.getGmailTokens(userId) != null

    fun disconnect(userId: Long) {
        db.deleteGmailTokens(userId)
    }

    fun createAuthorizationUrl(userId: Long): String {
        val oauth = config.gmailOAuth
            ?: throw IllegalStateException("Gmail OAuth no está configurado (define GMAIL_CLIENT_ID y GMAIL_CLIENT_SECRET).")

        purgeExpiredStates()
        val stateBytes = ByteArray(24)
        secureRandom.nextBytes(stateBytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
        pendingStates[state] = PendingOAuthState(userId, System.currentTimeMillis() + 10 * 60_000L)

        val params = mapOf(
            "client_id" to oauth.clientId,
            "redirect_uri" to oauth.redirectUri,
            "response_type" to "code",
            "scope" to GMAIL_READONLY_SCOPE,
            "access_type" to "offline",
            "prompt" to "consent",
            "state" to state
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$AUTH_ENDPOINT?$query"
    }

    suspend fun handleCallback(state: String, code: String): Long = withContext(Dispatchers.IO) {
        val oauth = config.gmailOAuth
            ?: throw IllegalStateException("Gmail OAuth no está configurado.")

        val entry = pendingStates.remove(state)
            ?: throw IllegalArgumentException("Estado OAuth inválido o expirado. Vuelve a iniciar la conexión.")
        if (System.currentTimeMillis() > entry.expiresAtMs) {
            throw IllegalArgumentException("La sesión OAuth ha caducado. Vuelve a intentarlo.")
        }

        val response = postFormUrlEncoded(
            TOKEN_ENDPOINT,
            mapOf(
                "client_id" to oauth.clientId,
                "client_secret" to oauth.clientSecret,
                "code" to code,
                "grant_type" to "authorization_code",
                "redirect_uri" to oauth.redirectUri
            )
        )

        val accessToken = response["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val refreshToken = response["refresh_token"]?.jsonPrimitive?.contentOrNull
        val expiresIn = response["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L

        if (accessToken.isBlank()) {
            throw IllegalStateException("Google no devolvió access_token.")
        }

        val expiresAtMs = System.currentTimeMillis() + (expiresIn - 60L).coerceAtLeast(60L) * 1000L
        db.saveGmailTokens(entry.userId, accessToken, refreshToken, expiresAtMs)
        entry.userId
    }

    fun startScheduler(scope: CoroutineScope) {
        val oauth = config.gmailOAuth ?: return
        if (oauth.syncIntervalMinutes <= 0) return

        logger.info("Sincronización periódica Gmail OAuth activa cada {} min.", oauth.syncIntervalMinutes)
        scope.launch {
            delay(20_000L)
            while (isActive) {
                val userIds = db.listGmailConnectedUserIds()
                for (uid in userIds) {
                    try {
                        val res = syncForUser(uid)
                        logger.info("Gmail OAuth sync programado (userId={}): {}", uid, res.message)
                    } catch (e: Exception) {
                        logger.warn("Error en Gmail OAuth sync (userId={}): {}", uid, e.message)
                    }
                }
                delay(oauth.syncIntervalMinutes * 60_000L)
            }
        }
    }

    suspend fun syncForUser(userId: Long): SyncRunResult = withContext(Dispatchers.IO) {
        val oauth = config.gmailOAuth
            ?: throw IllegalStateException("Gmail OAuth no está configurado en el servidor.")

        if (!syncingUsers.add(userId)) {
            throw IllegalStateException("Ya hay una sincronización de Gmail en curso para este usuario.")
        }

        try {
            val accessToken = getValidAccessToken(userId, oauth)
            val allIds = listMercadonaMessageIds(accessToken)

            db.deleteTicketsWithoutItems(userId)
            val knownIds = db.getKnownMessageIds(userId)
            val newIds = allIds.filterNot { it in knownIds }

            if (newIds.isEmpty()) {
                val msg = "Todo al día: ya tienes tus ${allIds.size} tickets de Gmail guardados."
                db.recordSyncState(userId, "gmail", "ok", msg)
                return@withContext SyncRunResult("gmail", allIds.size, 0, 0, 0, 0, msg)
            }

            var inserted = 0
            var duplicates = 0
            var failed = 0

            for (msgId in newIds) {
                try {
                    val attachments = getMessagePdfAttachments(accessToken, msgId)
                    if (attachments.isEmpty()) {
                        failed++
                        continue
                    }
                    attachments.forEachIndexed { idx, att ->
                        val pdfBytes = downloadPdfAttachment(accessToken, msgId, att.attachmentId)
                        val storeMsgId = if (idx == 0) msgId else "$msgId#$idx"
                        val res = importService.importPdfBytes(userId, pdfBytes, att.filename, storeMsgId)
                        when (res.status) {
                            "inserted" -> inserted++
                            "error" -> failed++
                            else -> duplicates++
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Error procesando correo Gmail msgId={}: {}", msgId, e.message)
                    failed++
                }
            }

            val summaryMsg = buildString {
                append("Gmail completado: $inserted nuevos")
                if (duplicates > 0) append(", $duplicates duplicados")
                if (failed > 0) append(", $failed sin PDF válido")
            }
            db.recordSyncState(userId, "gmail", "ok", summaryMsg)

            SyncRunResult(
                source = "gmail",
                totalMatched = allIds.size,
                processedNew = newIds.size,
                inserted = inserted,
                duplicates = duplicates,
                failed = failed,
                message = summaryMsg
            )
        } catch (e: Exception) {
            val errMsg = "Error Gmail: ${e.message ?: e.javaClass.simpleName}"
            db.recordSyncState(userId, "gmail", "error", errMsg)
            throw e
        } finally {
            syncingUsers.remove(userId)
        }
    }

    private fun getValidAccessToken(userId: Long, oauth: GmailOAuthConfig): String {
        val record = db.getGmailTokens(userId)
            ?: throw IllegalStateException("Tu cuenta de Gmail no está conectada.")

        if (System.currentTimeMillis() < record.expiresAtMs && record.accessToken.isNotBlank()) {
            return record.accessToken
        }

        val refreshToken = record.refreshToken
            ?: throw IllegalStateException("Sesión de Gmail expirada (sin refresh_token). Vuelve a conectar Gmail.")

        val refreshed = postFormUrlEncoded(
            TOKEN_ENDPOINT,
            mapOf(
                "client_id" to oauth.clientId,
                "client_secret" to oauth.clientSecret,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token"
            )
        )
        val newAccessToken = refreshed["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expiresIn = refreshed["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        if (newAccessToken.isBlank()) {
            throw IllegalStateException("No se pudo renovar el token de Gmail.")
        }

        val expiresAtMs = System.currentTimeMillis() + (expiresIn - 60L).coerceAtLeast(60L) * 1000L
        db.saveGmailTokens(userId, newAccessToken, refreshToken, expiresAtMs)
        return newAccessToken
    }

    private fun listMercadonaMessageIds(accessToken: String, query: String = DEFAULT_MERCADONA_QUERY): List<String> {
        val allIds = mutableListOf<String>()
        var pageToken: String? = null

        do {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlBuilder = StringBuilder("$GMAIL_API_BASE/messages?q=$encodedQuery&maxResults=100")
            if (!pageToken.isNullOrBlank()) {
                urlBuilder.append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
            }

            val listJson = getJson(urlBuilder.toString(), accessToken)
            val messagesArray = listJson["messages"] as? JsonArray
            if (messagesArray != null) {
                for (element in messagesArray) {
                    val id = element.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    if (!id.isNullOrBlank()) allIds.add(id)
                }
            }
            pageToken = listJson["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } while (pageToken != null)

        return allIds
    }

    private fun getMessagePdfAttachments(accessToken: String, msgId: String): List<PdfAttachmentInfo> {
        val msgJson = getJson("$GMAIL_API_BASE/messages/$msgId?format=full", accessToken)
        val payload = msgJson["payload"] as? JsonObject ?: return emptyList()
        val attachments = mutableListOf<PdfAttachmentInfo>()
        extractPdfAttachments(payload, attachments)
        return attachments
    }

    private fun extractPdfAttachments(part: JsonObject, out: MutableList<PdfAttachmentInfo>) {
        val filename = part["filename"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val mimeType = part["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val body = part["body"] as? JsonObject
        val attachmentId = body?.get("attachmentId")?.jsonPrimitive?.contentOrNull.orEmpty()
        val size = body?.get("size")?.jsonPrimitive?.intOrNull ?: 0

        val isPdf = mimeType == "application/pdf" || filename.endsWith(".pdf", ignoreCase = true)
        if (isPdf && attachmentId.isNotBlank()) {
            out.add(
                PdfAttachmentInfo(
                    filename = filename.ifBlank { "ticket.pdf" },
                    attachmentId = attachmentId,
                    sizeBytes = size
                )
            )
        }

        val subParts = part["parts"] as? JsonArray
        if (subParts != null) {
            for (sub in subParts) {
                extractPdfAttachments(sub.jsonObject, out)
            }
        }
    }

    private fun downloadPdfAttachment(accessToken: String, messageId: String, attachmentId: String): ByteArray {
        val obj = getJson("$GMAIL_API_BASE/messages/$messageId/attachments/$attachmentId", accessToken)
        val dataBase64Url = obj["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (dataBase64Url.isBlank()) {
            throw IllegalStateException("El adjunto PDF está vacío.")
        }
        return Base64.getUrlDecoder().decode(dataBase64Url)
    }

    private fun getJson(urlString: String, accessToken: String): JsonObject {
        val conn = (URI(urlString).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        val parsed = json.parseToJsonElement(text).jsonObject
        if (conn.responseCode !in 200..299) {
            val errorMsg = (parsed["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: text
            throw IllegalStateException("Error Gmail API (${conn.responseCode}): $errorMsg")
        }
        return parsed
    }

    private fun postFormUrlEncoded(urlString: String, params: Map<String, String>): JsonObject {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = (URI(urlString).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(body)
            writer.flush()
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        val parsed = json.parseToJsonElement(text).jsonObject
        if (conn.responseCode !in 200..299) {
            val err = parsed["error_description"]?.jsonPrimitive?.contentOrNull
                ?: parsed["error"]?.jsonPrimitive?.contentOrNull
                ?: "HTTP ${conn.responseCode}"
            throw IllegalStateException("Error OAuth (${conn.responseCode}): $err")
        }
        return parsed
    }

    private fun purgeExpiredStates() {
        val now = System.currentTimeMillis()
        pendingStates.entries.removeIf { it.value.expiresAtMs < now }
    }
}
