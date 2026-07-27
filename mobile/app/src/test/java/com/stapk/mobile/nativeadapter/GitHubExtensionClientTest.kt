package com.stapk.mobile.nativeadapter

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class GitHubExtensionClientTest {
    @Test
    fun `extension client uses bounded production timeouts`() {
        val client = extensionHttpClient()

        assertEquals(20_000, client.connectTimeoutMillis)
        assertEquals(120_000, client.readTimeoutMillis)
        assertEquals(20_000, client.writeTimeoutMillis)
        assertEquals(180_000, client.callTimeoutMillis)
    }

    @Test
    fun `accepts only canonical public GitHub repository URLs`() {
        assertEquals(
            GitHubRepository("zonde306", "ST-Prompt-Template", "https://github.com/zonde306/ST-Prompt-Template"),
            GitHubExtensionClient.parseRepositoryUrl("https://github.com/zonde306/ST-Prompt-Template")
        )
        assertEquals(
            GitHubRepository("N0VI028", "JS-Slash-Runner", "https://github.com/N0VI028/JS-Slash-Runner"),
            GitHubExtensionClient.parseRepositoryUrl("https://github.com/N0VI028/JS-Slash-Runner.git")
        )

        listOf(
            "http://github.com/owner/repo",
            "https://user@github.com/owner/repo",
            "https://github.com/owner/repo?ref=main",
            "https://github.com/owner/repo#readme",
            "https://github.com/owner/repo/tree/main",
            "https://gitlab.com/owner/repo",
            "https://github.com//repo",
            "https://github.com/owner/"
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                GitHubExtensionClient.parseRepositoryUrl(url)
            }
        }
    }

    @Test
    fun `resolves default branch commit and bounded archive through GitHub API`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"default_branch":"main"}"""))
        server.enqueue(MockResponse().setBody("""{"sha":"abc123"}"""))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/archive.zip"))
        server.enqueue(MockResponse().setBody("archive-body").setHeader("Content-Type", "application/zip"))
        server.start()
        try {
            val client = GitHubExtensionClient(
                apiBaseUrl = server.url("/"),
                allowInsecureTestBaseUrl = true
            )

            val release = client.resolve("https://github.com/zonde306/ST-Prompt-Template", null)

            assertEquals("main", release.branch)
            assertEquals("abc123", release.commitSha)
            assertEquals("archive-body", release.archive.string())
            assertEquals("/repos/zonde306/ST-Prompt-Template", server.takeRequest().path)
            assertEquals("/repos/zonde306/ST-Prompt-Template/commits/main", server.takeRequest().path)
            assertEquals("/repos/zonde306/ST-Prompt-Template/zipball/abc123", server.takeRequest().path)
            assertEquals("/archive.zip", server.takeRequest().path)
            assertTrue(server.requestCount == 4)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `default client allows archive response slower than OkHttp ten second default`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"default_branch":"main"}"""))
        server.enqueue(MockResponse().setBody("""{"sha":"abc123"}"""))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/archive.zip"))
        server.enqueue(
            MockResponse()
                .setHeadersDelay(11, TimeUnit.SECONDS)
                .setBody("archive-body")
                .setHeader("Content-Type", "application/zip")
        )
        server.start()
        try {
            val release = GitHubExtensionClient(
                apiBaseUrl = server.url("/"),
                allowInsecureTestBaseUrl = true
            ).resolve("https://github.com/owner/repo", null)

            assertEquals("archive-body", release.archive.string())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `classifies timeout after archive redirect as archive download failure`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"default_branch":"main"}"""))
        server.enqueue(MockResponse().setBody("""{"sha":"abc123"}"""))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/archive.zip"))
        server.enqueue(
            MockResponse()
                .setHeadersDelay(250, TimeUnit.MILLISECONDS)
                .setBody("archive-body")
        )
        server.start()
        try {
            val client = GitHubExtensionClient(
                client = OkHttpClient.Builder()
                    .readTimeout(50, TimeUnit.MILLISECONDS)
                    .build(),
                apiBaseUrl = server.url("/"),
                allowInsecureTestBaseUrl = true
            )

            val failure = assertThrows(ExtensionSourceException::class.java) {
                client.resolve("https://github.com/owner/repo", null)
            }

            assertEquals(ExtensionSourcePhase.ARCHIVE_DOWNLOAD, failure.phase)
            assertTrue(failure.cause is SocketTimeoutException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `classifies interrupted metadata body read as metadata failure`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setBody("""{"default_branch":"${"main".repeat(4096)}"}""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.start()
        try {
            val client = GitHubExtensionClient(
                apiBaseUrl = server.url("/"),
                allowInsecureTestBaseUrl = true
            )

            val failure = assertThrows(ExtensionSourceException::class.java) {
                client.resolve("https://github.com/owner/repo", null)
            }

            assertEquals(ExtensionSourcePhase.METADATA, failure.phase)
            assertTrue(failure.cause is java.io.IOException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejects non-string default branch and commit SHA in their source phases`() {
        run {
            val server = MockWebServer()
            server.enqueue(MockResponse().setBody("""{"default_branch":{}}"""))
            server.start()
            try {
                val client = GitHubExtensionClient(
                    apiBaseUrl = server.url("/"),
                    allowInsecureTestBaseUrl = true
                )

                val failure = assertThrows(ExtensionSourceException::class.java) {
                    client.resolve("https://github.com/owner/repo", null)
                }

                assertEquals(ExtensionSourcePhase.METADATA, failure.phase)
            } finally {
                server.shutdown()
            }
        }

        run {
            val server = MockWebServer()
            server.enqueue(MockResponse().setBody("""{"default_branch":"main"}"""))
            server.enqueue(MockResponse().setBody("""{"sha":false}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            try {
                val client = GitHubExtensionClient(
                    apiBaseUrl = server.url("/"),
                    allowInsecureTestBaseUrl = true
                )

                val failure = assertThrows(ExtensionSourceException::class.java) {
                    client.resolve("https://github.com/owner/repo", null)
                }

                assertEquals(ExtensionSourcePhase.COMMIT, failure.phase)
                assertEquals(2, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `requires HTTPS maps HTTP errors and limits redirects`() {
        val server = MockWebServer()
        server.start()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                GitHubExtensionClient(apiBaseUrl = server.url("/"))
            }

            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            val client = GitHubExtensionClient(
                apiBaseUrl = server.url("/"),
                allowInsecureTestBaseUrl = true,
                maxRedirects = 2
            )
            val notFound = assertThrows(ExtensionHttpException::class.java) {
                client.resolve("https://github.com/owner/repo", null)
            }
            assertEquals(404, notFound.statusCode)

            server.enqueue(MockResponse().setBody("""{"default_branch":"main"}"""))
            server.enqueue(MockResponse().setBody("""{"sha":"abc123"}"""))
            repeat(3) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/redirect-$it")) }
            assertThrows(ExtensionRedirectException::class.java) {
                client.resolve("https://github.com/owner/redirected", null)
            }
        } finally {
            server.shutdown()
        }
    }
}
