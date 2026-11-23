package io.github.neoruben.mercadonastats.server.api

import io.github.neoruben.mercadonastats.data.StoredTicket
import io.github.neoruben.mercadonastats.server.auth.AuthResolution
import io.github.neoruben.mercadonastats.server.auth.AuthService
import io.github.neoruben.mercadonastats.server.config.AuthMode
import io.github.neoruben.mercadonastats.server.config.ServerConfig
import io.github.neoruben.mercadonastats.server.db.Database
import io.github.neoruben.mercadonastats.server.db.UserRecord
import io.github.neoruben.mercadonastats.server.service.GmailOAuthService
import io.github.neoruben.mercadonastats.server.service.ImapSyncService
import io.github.neoruben.mercadonastats.server.service.ImportItemResult
import io.github.neoruben.mercadonastats.server.service.InboxWatcherService
import io.github.neoruben.mercadonastats.server.service.SyncRunResult
import io.github.neoruben.mercadonastats.server.service.TicketImportService
import io.github.neoruben.mercadonastats.stats.StatsCalculator
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import java.net.URLEncoder

fun Application.configureRoutes(
    config: ServerConfig,
    db: Database,
    authService: AuthService,
    importService: TicketImportService,
    inboxWatcherService: InboxWatcherService,
    imapSyncService: ImapSyncService,
    gmailOAuthService: GmailOAuthService
) {
    suspend fun requireUser(call: ApplicationCall): UserRecord? {
        return when (val res = authService.resolveUser(call)) {
            is AuthResolution.Authenticated -> res.user
            is AuthResolution.Unauthenticated -> {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(res.message))
                null
            }

            is AuthResolution.Forbidden -> {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse(res.message))
                null
            }
        }
    }

    suspend fun requireAdmin(call: ApplicationCall): UserRecord? {
        val user = requireUser(call) ?: return null
        if (!user.isAdmin) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Se requieren permisos de administrador."))
            return null
        }
        return user
    }

    routing {
        route("/api") {
            get("/health") {
                call.respond(HttpStatusCode.OK, HealthResponse("ok"))
            }

            // -----------------------------------------------------------------
            // Autenticación
            // -----------------------------------------------------------------
            route("/auth") {
                get("/me") {
                    when (val res = authService.resolveUser(call)) {
                        is AuthResolution.Authenticated -> {
                            val u = res.user
                            call.respond(
                                AuthMeResponse(
                                    authMode = config.authMode.name.lowercase(),
                                    authenticated = true,
                                    user = u.toDto(),
                                    allowRegistration = config.authMode == AuthMode.LOCAL && config.allowRegistration,
                                    imapConfigured = imapSyncService.isConfigured(),
                                    imapAllowed = imapSyncService.canUserTriggerSync(u.username, u.isAdmin),
                                    gmailOAuthConfigured = gmailOAuthService.isConfigured(),
                                    gmailConnected = gmailOAuthService.isConnected(u.id),
                                    inboxEnabled = config.inboxPollSeconds > 0
                                )
                            )
                        }

                        is AuthResolution.Unauthenticated -> {
                            call.respond(
                                AuthMeResponse(
                                    authMode = config.authMode.name.lowercase(),
                                    authenticated = false,
                                    user = null,
                                    allowRegistration = config.authMode == AuthMode.LOCAL && config.allowRegistration,
                                    imapConfigured = imapSyncService.isConfigured(),
                                    imapAllowed = false,
                                    gmailOAuthConfigured = gmailOAuthService.isConfigured(),
                                    gmailConnected = false,
                                    inboxEnabled = config.inboxPollSeconds > 0
                                )
                            )
                        }

                        is AuthResolution.Forbidden -> {
                            call.respond(HttpStatusCode.Forbidden, ErrorResponse(res.message))
                        }
                    }
                }

                post("/login") {
                    if (config.authMode != AuthMode.LOCAL) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("El login local no está activo (AUTH_MODE=${config.authMode.name.lowercase()}).")
                        )
                        return@post
                    }
                    val req = call.receive<LoginRequest>()
                    val user = authService.authenticateLocal(req.username, req.password)
                    if (user == null) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Usuario o contraseña incorrectos."))
                        return@post
                    }
                    authService.createSessionCookie(call, user)
                    call.respond(HttpStatusCode.OK, user.toDto())
                }

                post("/register") {
                    if (config.authMode != AuthMode.LOCAL || !config.allowRegistration) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("El registro libre está desactivado."))
                        return@post
                    }
                    val req = call.receive<RegisterRequest>()
                    val result = authService.registerLocalUser(
                        username = req.username,
                        password = req.password,
                        displayName = req.displayName,
                        email = req.email,
                        isAdmin = false
                    )
                    result.onSuccess { user ->
                        authService.createSessionCookie(call, user)
                        call.respond(HttpStatusCode.Created, user.toDto())
                    }.onFailure { err ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(err.message ?: "No se pudo crear el usuario."))
                    }
                }

                post("/logout") {
                    authService.clearSessionCookie(call)
                    call.respond(HttpStatusCode.OK, MessageResponseDto("Sesión cerrada."))
                }

                post("/password") {
                    val user = requireUser(call) ?: return@post
                    val req = call.receive<ChangePasswordRequest>()
                    authService.changePassword(user, req.currentPassword, req.newPassword)
                        .onSuccess {
                            call.respond(HttpStatusCode.OK, MessageResponseDto("Contraseña actualizada."))
                        }
                        .onFailure { err ->
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(err.message ?: "Error al cambiar contraseña."))
                        }
                }
            }

            // -----------------------------------------------------------------
            // Administración de usuarios (solo admin)
            // -----------------------------------------------------------------
            route("/admin/users") {
                get {
                    requireAdmin(call) ?: return@get
                    call.respond(db.listUsers().map { it.toDto() })
                }

                post {
                    requireAdmin(call) ?: return@post
                    if (config.authMode != AuthMode.LOCAL) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("La creación manual de usuarios solo aplica en AUTH_MODE=local.")
                        )
                        return@post
                    }
                    val req = call.receive<CreateUserRequest>()
                    authService.registerLocalUser(
                        username = req.username,
                        password = req.password,
                        displayName = req.displayName,
                        email = req.email,
                        isAdmin = req.isAdmin
                    ).onSuccess { created ->
                        call.respond(HttpStatusCode.Created, created.toDto())
                    }.onFailure { err ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(err.message ?: "No se pudo crear el usuario."))
                    }
                }

                delete("/{id}") {
                    val admin = requireAdmin(call) ?: return@delete
                    val targetId = call.parameters["id"]?.toLongOrNull()
                    if (targetId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de usuario inválido."))
                        return@delete
                    }
                    if (targetId == admin.id) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("No puedes eliminar tu propio usuario."))
                        return@delete
                    }
                    if (db.deleteUser(targetId)) {
                        call.respond(HttpStatusCode.OK, MessageResponseDto("Usuario eliminado."))
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Usuario no encontrado."))
                    }
                }
            }

            // -----------------------------------------------------------------
            // Tickets: listado, detalle, borrado e importación PDF múltiple
            // -----------------------------------------------------------------
            route("/tickets") {
                get {
                    val user = requireUser(call) ?: return@get
                    val monthFilter = call.request.queryParameters["month"]?.trim()?.takeIf { it.isNotEmpty() }
                    val query = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() }

                    var tickets = db.getAllTickets(user.id)
                    if (monthFilter != null) {
                        tickets = tickets.filter { it.dateTimeIso.startsWith(monthFilter) }
                    }
                    if (query != null) {
                        tickets = tickets.filter { t ->
                            t.storeAddress.contains(query, ignoreCase = true) ||
                                t.ticketNumber.contains(query, ignoreCase = true) ||
                                t.items.any { it.description.contains(query, ignoreCase = true) }
                        }
                    }

                    call.respond(tickets.map { it.toSummaryDto() })
                }

                get("/{id}") {
                    val user = requireUser(call) ?: return@get
                    val ticketId = call.parameters["id"]?.toLongOrNull()
                    if (ticketId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de ticket inválido."))
                        return@get
                    }
                    val ticket = db.getTicketById(user.id, ticketId)
                    if (ticket == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Ticket no encontrado."))
                        return@get
                    }
                    call.respond(ticket.toDetailDto())
                }

                delete("/{id}") {
                    val user = requireUser(call) ?: return@delete
                    val ticketId = call.parameters["id"]?.toLongOrNull()
                    if (ticketId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de ticket inválido."))
                        return@delete
                    }
                    if (db.deleteTicket(user.id, ticketId)) {
                        call.respond(HttpStatusCode.OK, MessageResponseDto("Ticket eliminado."))
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Ticket no encontrado."))
                    }
                }

                post("/import") {
                    val user = requireUser(call) ?: return@post
                    val results = mutableListOf<ImportItemResult>()
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        try {
                            if (part is PartData.FileItem) {
                                val fileName = part.originalFileName?.takeIf { it.isNotBlank() } ?: "ticket.pdf"
                                val bytes = part.provider().toByteArray()
                                results.add(
                                    importService.importPdfBytes(
                                        userId = user.id,
                                        pdfBytes = bytes,
                                        filename = fileName,
                                        messageId = null
                                    )
                                )
                            }
                        } finally {
                            part.dispose()
                        }
                    }

                    if (results.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("No se recibió ningún archivo PDF."))
                        return@post
                    }

                    val summary = importService.summarize(results)
                    call.respond(
                        BatchImportResponseDto(
                            total = summary.total,
                            inserted = summary.inserted,
                            duplicates = summary.duplicates,
                            failed = summary.failed,
                            results = summary.results.map {
                                ImportItemResultDto(
                                    filename = it.filename,
                                    status = it.status,
                                    ticketNumber = it.ticketNumber,
                                    totalAmount = it.totalAmount,
                                    error = it.error
                                )
                            }
                        )
                    )
                }
            }

            // -----------------------------------------------------------------
            // Estadísticas mensuales / semanales y ranking de productos
            // -----------------------------------------------------------------
            get("/stats") {
                val user = requireUser(call) ?: return@get
                val tickets = db.getAllTickets(user.id)
                val summary = StatsCalculator.calculate(tickets)
                val oldestDate = tickets.lastOrNull()?.dateDisplay?.substringBefore(" ")
                val newestDate = tickets.firstOrNull()?.dateDisplay?.substringBefore(" ")

                call.respond(
                    StatsResponseDto(
                        totalSpent = summary.totalSpent,
                        totalVat = summary.totalVat,
                        ticketCount = summary.ticketCount,
                        averageTicket = summary.averageTicket,
                        oldestDate = oldestDate,
                        newestDate = newestDate,
                        monthlyStats = summary.monthlyStats.map {
                            PeriodSpendStatDto(
                                periodKey = it.periodKey,
                                periodLabel = it.periodLabel,
                                totalAmount = it.totalAmount,
                                totalVatAmount = it.totalVatAmount,
                                ticketCount = it.ticketCount
                            )
                        },
                        weeklyStats = summary.weeklyStats.map {
                            PeriodSpendStatDto(
                                periodKey = it.periodKey,
                                periodLabel = it.periodLabel,
                                totalAmount = it.totalAmount,
                                totalVatAmount = it.totalVatAmount,
                                ticketCount = it.ticketCount
                            )
                        }
                    )
                )
            }

            // Evolución del precio de un producto (?key=<productKey>)
            get("/products/history") {
                val user = requireUser(call) ?: return@get
                val key = call.request.queryParameters["key"]?.trim().orEmpty()
                if (key.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Falta el parámetro 'key'."))
                    return@get
                }
                val tickets = db.getAllTickets(user.id)
                val points = StatsCalculator.priceHistory(tickets, key)
                if (points.isEmpty()) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No hay compras de ese producto."))
                    return@get
                }
                val normalized = StatsCalculator.normalizeProductKey(key)
                val displayName = tickets.asSequence()
                    .flatMap { it.items.asSequence() }
                    .firstOrNull { StatsCalculator.normalizeProductKey(it.description) == normalized }
                    ?.description?.trim()?.replace(Regex("\\s+"), " ")
                    ?: key
                call.respond(
                    ProductPriceHistoryDto(
                        productKey = normalized,
                        displayName = displayName,
                        unit = points.firstNotNullOfOrNull { it.unit },
                        points = points.map { PricePointDto(it.ticketId, it.dateTimeIso, it.unitPrice, it.quantity) }
                    )
                )
            }

            get("/products") {
                val user = requireUser(call) ?: return@get
                val sort = call.request.queryParameters["sort"]?.trim()?.lowercase() ?: "frequency"
                val query = call.request.queryParameters["q"]?.trim().orEmpty()

                val tickets = db.getAllTickets(user.id)
                val summary = StatsCalculator.calculate(tickets)

                val filtered = if (query.isEmpty()) {
                    summary.productStats
                } else {
                    summary.productStats.filter { it.displayName.contains(query, ignoreCase = true) }
                }

                val sorted = if (sort == "amount") {
                    filtered.sortedByDescending { it.totalAmount }
                } else {
                    filtered.sortedWith(
                        compareByDescending<io.github.neoruben.mercadonastats.stats.ProductSpendStat> { it.purchaseCount }
                            .thenByDescending { it.totalQuantity }
                            .thenByDescending { it.totalAmount }
                    )
                }

                call.respond(
                    ProductsResponseDto(
                        total = sorted.size,
                        sort = if (sort == "amount") "amount" else "frequency",
                        products = sorted.map {
                            ProductSpendStatDto(
                                productKey = it.productKey,
                                displayName = it.displayName,
                                unit = it.unit,
                                totalQuantity = it.totalQuantity,
                                purchaseCount = it.purchaseCount,
                                totalAmount = it.totalAmount
                            )
                        }
                    )
                )
            }

            // -----------------------------------------------------------------
            // Sincronización: estado, IMAP, Inbox y Gmail OAuth opcional
            // -----------------------------------------------------------------
            route("/sync") {
                get("/status") {
                    val user = requireUser(call) ?: return@get
                    val states = db.getSyncStates(user.id).map {
                        SyncStateDto(it.source, it.lastRunMs, it.status, it.message)
                    }
                    call.respond(
                        SyncStatusResponseDto(
                            imapConfigured = imapSyncService.isConfigured(),
                            imapAllowed = imapSyncService.canUserTriggerSync(user.username, user.isAdmin),
                            gmailOAuthConfigured = gmailOAuthService.isConfigured(),
                            gmailConnected = gmailOAuthService.isConnected(user.id),
                            inboxEnabled = config.inboxPollSeconds > 0,
                            states = states,
                            imapProgress = imapSyncService.progress
                                ?.takeIf { imapSyncService.canUserTriggerSync(user.username, user.isAdmin) }
                                ?.let { ImapProgressDto(it.phase, it.done, it.total, it.inserted, it.duplicates, it.failed) }
                        )
                    )
                }

                post("/imap") {
                    val user = requireUser(call) ?: return@post
                    if (!imapSyncService.isConfigured()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("IMAP no está configurado en el servidor."))
                        return@post
                    }
                    if (!imapSyncService.canUserTriggerSync(user.username, user.isAdmin)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("Solo el administrador o el usuario configurado en IMAP_TARGET_USER puede lanzar la sincronización IMAP.")
                        )
                        return@post
                    }
                    // La sincronización puede tardar minutos: se lanza en segundo plano y la web
                    // sigue el progreso con GET /api/sync/status.
                    if (imapSyncService.isSyncing()) {
                        call.respond(HttpStatusCode.Accepted, SyncStartedDto(false, "Ya hay una sincronización en curso."))
                        return@post
                    }
                    val app = call.application
                    app.launch(Dispatchers.IO) {
                        runCatching { imapSyncService.syncNow() }
                            .onFailure { app.log.warn("Sincronización IMAP manual: {}", it.message) }
                    }
                    call.respond(HttpStatusCode.Accepted, SyncStartedDto(true, "Sincronización iniciada."))
                }

                post("/inbox") {
                    // El escaneo recorre las carpetas de todos los usuarios y la respuesta
                    // incluye sus tickets, así que solo lo puede lanzar un administrador.
                    val user = requireUser(call) ?: return@post
                    if (!user.isAdmin) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("Solo un administrador puede lanzar el escaneo de la carpeta inbox.")
                        )
                        return@post
                    }
                    val summary = inboxWatcherService.scanOnce()
                    call.respond(
                        BatchImportResponseDto(
                            total = summary.total,
                            inserted = summary.inserted,
                            duplicates = summary.duplicates,
                            failed = summary.failed,
                            results = summary.results.map {
                                ImportItemResultDto(it.filename, it.status, it.ticketNumber, it.totalAmount, it.error)
                            }
                        )
                    )
                }

                post("/gmail") {
                    val user = requireUser(call) ?: return@post
                    if (!gmailOAuthService.isConfigured()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Gmail OAuth no está configurado."))
                        return@post
                    }
                    try {
                        val result = gmailOAuthService.syncForUser(user.id)
                        call.respond(HttpStatusCode.OK, result.toDto())
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "Error al sincronizar Gmail."))
                    }
                }
            }

            route("/gmail") {
                get("/oauth/start") {
                    val user = requireUser(call) ?: return@get
                    if (!gmailOAuthService.isConfigured()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Gmail OAuth no está configurado."))
                        return@get
                    }
                    val authUrl = gmailOAuthService.createAuthorizationUrl(user.id)
                    call.respondRedirect(authUrl)
                }

                get("/oauth/callback") {
                    val error = call.request.queryParameters["error"]
                    if (!error.isNullOrBlank()) {
                        val encoded = URLEncoder.encode(error, "UTF-8")
                        call.respondRedirect("/?gmail_error=$encoded")
                        return@get
                    }
                    val state = call.request.queryParameters["state"]
                    val code = call.request.queryParameters["code"]
                    if (state.isNullOrBlank() || code.isNullOrBlank()) {
                        call.respondRedirect("/?gmail_error=missing_code")
                        return@get
                    }
                    try {
                        gmailOAuthService.handleCallback(state, code)
                        call.respondRedirect("/?gmail=connected")
                    } catch (e: Exception) {
                        val encoded = URLEncoder.encode(e.message ?: "oauth_failed", "UTF-8")
                        call.respondRedirect("/?gmail_error=$encoded")
                    }
                }

                post("/disconnect") {
                    val user = requireUser(call) ?: return@post
                    gmailOAuthService.disconnect(user.id)
                    call.respond(HttpStatusCode.OK, MessageResponseDto("Cuenta de Gmail desconectada."))
                }
            }
        }

        // Frontend estático (HTML + CSS + JS sin build)
        staticResources("/", "static")
    }
}

