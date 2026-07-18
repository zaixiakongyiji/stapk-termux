package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ChatBackupController(
    private val paths: NativeAdapterPaths,
    private val exportStore: ExportStore = ExportStore(paths.userDataDir.resolve("exports")),
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnosticLogger: DiagnosticLogger = DiagnosticLogger(paths.logsDir)
) {
    private val gson = Gson()

    @Synchronized
    fun backupIfChanged(chatFile: File, newContent: String) {
        if (!chatFile.isFile) return
        runCatching {
            val oldContent = chatFile.readText()
            if (oldContent == newContent) return
            paths.chatBackupsDir.mkdirs()
            val timestamp = BACKUP_TIME_FORMAT.format(Instant.ofEpochMilli(clock()))
            val backup = paths.chatBackupsDir.resolve("${chatFile.nameWithoutExtension}_$timestamp.jsonl")
            require(!backup.exists()) { "Backup already exists" }
            writeAtomically(backup, oldContent)
            prune(chatFile.nameWithoutExtension)
        }.onFailure {
            recordDiagnostic(chatFile)
        }
    }

    fun getBackups(): HttpResponse {
        val backups = JsonArray()
        listedBackupFiles().forEach { file ->
            val chatItems = runCatching {
                file.readLines().count { line ->
                    line.isNotBlank() && JsonParser.parseString(line).asJsonObject.has("mes")
                }
            }.getOrDefault(0)
            backups.add(JsonObject().apply {
                addProperty("name", file.name)
                addProperty("size", file.length())
                addProperty("date", file.lastModified())
                addProperty("file_name", file.name)
                addProperty("file_size", "${file.length()} B")
                addProperty("last_mes", file.lastModified())
                addProperty("chat_items", chatItems)
            })
        }
        return HttpResponse.json(200, gson.toJson(backups))
    }

    fun downloadBackup(body: String): HttpResponse {
        val file = requestedBackup(body) ?: return invalidBackupResponse()
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return HttpResponse.json(404, """{"error":"backup_not_found"}""")
        }
        return try {
            val ticket = exportStore.create(file.name, "application/x-ndjson") { target ->
                Files.copy(file.toPath(), target.toPath(), REPLACE_EXISTING)
            }
            HttpResponse.file(ticket.file, ticket.fileName, ticket.token, ticket.mimeType)
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"backup_download_failed"}""")
        }
    }

    @Synchronized
    fun deleteBackup(body: String): HttpResponse {
        val file = requestedBackup(body) ?: return invalidBackupResponse()
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return HttpResponse.json(404, """{"error":"backup_not_found"}""")
        }
        return if (file.delete()) {
            HttpResponse.json(200, """{"ok":true}""")
        } else {
            HttpResponse.json(500, """{"error":"backup_delete_failed"}""")
        }
    }

    fun cleanupExports() = exportStore.cleanupExpired()

    private fun prune(chatStem: String) {
        val pattern = Regex("^${Regex.escape(chatStem)}_\\d{8}-\\d{6}-\\d{3}\\.jsonl$")
        paths.chatBackupsDir.listFiles { file -> file.isFile && pattern.matches(file.name) }
            .orEmpty()
            .sortedByDescending { it.name }
            .drop(MAX_BACKUPS_PER_CHAT)
            .forEach { it.delete() }
    }

    private fun listedBackupFiles(): List<File> =
        paths.chatBackupsDir.listFiles { file ->
            file.isFile && !Files.isSymbolicLink(file.toPath()) && BACKUP_FILE_NAME.matches(file.name)
        }.orEmpty().sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

    private fun requestedBackup(body: String): File? {
        val request = runCatching { JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject }.getOrNull()
            ?: return null
        val name = request.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (!BACKUP_FILE_NAME.matches(name)) return null
        if (runCatching { SafePath.fileName(name) }.getOrNull() != name) return null
        return runCatching { SafePath.child(paths.chatBackupsDir, name) }.getOrNull()
    }

    private fun invalidBackupResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_backup"}""")

    private fun writeAtomically(target: File, content: String) {
        val temporary = File.createTempFile("stapk-chat-backup-", ".tmp", target.parentFile)
        try {
            temporary.writeText(content)
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath())
            }
        } finally {
            temporary.delete()
        }
    }

    private fun recordDiagnostic(chatFile: File) {
        runCatching {
            diagnosticLogger.event(
                DiagnosticArea.STORAGE,
                "chat_backup_failed",
                mapOf("file" to chatFile.name)
            )
        }
    }

    private companion object {
        val BACKUP_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
        val BACKUP_FILE_NAME = Regex("^.+_\\d{8}-\\d{6}-\\d{3}\\.jsonl$")
        const val MAX_BACKUPS_PER_CHAT = 50
    }
}
