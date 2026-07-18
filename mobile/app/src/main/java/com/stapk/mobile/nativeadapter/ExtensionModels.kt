package com.stapk.mobile.nativeadapter

import okhttp3.ResponseBody
import java.io.File
import java.io.IOException

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

data class InstalledExtension(
    val record: ExtensionRecord,
    val directory: File
)

fun interface ExtensionSource {
    fun resolve(url: String, branch: String?): ExtensionRelease
}

open class ExtensionSourceException(message: String, cause: Throwable? = null) : IOException(message, cause)

class ExtensionHttpException(val statusCode: Int) :
    ExtensionSourceException("GitHub request failed with HTTP $statusCode")

class ExtensionRedirectException(message: String) : ExtensionSourceException(message)

class ExtensionDownloadTooLargeException : ExtensionSourceException("Extension archive exceeds download limit")

class InvalidExtensionArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)
