package io.github.neoruben.mercadonastats.server.config

enum class AuthMode {
    LOCAL,
    PROXY,
    NONE;

    companion object {
        fun parse(raw: String?): AuthMode = when (raw?.trim()?.lowercase()) {
            null, "", "local" -> LOCAL
            "proxy" -> PROXY
            "none" -> NONE
            else -> throw IllegalArgumentException("AUTH_MODE no válido: '$raw'. Valores permitidos: local, proxy, none.")
        }
    }
}

data class ImapConfig(
    val host: String,
    val port: Int,
    val useSsl: Boolean,
    val username: String,
    val password: String,
    val folder: String,
    val fromFilter: String,
    val syncIntervalMinutes: Int,
    val targetUsername: String
)

data class GmailOAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val syncIntervalMinutes: Int
)

data class ServerConfig(
    val host: String,
    val port: Int,
    val dataDir: String,
    val dbPath: String,
    val inboxDir: String,
    val inboxPollSeconds: Int,
    val inboxDefaultUser: String,
    val authMode: AuthMode,
    val adminUser: String,
    val adminPassword: String,
    val allowRegistration: Boolean,
    val cookieSecure: Boolean,
    val sessionTtlDays: Int,
    val proxyUserHeader: String,
    val proxyEmailHeader: String,
    val proxyNameHeader: String,
    val trustedProxies: List<String>,
    val imap: ImapConfig?,
    val gmailOAuth: GmailOAuthConfig?
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            fun get(key: String, default: String = ""): String =
                env[key]?.trim()?.takeIf { it.isNotEmpty() } ?: default

            val dataDir = get("DATA_DIR", "/data")
            val dbPath = get("DB_PATH", "$dataDir/mercastats.db")
            val inboxDir = get("INBOX_DIR", "$dataDir/inbox")
            val adminUser = get("ADMIN_USER", "admin")
            val authMode = AuthMode.parse(env["AUTH_MODE"])
            val port = get("PORT", "8080").toIntOrNull() ?: 8080
            val host = get("HOST", "0.0.0.0")
            val baseUrl = get("BASE_URL", "http://localhost:$port").trimEnd('/')

            // Una sola cabecera: el proxy debe sobrescribirla siempre. Aceptar varias permitiría
            // que el cliente colase otra (p. ej. Remote-User) que el proxy no reescribe.
            val proxyHeader = get("PROXY_USER_HEADER", "X-authentik-username")
            require(!proxyHeader.contains(',')) {
                "PROXY_USER_HEADER solo admite una cabecera (la que tu proxy sobrescribe), no una lista: '$proxyHeader'."
            }

            val trustedProxies = get("TRUSTED_PROXIES", "127.0.0.1,::1")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val imapHost = get("IMAP_HOST")
            val imapUser = get("IMAP_USER")
            val imapPassword = env["IMAP_PASSWORD"]?.trim().orEmpty()
            val imapSsl = get("IMAP_SSL", "true").equals("true", ignoreCase = true)
            val imapConfig = if (imapHost.isNotEmpty() && imapUser.isNotEmpty() && imapPassword.isNotEmpty()) {
                ImapConfig(
                    host = imapHost,
                    port = get("IMAP_PORT", if (imapSsl) "993" else "143").toIntOrNull() ?: if (imapSsl) 993 else 143,
                    useSsl = imapSsl,
                    username = imapUser,
                    password = imapPassword,
                    folder = get("IMAP_FOLDER", "INBOX"),
                    fromFilter = get("IMAP_FROM_FILTER", "ticket_digital@mail.mercadona.com"),
                    syncIntervalMinutes = (get("IMAP_SYNC_INTERVAL_MINUTES", "60").toIntOrNull() ?: 60).coerceAtLeast(0),
                    targetUsername = get("IMAP_TARGET_USER", adminUser)
                )
            } else {
                null
            }

            val gmailClientId = get("GMAIL_CLIENT_ID")
            val gmailClientSecret = env["GMAIL_CLIENT_SECRET"]?.trim().orEmpty()
            val gmailOAuthConfig = if (gmailClientId.isNotEmpty() && gmailClientSecret.isNotEmpty()) {
                GmailOAuthConfig(
                    clientId = gmailClientId,
                    clientSecret = gmailClientSecret,
                    redirectUri = get("GMAIL_REDIRECT_URI", "$baseUrl/api/gmail/oauth/callback"),
                    syncIntervalMinutes = (get("GMAIL_SYNC_INTERVAL_MINUTES", "60").toIntOrNull() ?: 60).coerceAtLeast(0)
                )
            } else {
                null
            }

            return ServerConfig(
                host = host,
                port = port,
                dataDir = dataDir,
                dbPath = dbPath,
                inboxDir = inboxDir,
                inboxPollSeconds = (get("INBOX_POLL_SECONDS", "30").toIntOrNull() ?: 30).coerceAtLeast(0),
                inboxDefaultUser = get("INBOX_DEFAULT_USER", adminUser),
                authMode = authMode,
                adminUser = adminUser,
                adminPassword = env["ADMIN_PASSWORD"]?.trim().orEmpty(),
                allowRegistration = get("ALLOW_REGISTRATION", "false").equals("true", ignoreCase = true),
                cookieSecure = get("COOKIE_SECURE", "false").equals("true", ignoreCase = true),
                sessionTtlDays = (get("SESSION_TTL_DAYS", "30").toIntOrNull() ?: 30).coerceAtLeast(1),
                proxyUserHeader = proxyHeader,
                proxyEmailHeader = get("PROXY_EMAIL_HEADER", "X-authentik-email"),
                proxyNameHeader = get("PROXY_NAME_HEADER", "X-authentik-name"),
                trustedProxies = trustedProxies,
                imap = imapConfig,
                gmailOAuth = gmailOAuthConfig
            )
        }
    }
}
