package com.stapk.mobile.nativeadapter.vector

import com.google.gson.JsonParser
import com.stapk.mobile.nativeadapter.DiagnosticLogger
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingProviderClientTest {
    @Test
    fun `client uses bounded timeouts and disables automatic connection retry`() {
        val client = embeddingHttpClient()

        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun `sends saved provider endpoint key model and all inputs exactly once`() = withServer { server, client ->
        server.enqueue(jsonResponse("""{"data":[{"index":0,"embedding":[1,0]}]}"""))

        client.embed(snapshot(server), listOf("private first"))

        val request = server.takeRequest()
        assertEquals("/v1/embeddings", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("embed-model", body.get("model").asString)
        assertEquals(listOf("private first"), body.getAsJsonArray("input").map { it.asString })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `response is reordered by index and normalized`() = withServer { server, client ->
        server.enqueue(jsonResponse("""
            {"data":[
              {"index":1,"embedding":[0,3,4]},
              {"index":0,"embedding":[2,0,0]}
            ]}
        """))

        val result = client.embed(snapshot(server), listOf("first", "second"))

        assertEquals(3, result.dimension)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), result.vectors[0], 0.00001f)
        assertArrayEquals(floatArrayOf(0f, 0.6f, 0.8f), result.vectors[1], 0.00001f)
    }

    @Test
    fun `rejects invalid provider vectors as unprocessable without retry`() = withServer { server, client ->
        listOf(
            """{"data":[{"index":0,"embedding":[1,0]}]}""",
            """{"data":[{"index":0,"embedding":[1,0]},{"index":0,"embedding":[1,0]}]}""",
            """{"data":[{"index":2,"embedding":[1,0]},{"index":1,"embedding":[1,0]}]}""",
            """{"data":[{"index":0,"embedding":[1,0]},{"index":1,"embedding":[1,0,0]}]}""",
            """{"data":[{"index":0,"embedding":[0,0]},{"index":1,"embedding":[1,0]}]}""",
            """{"data":[{"index":0,"embedding":[1e100,0]},{"index":1,"embedding":[1,0]}]}"""
        ).forEach { response ->
            server.enqueue(jsonResponse(response))
            val failure = assertThrows(EmbeddingFailure::class.java) {
                client.embed(snapshot(server), listOf("one", "two"))
            }
            assertEquals(422, failure.httpStatus)
            assertEquals("embedding_invalid_vector", failure.errorCode)
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `maps malformed json and provider statuses without exposing provider detail`() = withServer { server, client ->
        server.enqueue(jsonResponse("{"))
        val malformed = assertThrows(EmbeddingFailure::class.java) {
            client.embed(snapshot(server), listOf("private text"))
        }
        assertEquals(502, malformed.httpStatus)
        assertEquals("embedding_provider_error", malformed.errorCode)

        listOf(401, 403, 500).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("provider private detail"))
            val failure = assertThrows(EmbeddingFailure::class.java) {
                client.embed(snapshot(server), listOf("private text"))
            }
            assertEquals(502, failure.httpStatus)
            assertEquals("embedding_provider_error", failure.errorCode)
        }
        server.enqueue(MockResponse().setResponseCode(429))
        val rateLimited = assertThrows(EmbeddingFailure::class.java) {
            client.embed(snapshot(server), listOf("private text"))
        }
        assertEquals(429, rateLimited.httpStatus)
        assertEquals("embedding_rate_limited", rateLimited.errorCode)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `maps socket timeout and response larger than thirty two mib`() {
        val timeoutClient = EmbeddingProviderClient(
            OkHttpClient.Builder().addInterceptor { throw SocketTimeoutException("private endpoint") }.build(),
            logger()
        )
        val timeout = assertThrows(EmbeddingFailure::class.java) {
            timeoutClient.embed(snapshot("http://127.0.0.1:1/v1"), listOf("private text"))
        }
        assertEquals(504, timeout.httpStatus)
        assertEquals("embedding_timeout", timeout.errorCode)

        withServer { server, client ->
            server.enqueue(jsonResponse("x".repeat(32 * 1024 * 1024 + 1)))
            val tooLarge = assertThrows(EmbeddingFailure::class.java) {
                client.embed(snapshot(server), listOf("private text"))
            }
            assertEquals(413, tooLarge.httpStatus)
            assertEquals("vector_request_too_large", tooLarge.errorCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `maps timeout raised while reading a successful response body to gateway timeout`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse("""{"data":[{"index":0,"embedding":[1,0]}]}"""))
        server.start()
        try {
            val client = EmbeddingProviderClient(
                OkHttpClient.Builder().addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    response.newBuilder().body(timeoutBody(requireNotNull(response.body))).build()
                }.build(),
                logger()
            )

            val failure = assertThrows(EmbeddingFailure::class.java) {
                client.embed(snapshot(server), listOf("private text"))
            }

            assertEquals(504, failure.httpStatus)
            assertEquals("embedding_timeout", failure.errorCode)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `diagnostics redact key text vector and full base url`() = withServer { server, client ->
        server.enqueue(jsonResponse("{"))
        assertThrows(EmbeddingFailure::class.java) {
            client.embed(snapshot(server), listOf("private chunk text"))
        }

        val diagnostics = File(loggerDirectory, "diagnostics.jsonl").readText()
        assertFalse(diagnostics.contains("test-key"))
        assertFalse(diagnostics.contains("private chunk text"))
        assertFalse(diagnostics.contains("127.0.0.1:"))
        assertFalse(diagnostics.contains("embedding"))
        assertTrue(diagnostics.contains("\"batchCount\":\"1\""))
        assertTrue(diagnostics.contains("\"errorClass\""))
    }

    private fun withServer(block: (MockWebServer, EmbeddingProviderClient) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server, EmbeddingProviderClient(OkHttpClient(), logger()))
        } finally {
            server.shutdown()
        }
    }

    private fun snapshot(server: MockWebServer): EmbeddingProviderSnapshot = snapshot(
        server.url("/v1/").toString().trimEnd('/')
    )

    private fun snapshot(baseUrl: String): EmbeddingProviderSnapshot = EmbeddingProviderSnapshot(
        EmbeddingProviderConfig(EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE, baseUrl, "embed-model"),
        "test-key",
        baseUrl,
        "a".repeat(64),
        "b".repeat(64)
    )

    private fun logger(): DiagnosticLogger = DiagnosticLogger(loggerDirectory)

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun timeoutBody(delegate: okhttp3.ResponseBody): okhttp3.ResponseBody = object : okhttp3.ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                throw SocketTimeoutException("response body timed out")
            }
        }.buffer()
    }

    private companion object {
        val loggerDirectory: File = Files.createTempDirectory("embedding-provider-client-test").toFile()
    }
}
