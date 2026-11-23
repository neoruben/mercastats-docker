package io.github.neoruben.mercadonastats.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import io.github.neoruben.mercadonastats.server.config.AuthMode
import io.github.neoruben.mercadonastats.server.config.ServerConfig
import io.github.neoruben.mercadonastats.server.db.Database
import io.github.neoruben.mercadonastats.server.db.UserRecord
import io.github.neoruben.mercadonastats.stats.StatsCalculator
import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

sealed class AuthResolution {
    data class Authenticated(val user: UserRecord) : AuthResolution()
    data class Unauthenticated(val message: String) : AuthResolution()
    data class Forbidden(val message: String) : AuthResolution()
}

class AuthService(
    private val config: ServerConfig,
    private val db: Database
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val secureRandom = SecureRandom()
    private val trustedProxyMatcher = IpCidrMatcher(config.trustedProxies)

    companion object {
        const val SESSION_COOKIE_NAME = "mercastats_session"
        private const val BCRYPT_COST = 12
        private val USERNAME_REGEX = Regex("^[a-zA-Z0-9._@-]{2,64}$")
    }

    /**
     * Inicializa el usuario administrador o por defecto en el primer arranque.
     */
    fun bootstrap() {
        db.deleteExpiredSessions()
        when (config.authMode) {
            AuthMode.LOCAL -> {
                if (db.countUsers() == 0) {
                    val password = config.adminPassword.ifBlank {
                        val generated = generateRandomPassword(20)
                        logger.warn("================================================================")
                        logger.warn("ADMIN_PASSWORD no definida. Contraseña generada para '${config.adminUser}':")
                        logger.warn("  Usuario:    {}", config.adminUser)
                        logger.warn("  Contraseña: {}", generated)
                        logger.warn("Cámbiala tras iniciar sesión o define ADMIN_PASSWORD en tu .env.")
                        logger.warn("================================================================")
                        generated
                    }
                    db.createUser(
                        username = config.adminUser,
                        displayName = config.adminUser,
                        email = null,
                        passwordHash = hashPassword(password),
                        isAdmin = true,
                        authSource = "local"
                    )
                    logger.info("Usuario administrador '{}' creado en modo AUTH_MODE=local.", config.adminUser)
                }
            }

            AuthMode.PROXY -> {
                require(config.trustedProxies.isNotEmpty()) {
                    "AUTH_MODE=proxy requiere TRUSTED_PROXIES con la IP exacta de tu reverse proxy."
                }
                logger.info(
                    "Autenticación en modo PROXY. Cabecera: {}, proxies de confianza: {}, administrador: '{}'",
                    config.proxyUserHeader,
                    config.trustedProxies,
                    config.adminUser
                )
            }

            AuthMode.NONE -> {
                if (db.findUserByUsername(config.adminUser) == null) {
                    db.createUser(
                        username = config.adminUser,
                        displayName = config.adminUser,
                        email = null,
                        passwordHash = null,
                        isAdmin = true,
                        authSource = "none"
                    )
                }
                logger.warn(
                    "AUTH_MODE=none activo (acceso sin autenticación como '{}'). No expongas este puerto directamente a Internet.",
                    config.adminUser
                )
            }
        }
    }

    fun resolveUser(call: ApplicationCall): AuthResolution = when (config.authMode) {
        AuthMode.NONE -> {
            val user = db.findUserByUsername(config.adminUser)
                ?: db.createUser(config.adminUser, config.adminUser, null, null, isAdmin = true, authSource = "none")
            AuthResolution.Authenticated(user)
        }

        AuthMode.LOCAL -> {
            val rawToken = call.request.cookies[SESSION_COOKIE_NAME]
            if (rawToken.isNullOrBlank()) {
                AuthResolution.Unauthenticated("Inicia sesión para continuar.")
            } else {
                val user = db.findUserBySessionTokenHash(hashSessionToken(rawToken))
                if (user != null) {
                    AuthResolution.Authenticated(user)
                } else {
                    AuthResolution.Unauthenticated("Sesión expirada o no válida.")
                }
            }
        }

        AuthMode.PROXY -> resolveProxyUser(call)
    }

    private fun resolveProxyUser(call: ApplicationCall): AuthResolution {
        // Usar estrictamente la IP TCP directa del peer (no X-Forwarded-For manipulable)
        val remotePeerIp = call.request.local.remoteAddress
        if (!trustedProxyMatcher.matches(remotePeerIp)) {
            logger.warn("Rechazada petición AUTH_MODE=proxy desde IP no confiable: {}", remotePeerIp)
            return AuthResolution.Forbidden("Petición recibida desde un proxy no autorizado ($remotePeerIp).")
        }

        val proxyUsername = call.request.headers[config.proxyUserHeader]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return AuthResolution.Unauthenticated(
                "Falta la cabecera de usuario del proxy (${config.proxyUserHeader})."
            )
        if (!USERNAME_REGEX.matches(proxyUsername)) {
            return AuthResolution.Forbidden("Nombre de usuario del proxy no válido.")
        }

        val proxyEmail = call.request.headers[config.proxyEmailHeader]?.trim()?.takeIf { it.isNotEmpty() }
        val proxyName = call.request.headers[config.proxyNameHeader]?.trim()?.takeIf { it.isNotEmpty() } ?: proxyUsername

        val existing = db.findUserByUsername(proxyUsername)
        val user = if (existing != null) {
            // No heredar cuentas creadas en otro modo (local/none): conservarían sus permisos
            // y datos sin que nadie lo haya decidido. Hay que migrarlas a propósito.
            if (existing.authSource != "proxy") {
                logger.warn(
                    "Usuario de proxy '{}' coincide con una cuenta '{}' existente; acceso denegado.",
                    proxyUsername,
                    existing.authSource
                )
                return AuthResolution.Forbidden(
                    "La cuenta '$proxyUsername' se creó en modo ${existing.authSource} y no se vincula automáticamente al proxy."
                )
            }
            if (existing.displayName != proxyName || existing.email != proxyEmail) {
                db.updateUserProfile(existing.id, proxyName, proxyEmail)
                existing.copy(displayName = proxyName, email = proxyEmail)
            } else {
                existing
            }
        } else {
            // Solo ADMIN_USER es administrador; nunca "el primero que llegue".
            val shouldBeAdmin = proxyUsername.equals(config.adminUser, ignoreCase = true)
            db.createUser(
                username = proxyUsername,
                displayName = proxyName,
                email = proxyEmail,
                passwordHash = null,
                isAdmin = shouldBeAdmin,
                authSource = "proxy"
            )
        }
        return AuthResolution.Authenticated(user)
    }

    fun authenticateLocal(username: String, password: String): UserRecord? {
        val user = db.findUserByUsername(username) ?: return null
        val hash = user.passwordHash ?: return null
        return if (verifyPassword(password, hash)) user else null
    }

    fun createSessionCookie(call: ApplicationCall, user: UserRecord) {
        val rawToken = generateSessionToken()
        val tokenHash = hashSessionToken(rawToken)
        val ttlSeconds = config.sessionTtlDays * 24 * 60 * 60
        val expiresAtMs = System.currentTimeMillis() + ttlSeconds * 1000L
        db.createSession(tokenHash, user.id, expiresAtMs)

        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = rawToken,
                path = "/",
                maxAge = ttlSeconds,
                httpOnly = true,
                secure = config.cookieSecure,
                extensions = mapOf("SameSite" to "Lax")
            )
        )
    }

    fun clearSessionCookie(call: ApplicationCall) {
        val rawToken = call.request.cookies[SESSION_COOKIE_NAME]
        if (!rawToken.isNullOrBlank()) {
            db.deleteSession(hashSessionToken(rawToken))
        }
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = "",
                path = "/",
                maxAge = 0,
                httpOnly = true,
                secure = config.cookieSecure,
                extensions = mapOf("SameSite" to "Lax")
            )
        )
    }

    fun registerLocalUser(
        username: String,
        password: String,
        displayName: String? = null,
        email: String? = null,
        isAdmin: Boolean = false
    ): Result<UserRecord> {
        val cleanUser = username.trim()
        if (!USERNAME_REGEX.matches(cleanUser)) {
            return Result.failure(
                IllegalArgumentException("Usuario inválido. Usa entre 2 y 64 caracteres (letras, números, ., _, @ o -).")
            )
        }
        if (password.length < 8) {
            return Result.failure(IllegalArgumentException("La contraseña debe tener al menos 8 caracteres."))
        }
        if (db.findUserByUsername(cleanUser) != null) {
            return Result.failure(IllegalArgumentException("El usuario '$cleanUser' ya existe."))
        }
        val created = db.createUser(
            username = cleanUser,
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: cleanUser,
            email = email,
            passwordHash = hashPassword(password),
            isAdmin = isAdmin,
            authSource = "local"
        )
        return Result.success(created)
    }

    fun changePassword(user: UserRecord, currentPassword: String, newPassword: String): Result<Unit> {
        if (config.authMode != AuthMode.LOCAL) {
            return Result.failure(IllegalStateException("El cambio de contraseña solo está disponible en AUTH_MODE=local."))
        }
        val currentHash = user.passwordHash
            ?: return Result.failure(IllegalStateException("Esta cuenta no tiene contraseña local."))
        if (!verifyPassword(currentPassword, currentHash)) {
            return Result.failure(IllegalArgumentException("La contraseña actual no es correcta."))
        }
        if (newPassword.length < 8) {
            return Result.failure(IllegalArgumentException("La nueva contraseña debe tener al menos 8 caracteres."))
        }
        db.updateUserPassword(user.id, hashPassword(newPassword))
        return Result.success(Unit)
    }

    fun hashPassword(password: String): String {
        val chars = password.toCharArray()
        return try {
            BCrypt.withDefaults().hashToString(BCRYPT_COST, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    fun verifyPassword(password: String, hash: String): Boolean {
        val chars = password.toCharArray()
        return try {
            BCrypt.verifyer().verify(chars, hash).verified
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun generateSessionToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashSessionToken(rawToken: String): String =
        StatsCalculator.sha256Hex(rawToken.toByteArray(Charsets.UTF_8))

    private fun generateRandomPassword(length: Int): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%&*"
        return buildString(length) {
            repeat(length) {
                append(alphabet[secureRandom.nextInt(alphabet.length)])
            }
        }
    }
}
