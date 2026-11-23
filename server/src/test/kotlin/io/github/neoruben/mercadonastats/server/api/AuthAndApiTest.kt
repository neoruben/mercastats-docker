package io.github.neoruben.mercadonastats.server.api

import io.github.neoruben.mercadonastats.server.config.AuthMode
import io.github.neoruben.mercadonastats.server.config.ServerConfig
import io.github.neoruben.mercadonastats.server.mercaStatsModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthAndApiTest {

    private fun testConfig(
        dbPath: String,
        authMode: AuthMode,
        trustedProxies: List<String> = listOf("127.0.0.1", "::1")
    ) = ServerConfig(
        host = "127.0.0.1",
        port = 8080,
        dataDir = File(dbPath).parent,
        dbPath = dbPath,
        inboxDir = File(File(dbPath).parent, "inbox").absolutePath,
        inboxPollSeconds = 0,
        inboxDefaultUser = "admin",
        authMode = authMode,
        adminUser = "admin",
        adminPassword = "TestPassword123!",
        allowRegistration = false,
        cookieSecure = false,
        sessionTtlDays = 7,
        proxyUserHeader = "X-authentik-username",
        proxyEmailHeader = "X-authentik-email",
        proxyNameHeader = "X-authentik-name",
        trustedProxies = trustedProxies,
        imap = null,
        gmailOAuth = null
    )

    @Test
    fun localAuthRequiresLoginAndIssuesSessionCookie() = testApplication {
        val dbFile = File.createTempFile("mercastats-local-test-", ".db")
        try {
            application {
                mercaStatsModule(testConfig(dbFile.absolutePath, AuthMode.LOCAL), startBackgroundWorkers = false)
            }

            // Health es público
            val health = client.get("/api/health")
            assertEquals(HttpStatusCode.OK, health.status)

            // Sin sesión -> 401
            val unauth = client.get("/api/tickets")
            assertEquals(HttpStatusCode.Unauthorized, unauth.status)

            // Login correcto -> devuelve cookie
            val login = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"TestPassword123!"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val setCookie = login.headers[HttpHeaders.SetCookie] ?: ""
            assertTrue(setCookie.contains("mercastats_session="))
            val cookiePair = setCookie.substringBefore(";")

            // Con cookie -> 200
            val stats = client.get("/api/stats") {
                header(HttpHeaders.Cookie, cookiePair)
            }
            assertEquals(HttpStatusCode.OK, stats.status)
            assertTrue(stats.bodyAsText().contains("\"ticketCount\":0"))
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun proxyAuthRejectsUntrustedProxyAndAcceptsTrustedHeader() = testApplication {
        val dbFileUntrusted = File.createTempFile("mercastats-proxy-untrusted-", ".db")
        try {
            application {
                mercaStatsModule(
                    testConfig(dbFileUntrusted.absolutePath, AuthMode.PROXY, trustedProxies = listOf("10.99.99.1")),
                    startBackgroundWorkers = false
                )
            }

            val forbidden = client.get("/api/tickets") {
                header("X-authentik-username", "spoofed-admin")
            }
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        } finally {
            dbFileUntrusted.delete()
        }
    }

    @Test
    fun proxyAuthProvisionsUserFromTrustedProxy() = testApplication {
        val dbFileTrusted = File.createTempFile("mercastats-proxy-trusted-", ".db")
        try {
            application {
                mercaStatsModule(
                    testConfig(dbFileTrusted.absolutePath, AuthMode.PROXY, trustedProxies = listOf("127.0.0.1", "::1")),
                    startBackgroundWorkers = false
                )
            }

            val resp = client.get("/api/auth/me") {
                header("X-authentik-username", "laura")
                header("X-authentik-name", "Laura")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("\"authenticated\":true"))
            assertTrue(body.contains("\"username\":\"laura\""))
        } finally {
            dbFileTrusted.delete()
        }
    }

    @Test
    fun proxyFirstUserIsNotAdminAndOnlyOneHeaderIsTrusted() = testApplication {
        val dbFile = File.createTempFile("mercastats-proxy-admin-", ".db")
        try {
            application {
                mercaStatsModule(testConfig(dbFile.absolutePath, AuthMode.PROXY), startBackgroundWorkers = false)
            }

            val first = client.get("/api/auth/me") { header("X-authentik-username", "primero") }
            assertEquals(HttpStatusCode.OK, first.status)
            assertTrue(first.bodyAsText().contains("\"isAdmin\":false"))

            val admin = client.get("/api/auth/me") { header("X-authentik-username", "admin") }
            assertTrue(admin.bodyAsText().contains("\"isAdmin\":true"))

            val remoteUserOnly = client.get("/api/tickets") { header("Remote-User", "admin") }
            assertEquals(HttpStatusCode.Unauthorized, remoteUserOnly.status)

            val inboxScan = client.post("/api/sync/inbox") { header("X-authentik-username", "primero") }
            assertEquals(HttpStatusCode.Forbidden, inboxScan.status)
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun proxyDoesNotTakeOverLocalAccount() = testApplication {
        val dbFile = File.createTempFile("mercastats-proxy-takeover-", ".db")
        try {
            // Primer arranque en modo local: crea 'admin' con authSource=local
            application {
                mercaStatsModule(testConfig(dbFile.absolutePath, AuthMode.LOCAL), startBackgroundWorkers = false)
            }
            client.get("/api/health")
        } finally {
            // la BD se reutiliza en la segunda aplicación
        }
        val second = io.ktor.server.testing.TestApplication {
            application {
                mercaStatsModule(testConfig(dbFile.absolutePath, AuthMode.PROXY), startBackgroundWorkers = false)
            }
        }
        try {
            val resp = second.client.get("/api/tickets") { header("X-authentik-username", "admin") }
            assertEquals(HttpStatusCode.Forbidden, resp.status)
        } finally {
            second.stop()
            dbFile.delete()
        }
    }

    @Test
    fun noneAuthAllowsDirectAccess() = testApplication {
        val dbFile = File.createTempFile("mercastats-none-test-", ".db")
        try {
            application {
                mercaStatsModule(testConfig(dbFile.absolutePath, AuthMode.NONE), startBackgroundWorkers = false)
            }

            val resp = client.get("/api/products")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(resp.bodyAsText().contains("\"total\":0"))
        } finally {
            dbFile.delete()
        }
    }
}
