package io.github.neoruben.mercadonastats.server

import io.github.neoruben.mercadonastats.server.api.ErrorResponse
import io.github.neoruben.mercadonastats.server.api.configureRoutes
import io.github.neoruben.mercadonastats.server.auth.AuthService
import io.github.neoruben.mercadonastats.server.config.ServerConfig
import io.github.neoruben.mercadonastats.server.db.Database
import io.github.neoruben.mercadonastats.server.service.GmailOAuthService
import io.github.neoruben.mercadonastats.server.service.ImapSyncService
import io.github.neoruben.mercadonastats.server.service.InboxWatcherService
import io.github.neoruben.mercadonastats.server.service.TicketImportService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

fun main() {
    val logger = LoggerFactory.getLogger("MercaStatsServer")
    val config = ServerConfig.fromEnv()
    logger.info(
        "Iniciando MercaStats Server en {}:{} (DB: {}, AUTH_MODE: {})",
        config.host,
        config.port,
        config.dbPath,
        config.authMode.name.lowercase()
    )

    embeddedServer(Netty, port = config.port, host = config.host) {
        mercaStatsModule(config = config, startBackgroundWorkers = true)
    }.start(wait = true)
}

fun Application.mercaStatsModule(
    config: ServerConfig,
    startBackgroundWorkers: Boolean = true
) {
    val logger = LoggerFactory.getLogger("MercaStatsApp")
    val db = Database(config.dbPath)
    val authService = AuthService(config, db)
    authService.bootstrap()

    val importService = TicketImportService(db)
    val inboxWatcherService = InboxWatcherService(config, db, importService)
    val imapSyncService = ImapSyncService(config, db, importService)
    val gmailOAuthService = GmailOAuthService(config, db, importService)

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "SAMEORIGIN")
        header("Referrer-Policy", "same-origin")
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                prettyPrint = false
            }
        )
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Petición inválida."))
        }
        exception<Throwable> { call, cause ->
            logger.error("Error no controlado en petición HTTP", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Error interno del servidor."))
        }
    }

    configureRoutes(
        config = config,
        db = db,
        authService = authService,
        importService = importService,
        inboxWatcherService = inboxWatcherService,
        imapSyncService = imapSyncService,
        gmailOAuthService = gmailOAuthService
    )

    if (startBackgroundWorkers) {
        val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        monitor.subscribe(ApplicationStarted) {
            inboxWatcherService.start(backgroundScope)
            imapSyncService.startScheduler(backgroundScope)
            gmailOAuthService.startScheduler(backgroundScope)
        }
        monitor.subscribe(ApplicationStopping) {
            backgroundScope.cancel()
        }
    }
}