private fun UserRecord.toDto() = UserDto(
    id = id,
    username = username,
    displayName = displayName,
    email = email,
    isAdmin = isAdmin,
    authSource = authSource
)

private fun StoredTicket.toSummaryDto() = TicketSummaryDto(
    id = id,
    ticketNumber = ticketNumber,
    storeName = storeName,
    storeAddress = storeAddress,
    dateTimeIso = dateTimeIso,
    dateDisplay = dateDisplay,
    totalAmount = totalAmount,
    totalVatAmount = totalVatAmount,
    itemCount = items.size
)

private fun StoredTicket.toDetailDto() = TicketDetailDto(
    id = id,
    ticketNumber = ticketNumber,
    pdfSha256 = pdfSha256,
    gmailMessageId = gmailMessageId,
    storeName = storeName,
    storeAddress = storeAddress,
    dateTimeIso = dateTimeIso,
    dateDisplay = dateDisplay,
    totalAmount = totalAmount,
    totalVatAmount = totalVatAmount,
    items = items.map {
        TicketItemDto(
            quantity = it.quantity,
            unit = it.unit,
            description = it.description,
            unitPrice = it.unitPrice,
            totalPrice = it.totalPrice
        )
    },
    vatBreakdown = vatBreakdown.map {
        TicketVatDto(
            vatRatePercent = it.vatRatePercent,
            baseAmount = it.baseAmount,
            vatAmount = it.vatAmount,
            totalAmount = it.totalAmount
        )
    }
)

private fun SyncRunResult.toDto() = SyncRunResponseDto(
    source = source,
    totalMatched = totalMatched,
    processedNew = processedNew,
    inserted = inserted,
    duplicates = duplicates,
    failed = failed,
    message = message
)
