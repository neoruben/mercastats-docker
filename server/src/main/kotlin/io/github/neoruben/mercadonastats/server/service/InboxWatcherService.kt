package io.github.neoruben.mercadonastats.server.service

import io.github.neoruben.mercadonastats.server.config.ServerConfig
import io.github.neoruben.mercadonastats.server.db.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vigila periódicamente la carpeta `/data/inbox` (o la configurada en `INBOX_DIR`).
 * - Los PDFs colocados directamente en `/data/inbox` (raíz) se asignan a `INBOX_DEFAULT_USER`.
 * - En entornos multiusuario, los PDFs en `/data/inbox/<username>` se asignan al usuario `<username>`.
 * Tras procesarse, los archivos se mueven a `processed/` o `failed/` para no reprocesarlos.
 */
class InboxWatcherService(
    private val config: ServerConfig,
    private val db: Database,
    private val importService: TicketImportService
) {
    private val logger = LoggerFactory.getLogger(InboxWatcherService::class.java)
    private val running = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (config.inboxPollSeconds <= 0) {
            logger.info("Vigilancia de carpeta inbox desactivada (INBOX_POLL_SECONDS=0).")
            return
        }

        val rootDir = File(config.inboxDir)
        runCatching { rootDir.mkdirs() }
        logger.info(
            "Vigilando carpeta inbox '{}' cada {}s (usuario por defecto: '{}').",
            rootDir.absolutePath,
            config.inboxPollSeconds,
            config.inboxDefaultUser
        )

        scope.launch {
            while (isActive) {
                try {
                    scanOnce()
                } catch (e: Exception) {
                    logger.error("Error escaneando carpeta inbox: {}", e.message)
                }
                delay(config.inboxPollSeconds * 1000L)
            }
        }
    }

    fun scanOnce(): BatchImportSummary {
        if (!running.compareAndSet(false, true)) {
            return BatchImportSummary(0, 0, 0, 0, emptyList())
        }
        try {
            val rootDir = File(config.inboxDir)
            if (!rootDir.exists() || !rootDir.isDirectory) {
                return BatchImportSummary(0, 0, 0, 0, emptyList())
            }

            val allResults = mutableListOf<ImportItemResult>()

            // 1) PDFs directos en /data/inbox -> usuario por defecto
            // Sin usuario destino no se importa nada: nunca se asigna a "el primer usuario".
            val defaultUser = db.findUserByUsername(config.inboxDefaultUser)
            if (defaultUser == null) {
                if (listPendingPdfs(rootDir).isNotEmpty()) {
                    logger.warn(
                        "Hay PDFs en '{}' pero INBOX_DEFAULT_USER='{}' no existe; se dejan sin importar.",
                        rootDir.absolutePath,
                        config.inboxDefaultUser
                    )
                }
            } else {
                val rootPdfs = listPendingPdfs(rootDir)
                if (rootPdfs.isNotEmpty()) {
                    val results = processDirectoryFiles(defaultUser.id, rootDir, rootPdfs)
                    allResults.addAll(results)
                    val summary = importService.summarize(results)
                    db.recordSyncState(
                        userId = defaultUser.id,
                        source = "inbox",
                        status = if (summary.failed > 0 && summary.inserted == 0) "error" else "ok",
                        message = "Inbox: ${summary.inserted} nuevos, ${summary.duplicates} duplicados, ${summary.failed} errores."
                    )
                }
            }

            // 2) Subcarpetas por usuario /data/inbox/<username>/*.pdf
            val subDirs = rootDir.listFiles { file ->
                !Files.isSymbolicLink(file.toPath()) &&
                    file.isDirectory && file.name != "processed" && file.name != "failed" && !file.name.startsWith(".")
            }.orEmpty()

            for (subDir in subDirs) {
                val user = db.findUserByUsername(subDir.name) ?: continue
                val pdfs = listPendingPdfs(subDir)
                if (pdfs.isEmpty()) continue
                val results = processDirectoryFiles(user.id, subDir, pdfs)
                allResults.addAll(results)
                val summary = importService.summarize(results)
                db.recordSyncState(
                    userId = user.id,
                    source = "inbox",
                    status = if (summary.failed > 0 && summary.inserted == 0) "error" else "ok",
                    message = "Inbox (${subDir.name}): ${summary.inserted} nuevos, ${summary.duplicates} duplicados, ${summary.failed} errores."
                )
            }

            return importService.summarize(allResults)
        } finally {
            running.set(false)
        }
    }

    private fun listPendingPdfs(dir: File): List<File> {
        val now = System.currentTimeMillis()
        return dir.listFiles { file ->
            // Ignorar enlaces simbólicos: podrían apuntar fuera de la carpeta inbox.
            !Files.isSymbolicLink(file.toPath()) &&
                file.isFile &&
                file.name.endsWith(".pdf", ignoreCase = true) &&
                !file.name.startsWith(".") &&
                // Esperar al menos 2 segundos desde la última modificación para evitar archivos a medio copiar
                (now - file.lastModified()) >= 2_000L
        }?.sortedBy { it.lastModified() }.orEmpty()
    }

    private fun processDirectoryFiles(userId: Long, baseDir: File, pdfs: List<File>): List<ImportItemResult> {
        val processedDir = File(baseDir, "processed").apply { mkdirs() }
        val failedDir = File(baseDir, "failed").apply { mkdirs() }
        if (Files.isSymbolicLink(processedDir.toPath()) || Files.isSymbolicLink(failedDir.toPath())) {
            logger.warn("'processed' o 'failed' en '{}' es un enlace simbólico; no se procesa.", baseDir.absolutePath)
            return emptyList()
        }
        val results = mutableListOf<ImportItemResult>()

        for (file in pdfs) {
            val bytes = runCatching { file.readBytes() }.getOrElse { err ->
                logger.warn("No se pudo leer '{}': {}", file.absolutePath, err.message)
                continue
            }
            val result = importService.importPdfBytes(userId, bytes, file.name, messageId = null)
            results.add(result)

            val targetDir = if (result.status == "error") failedDir else processedDir
            val moved = moveFileSafely(file, targetDir)
            if (result.status == "error" && moved != null) {
                runCatching {
                    File(failedDir, "${moved.name}.error.txt").writeText(result.error ?: "Error desconocido")
                }
            }
            logger.info("Inbox '{}' -> {} (userId={})", file.name, result.status, userId)
        }

        return results
    }

    private fun moveFileSafely(source: File, targetDir: File): File? {
        var target = File(targetDir, source.name)
        if (target.exists()) {
            val base = source.nameWithoutExtension
            val ext = source.extension
            target = File(targetDir, "${base}_${System.currentTimeMillis()}.$ext")
        }
        return try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target
        } catch (e: Exception) {
            logger.warn("No se pudo mover '{}' a '{}': {}", source.absolutePath, target.absolutePath, e.message)
            null
        }
    }
}
