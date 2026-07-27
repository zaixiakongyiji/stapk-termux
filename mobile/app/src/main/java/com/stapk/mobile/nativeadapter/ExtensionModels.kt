package com.stapk.mobile.nativeadapter

import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.io.Closeable

data class ExtensionRecord(
    val folderName: String,
    val repositoryUrl: String,
    val owner: String,
    val repository: String,
    val branch: String,
    val commitSha: String,
    val installedAt: Long,
    val updatedAt: Long
) {
    init {
        require(SAFE_FOLDER_NAME.matches(folderName)) { "Invalid extension folder name" }
        require(repositoryUrl.isNotBlank()) { "Repository URL is required" }
        require(owner.isNotBlank() && repository.isNotBlank()) { "Repository identity is required" }
        require(branch.isNotBlank() && commitSha.isNotBlank()) { "Repository version is required" }
        require(installedAt >= 0 && updatedAt >= 0) { "Extension timestamps must be non-negative" }
    }

    private companion object {
        val SAFE_FOLDER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
    }
}

data class GitHubRepository(
    val owner: String,
    val repository: String,
    val canonicalUrl: String
)

data class ExtensionRelease(
    val repository: GitHubRepository,
    val branch: String,
    val commitSha: String,
    val archive: ResponseBody
)

enum class ExtensionOperation {
    INSTALL,
    UPDATE,
    DELETE
}

enum class ExtensionTransactionPhase {
    PREPARED,
    FILES_ACTIVATED,
    REGISTRY_COMMITTED
}

data class ExtensionTransaction(
    val schemaVersion: Int = 1,
    val transactionId: String,
    val operation: ExtensionOperation,
    val phase: ExtensionTransactionPhase,
    val folderName: String,
    val oldRecord: ExtensionRecord?,
    val newRecord: ExtensionRecord?,
    val stagingName: String?,
    val backupName: String?,
    val trashName: String?
)

class PreparedExtension(
    val record: ExtensionRecord,
    val stagingDirectory: File
) : Closeable {
    private var closed = false

    override fun close() {
        if (closed) return
        if (stagingDirectory.exists() && !stagingDirectory.deleteRecursively()) {
            throw IOException("Unable to remove prepared extension staging directory")
        }
        closed = true
    }
}

fun interface ExtensionSource {
    fun resolve(url: String, branch: String?): ExtensionRelease
}

enum class ExtensionSourcePhase(val diagnosticValue: String) {
    UNKNOWN("unknown"),
    METADATA("metadata"),
    COMMIT("commit"),
    ARCHIVE_REDIRECT("archive_redirect"),
    ARCHIVE_DOWNLOAD("archive_download"),
    ARCHIVE_READ("archive_read")
}

open class ExtensionSourceException(
    message: String,
    cause: Throwable? = null,
    val phase: ExtensionSourcePhase = ExtensionSourcePhase.UNKNOWN
) : IOException(message, cause)

class ExtensionHttpException(
    val statusCode: Int,
    phase: ExtensionSourcePhase = ExtensionSourcePhase.UNKNOWN
) : ExtensionSourceException("GitHub request failed with HTTP $statusCode", phase = phase)

class ExtensionRedirectException(
    message: String,
    phase: ExtensionSourcePhase = ExtensionSourcePhase.UNKNOWN
) : ExtensionSourceException(message, phase = phase)

class ExtensionDownloadTooLargeException(
    phase: ExtensionSourcePhase = ExtensionSourcePhase.UNKNOWN
) : ExtensionSourceException("Extension archive exceeds download limit", phase = phase)

class ExtensionArchiveTransportException(cause: IOException) : ExtensionSourceException(
    "Unable to read extension archive",
    cause,
    ExtensionSourcePhase.ARCHIVE_READ
)

class InvalidExtensionArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)
