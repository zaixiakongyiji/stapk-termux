package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import com.google.gson.JsonObject
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
import java.util.concurrent.TimeUnit

class GitHubExtensionClient(
    client: OkHttpClient = extensionHttpClient(),
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
        val metadata = getJson(
            endpoint("repos", repository.owner, repository.repository),
            ExtensionSourcePhase.METADATA
        )
        val resolvedBranch = branch?.trim()?.takeIf(String::isNotEmpty)
            ?: metadata.requiredString(
                "default_branch",
                ExtensionSourcePhase.METADATA,
                "GitHub repository has no default branch"
            )
        require(resolvedBranch.length <= MAX_BRANCH_LENGTH && resolvedBranch.none(Char::isISOControl)) {
            "Invalid GitHub branch"
        }
        val commit = getJson(
            endpoint("repos", repository.owner, repository.repository, "commits", resolvedBranch),
            ExtensionSourcePhase.COMMIT
        )
            .requiredString(
                "sha",
                ExtensionSourcePhase.COMMIT,
                "GitHub commit response has no SHA"
            )
        val archiveResult = execute(
            Request.Builder()
                .url(endpoint("repos", repository.owner, repository.repository, "zipball", commit))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build(),
            ExtensionSourcePhase.ARCHIVE_REDIRECT
        )
        val archiveResponse = archiveResult.response
        if (!archiveResponse.isSuccessful) {
            val statusCode = archiveResponse.code
            archiveResponse.close()
            throw ExtensionHttpException(statusCode, archiveResult.phase)
        }
        val body = archiveResponse.body ?: run {
            archiveResponse.close()
            throw ExtensionSourceException(
                "GitHub archive response is empty",
                phase = archiveResult.phase
            )
        }
        if (body.contentLength() > MAX_ARCHIVE_DOWNLOAD_BYTES) {
            body.close()
            throw ExtensionDownloadTooLargeException(archiveResult.phase)
        }
        return ExtensionRelease(
            repository = repository,
            branch = resolvedBranch,
            commitSha = commit,
            archive = BoundedResponseBody(body, MAX_ARCHIVE_DOWNLOAD_BYTES)
        )
    }

    private fun getJson(url: HttpUrl, phase: ExtensionSourcePhase): JsonObject {
        val result = execute(
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build(),
            phase
        )
        return result.response.use { response ->
            if (!response.isSuccessful) throw ExtensionHttpException(response.code, result.phase)
            val body = response.body ?: throw ExtensionSourceException(
                "GitHub JSON response is empty",
                phase = result.phase
            )
            val bytes = try {
                readBounded(body, MAX_JSON_RESPONSE_BYTES, result.phase)
            } catch (exception: ExtensionSourceException) {
                throw exception
            } catch (exception: IOException) {
                throw ExtensionSourceException("GitHub response read failed", exception, result.phase)
            }
            try {
                JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            } catch (exception: Exception) {
                throw ExtensionSourceException("GitHub returned invalid JSON", exception, result.phase)
            }
        }
    }

    private fun JsonObject.requiredString(
        name: String,
        phase: ExtensionSourcePhase,
        message: String
    ): String {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw ExtensionSourceException(message, phase = phase)
        }
        return value.asString.takeIf(String::isNotBlank)
            ?: throw ExtensionSourceException(message, phase = phase)
    }

    private fun execute(
        initialRequest: Request,
        initialPhase: ExtensionSourcePhase
    ): PhasedResponse {
        var request = initialRequest
        var phase = initialPhase
        var redirects = 0
        while (true) {
            val response = try {
                transport.newCall(request).execute()
            } catch (exception: IOException) {
                throw ExtensionSourceException("GitHub request failed", exception, phase)
            }
            if (response.code !in REDIRECT_STATUS_CODES) return PhasedResponse(response, phase)
            if (redirects >= maxRedirects) {
                response.close()
                throw ExtensionRedirectException("GitHub redirect limit exceeded", phase)
            }
            val location = response.header("Location")
            val nextUrl = location?.let(response.request.url::resolve)
            response.close()
            if (nextUrl == null) {
                throw ExtensionRedirectException("GitHub redirect is missing a valid location", phase)
            }
            if (nextUrl.scheme != "https" && !allowInsecureTestBaseUrl) {
                throw ExtensionRedirectException("GitHub redirect must use HTTPS", phase)
            }
            redirects += 1
            if (initialPhase == ExtensionSourcePhase.ARCHIVE_REDIRECT) {
                phase = ExtensionSourcePhase.ARCHIVE_DOWNLOAD
            }
            request = request.newBuilder().url(nextUrl).build()
        }
    }

    private fun endpoint(vararg segments: String): HttpUrl = apiBaseUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    private fun readBounded(
        body: ResponseBody,
        maxBytes: Long,
        phase: ExtensionSourcePhase
    ): ByteArray {
        if (body.contentLength() > maxBytes) throw ExtensionDownloadTooLargeException(phase)
        val output = Buffer()
        var total = 0L
        while (true) {
            val count = body.source().read(output, minOf(8192L, maxBytes - total + 1L))
            if (count < 0) break
            total += count
            if (total > maxBytes) throw ExtensionDownloadTooLargeException(phase)
        }
        return output.readByteArray()
    }

    private data class PhasedResponse(
        val response: Response,
        val phase: ExtensionSourcePhase
    )

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
                        if (total > maxBytes) {
                            throw ExtensionDownloadTooLargeException(ExtensionSourcePhase.ARCHIVE_READ)
                        }
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

internal fun extensionHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .callTimeout(180, TimeUnit.SECONDS)
    .build()
