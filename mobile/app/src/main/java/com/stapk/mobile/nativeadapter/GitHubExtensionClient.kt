package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.URI

class GitHubExtensionClient(
    client: OkHttpClient = OkHttpClient(),
    private val apiBaseUrl: HttpUrl = DEFAULT_API_BASE_URL,
    private val allowInsecureTestBaseUrl: Boolean = false,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS
) : ExtensionSource {
    private val transport = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init {
        require(apiBaseUrl.scheme == "https" || allowInsecureTestBaseUrl) { "GitHub API must use HTTPS" }
        require(maxRedirects >= 0) { "Redirect limit must be non-negative" }
    }

    override fun resolve(url: String, branch: String?): ExtensionRelease {
        val repository = parseRepositoryUrl(url)
        val metadata = getJson(endpoint("repos", repository.owner, repository.repository))
        val resolvedBranch = branch?.trim()?.takeIf(String::isNotEmpty)
            ?: metadata.get("default_branch")?.asString?.takeIf(String::isNotBlank)
            ?: throw ExtensionSourceException("GitHub repository has no default branch")
        require(resolvedBranch.length <= MAX_BRANCH_LENGTH && resolvedBranch.none(Char::isISOControl)) {
            "Invalid GitHub branch"
        }
        val commit = getJson(endpoint("repos", repository.owner, repository.repository, "commits", resolvedBranch))
            .get("sha")?.asString?.takeIf(String::isNotBlank)
            ?: throw ExtensionSourceException("GitHub commit response has no SHA")
        val archiveResponse = execute(
            Request.Builder()
                .url(endpoint("repos", repository.owner, repository.repository, "zipball", commit))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()
        )
        if (!archiveResponse.isSuccessful) {
            val statusCode = archiveResponse.code
            archiveResponse.close()
            throw ExtensionHttpException(statusCode)
        }
        val body = archiveResponse.body ?: run {
            archiveResponse.close()
            throw ExtensionSourceException("GitHub archive response is empty")
        }
        if (body.contentLength() > MAX_ARCHIVE_DOWNLOAD_BYTES) {
            body.close()
            throw ExtensionDownloadTooLargeException()
        }
        return ExtensionRelease(
            repository = repository,
            branch = resolvedBranch,
            commitSha = commit,
            archive = BoundedResponseBody(body, MAX_ARCHIVE_DOWNLOAD_BYTES)
        )
    }

    private fun getJson(url: HttpUrl) = execute(
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
    ).use { response ->
        if (!response.isSuccessful) throw ExtensionHttpException(response.code)
        val body = response.body ?: throw ExtensionSourceException("GitHub JSON response is empty")
        val bytes = readBounded(body, MAX_JSON_RESPONSE_BYTES)
        try {
            JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        } catch (exception: Exception) {
            throw ExtensionSourceException("GitHub returned invalid JSON", exception)
        }
    }

    private fun execute(initialRequest: Request): Response {
        var request = initialRequest
        var redirects = 0
        while (true) {
            val response = try {
                transport.newCall(request).execute()
            } catch (exception: IOException) {
                throw ExtensionSourceException("GitHub request failed", exception)
            }
            if (response.code !in REDIRECT_STATUS_CODES) return response
            if (redirects >= maxRedirects) {
                response.close()
                throw ExtensionRedirectException("GitHub redirect limit exceeded")
            }
            val location = response.header("Location")
            val nextUrl = location?.let(response.request.url::resolve)
            response.close()
            if (nextUrl == null) throw ExtensionRedirectException("GitHub redirect is missing a valid location")
            if (nextUrl.scheme != "https" && !allowInsecureTestBaseUrl) {
                throw ExtensionRedirectException("GitHub redirect must use HTTPS")
            }
            redirects += 1
            request = request.newBuilder().url(nextUrl).build()
        }
    }

    private fun endpoint(vararg segments: String): HttpUrl = apiBaseUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    private fun readBounded(body: ResponseBody, maxBytes: Long): ByteArray {
        if (body.contentLength() > maxBytes) throw ExtensionDownloadTooLargeException()
        val output = Buffer()
        var total = 0L
        while (true) {
            val count = body.source().read(output, minOf(8192L, maxBytes - total + 1L))
            if (count < 0) break
            total += count
            if (total > maxBytes) throw ExtensionDownloadTooLargeException()
        }
        return output.readByteArray()
    }

    companion object {
        fun parseRepositoryUrl(value: String): GitHubRepository {
            val uri = try {
                URI(value)
            } catch (exception: Exception) {
                throw IllegalArgumentException("Invalid GitHub repository URL", exception)
            }
            require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true)) {
                "Only HTTPS GitHub repositories are supported"
            }
            require(uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) {
                "GitHub repository URL contains unsupported components"
            }
            require(!uri.rawPath.contains('%')) { "Encoded GitHub paths are not supported" }
            val match = REPOSITORY_PATH.matchEntire(uri.path)
                ?: throw IllegalArgumentException("GitHub repository URL must contain owner and repository")
            val owner = match.groupValues[1]
            val repository = match.groupValues[2].removeSuffix(".git")
            require(SAFE_REPOSITORY_COMPONENT.matches(owner) && SAFE_REPOSITORY_COMPONENT.matches(repository)) {
                "Invalid GitHub repository identity"
            }
            return GitHubRepository(owner, repository, "https://github.com/$owner/$repository")
        }

        private val DEFAULT_API_BASE_URL = "https://api.github.com/".toHttpUrl()
        private val REPOSITORY_PATH = Regex("^/([^/]+)/([^/]+)$")
        private val SAFE_REPOSITORY_COMPONENT = Regex("[A-Za-z0-9_.-]+")
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private const val USER_AGENT = "stAPK-Mobile/0.3"
        private const val DEFAULT_MAX_REDIRECTS = 5
        private const val MAX_BRANCH_LENGTH = 255
        private const val MAX_JSON_RESPONSE_BYTES = 1024L * 1024L
        private const val MAX_ARCHIVE_DOWNLOAD_BYTES = 64L * 1024L * 1024L
    }

    private class BoundedResponseBody(
        private val delegate: ResponseBody,
        private val maxBytes: Long
    ) : ResponseBody() {
        private val boundedSource: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                private var total = 0L

                override fun read(sink: Buffer, byteCount: Long): Long {
                    val count = super.read(sink, minOf(byteCount, maxBytes - total + 1L))
                    if (count > 0) {
                        total += count
                        if (total > maxBytes) throw ExtensionDownloadTooLargeException()
                    }
                    return count
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = boundedSource
    }
}
