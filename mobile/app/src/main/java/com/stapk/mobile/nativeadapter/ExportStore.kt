package com.stapk.mobile.nativeadapter

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class ExportTicket(
    val token: String,
    val file: File,
    val fileName: String,
    val mimeType: String,
    val expiresAt: Long
)

class ExportStore(
    private val exportsDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxActiveTickets: Int = DEFAULT_MAX_ACTIVE_TICKETS,
    private val maxActiveBytes: Long = DEFAULT_MAX_ACTIVE_BYTES
) {
    private val random = SecureRandom()
    private val exports = ConcurrentHashMap<String, ExportTicket>()
    private val consumed = ConcurrentHashMap<String, ExportTicket>()

    @Synchronized
    fun create(
        fileName: String,
        mimeType: String,
        expectedBytes: Long? = null,
        writer: (File) -> Unit
    ): ExportTicket {
        require(expectedBytes == null || expectedBytes >= 0L) { "Expected bytes must not be negative" }
        cleanupExpired()
        ensureQuota(expectedBytes ?: 0L)
        check(exportsDir.exists() || exportsDir.mkdirs()) { "Unable to create exports directory" }
        require(exportsDir.isDirectory) { "Export path must be a directory" }
        val tokenBytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(tokenBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val safeFileName = SafePath.fileName(fileName)
        val exportFile = SafePath.child(exportsDir, "$token-$safeFileName")
        val temporary = File.createTempFile("stapk-export-", ".tmp", exportsDir)
        val ticket = ExportTicket(
            token = token,
            file = exportFile,
            fileName = safeFileName,
            mimeType = mimeType,
            expiresAt = clock() + TOKEN_TTL_MILLIS
        )
        try {
            // 在文件出现前登记，避免并发清理把尚未完成的导出视为 orphan。
            exports[token] = ticket
            writer(temporary)
            ensureQuota(temporary.length(), excludedToken = token)
            moveReplacing(temporary, exportFile)
            Files.setLastModifiedTime(exportFile.toPath(), FileTime.fromMillis(ticket.expiresAt))
            return ticket
        } catch (exception: Exception) {
            exports.remove(token, ticket)
            temporary.delete()
            exportFile.delete()
            throw exception
        }
    }

    fun consume(token: String): ExportTicket? {
        while (true) {
            val ticket = find(token) ?: return null
            if (!exports.remove(token, ticket)) continue
            consumed[token] = ticket
            return ticket
        }
    }

    fun release(token: String) {
        val ticket = consumed.remove(token) ?: exports.remove(token) ?: return
        ticket.file.delete()
    }

    fun find(token: String): ExportTicket? {
        val ticket = exports[token] ?: return null
        if (clock() < ticket.expiresAt && ticket.file.isFile) return ticket
        if (exports.remove(token, ticket)) ticket.file.delete()
        return null
    }

    @Synchronized
    fun cleanupExpired() {
        val now = clock()
        exports.entries.forEach { (token, ticket) ->
            if (now >= ticket.expiresAt && exports.remove(token, ticket)) {
                ticket.file.delete()
            }
        }
        consumed.entries.forEach { (token, ticket) ->
            if (now >= ticket.expiresAt && consumed.remove(token, ticket)) ticket.file.delete()
        }
        exportsDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    EXPORT_FILE_NAME.matches(file.name) &&
                    !isActive(file) &&
                    file.lastModified() <= now
            }
            ?.forEach { it.delete() }
    }

    private fun isActive(file: File): Boolean =
        exports.values.any { it.file == file } || consumed.values.any { it.file == file }

    private fun ensureQuota(incomingBytes: Long, excludedToken: String? = null) {
        val tickets = (exports.values + consumed.values).filter { it.token != excludedToken }
        if (tickets.size >= maxActiveTickets) throw ExportQuotaExceededException()
        val activeBytes = tickets.sumOf { ticket -> ticket.file.takeIf(File::isFile)?.length() ?: 0L }
        if (incomingBytes > maxActiveBytes - activeBytes) throw ExportQuotaExceededException()
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val TOKEN_TTL_MILLIS = 15L * 60L * 1000L
        const val DEFAULT_MAX_ACTIVE_TICKETS = 8
        const val DEFAULT_MAX_ACTIVE_BYTES = 64L * 1024L * 1024L
        val EXPORT_FILE_NAME = Regex("[A-Za-z0-9_-]{43}-.+")
    }
}

class ExportQuotaExceededException : IllegalStateException("Export staging quota exceeded")

object ExportMetadata {
    fun isToken(value: String): Boolean = TOKEN.matches(value)

    fun isFileName(value: String): Boolean =
        value.isNotBlank() &&
            value != "." &&
            value != ".." &&
            value.codePointCount(0, value.length) <= MAX_FILE_NAME_CODE_POINTS &&
            value.none { it == '/' || it == '\\' || it.isISOControl() }

    fun isMimeType(value: String): Boolean = value in MIME_EXTENSIONS

    fun isExport(fileName: String, mimeType: String): Boolean {
        if (!isFileName(fileName)) return false
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in MIME_EXTENSIONS[mimeType].orEmpty()
    }

    private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
    private val MIME_EXTENSIONS = mapOf(
        "application/json" to setOf("json"),
        "application/x-ndjson" to setOf("jsonl"),
        "application/zip" to setOf("zip"),
        "text/plain" to setOf("txt", "md", "markdown", "csv", "log"),
        "image/png" to setOf("png")
    )
    private const val MAX_FILE_NAME_CODE_POINTS = 120
}
