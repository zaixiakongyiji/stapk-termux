package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import java.io.File
import java.net.URI

enum class DiagnosticArea {
    HTTP,
    STORAGE,
    PROVIDER,
    RESTORE
}

class DiagnosticLogger(
    private val logsDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val backupCount: Int = DEFAULT_BACKUP_COUNT
) {
    init {
        require(maxBytes > 0L) { "Diagnostic log size must be positive" }
        require(backupCount >= 0) { "Diagnostic backup count must not be negative" }
    }

    @Synchronized
    fun event(area: DiagnosticArea, code: String, fields: Map<String, String> = emptyMap()) {
        require(CODE.matches(code)) { "Invalid diagnostic code" }
        check(logsDir.exists() || logsDir.mkdirs()) { "Unable to create diagnostic log directory" }
        val line = JsonObject().apply {
            addProperty("timestamp", clock())
            addProperty("area", area.name)
            addProperty("code", code)
            add("fields", JsonObject().apply {
                fields.toSortedMap().forEach { (key, value) ->
                    sanitizeField(code, key, value)?.let { addProperty(key, it) }
                }
            })
        }.toString() + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        val active = activeFile()
        if (active.isFile && active.length() + bytes.size > maxBytes) rotate()
        active.appendBytes(bytes)
    }

    internal fun logFiles(): List<File> = buildList {
        activeFile().takeIf(File::isFile)?.let(::add)
        for (index in 1..backupCount) {
            backupFile(index).takeIf(File::isFile)?.let(::add)
        }
    }

    private fun sanitizeField(code: String, key: String, value: String): String? = when (key) {
        "method" -> value.takeIf { METHOD.matches(it) }
        "path" -> sanitizePath(value)
        "status" -> value.toIntOrNull()?.takeIf { it in 100..599 }?.toString()
        "host" -> value.lowercase().takeIf { HOST.matches(it) }
        "durationMs" -> value.toLongOrNull()?.takeIf { it in 0..MAX_DURATION_MS }?.toString()
        "stream" -> value.toBooleanStrictOrNull()?.toString()
        "terminal" -> value.takeIf { it in PROVIDER_STREAM_TERMINALS }
        "file" -> value.takeIf { ExportMetadata.isFileName(it) }
        "errorClass" -> value.takeIf { ERROR_CLASS.matches(it) }
        "sha256" -> value.lowercase().takeIf { SHA256.matches(it) }
        "operation" -> value.takeIf { it in EXTENSION_OPERATIONS }
        "phase" -> value.takeIf {
            it in if (code == "extension_source_failed") {
                EXTENSION_SOURCE_PHASES
            } else {
                EXTENSION_TRANSACTION_PHASES
            }
        }
        "folder" -> value.takeIf { EXTENSION_FOLDER.matches(it) }
        "result" -> value.takeIf { CODE.matches(it) }
        "recoveredCount", "quarantinedCount" ->
            value.toIntOrNull()?.takeIf { it >= 0 }?.toString()
        else -> null
    }

    private fun sanitizePath(value: String): String? {
        val path = runCatching { URI(value).path }.getOrNull() ?: return null
        return path.takeIf { it.startsWith('/') && it.length <= MAX_PATH_LENGTH && it.none(Char::isISOControl) }
    }

    private fun rotate() {
        if (backupCount == 0) {
            activeFile().delete()
            return
        }
        backupFile(backupCount).delete()
        for (index in backupCount - 1 downTo 1) {
            val source = backupFile(index)
            if (source.isFile) check(source.renameTo(backupFile(index + 1))) { "Unable to rotate diagnostic log" }
        }
        val active = activeFile()
        if (active.isFile) check(active.renameTo(backupFile(1))) { "Unable to rotate diagnostic log" }
    }

    private fun activeFile(): File = logsDir.resolve("diagnostics.jsonl")

    private fun backupFile(index: Int): File = logsDir.resolve("diagnostics.$index.jsonl")

    private companion object {
        const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_BACKUP_COUNT = 3
        const val MAX_PATH_LENGTH = 256
        const val MAX_DURATION_MS = 7L * 24L * 60L * 60L * 1000L
        val CODE = Regex("[a-z][a-z0-9_.-]{0,79}")
        val METHOD = Regex("[A-Z]{3,10}")
        val HOST = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        val ERROR_CLASS = Regex("[A-Za-z_$][A-Za-z0-9_$.]{0,159}")
        val SHA256 = Regex("[a-f0-9]{64}")
        val PROVIDER_STREAM_TERMINALS = setOf("completed", "canceled", "read_error")
        val EXTENSION_OPERATIONS = setOf("install", "update", "delete", "version")
        val EXTENSION_TRANSACTION_PHASES = setOf(
            "prepared",
            "files_activated",
            "registry_committed"
        )
        val EXTENSION_SOURCE_PHASES = setOf(
            "unknown",
            "metadata",
            "commit",
            "archive_redirect",
            "archive_download",
            "archive_read"
        )
        val EXTENSION_FOLDER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
    }
}
