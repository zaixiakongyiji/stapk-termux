package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OpenAiCompatibleControllerTest {
    @Test
    fun `streaming generation forwards stream flag and returns SSE body stream`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setChunkedBody("data: {\"choices\":[]}\n\ndata: [DONE]\n\n", 16)
        )
        server.start()
        try {
            val controller = openAiController("stapk-openai-stream")

            val response = controller.generate(streamingRequest(server))
            val payload = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject

            assertTrue(payload.get("stream").asBoolean)
            assertNotNull(response.bodyStream)
            assertEquals("text/event-stream", response.mimeType.substringBefore(';'))
            response.bodyStream!!.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming generation exposes first event before provider completes`() {
        val firstEvent = "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
        val server = GatedProviderServer(
            statusCode = 200,
            contentType = "text/event-stream",
            firstBody = firstEvent.toByteArray(),
            remainingBody = "data: [DONE]\n\n".toByteArray()
        )
        try {
            val controller = openAiController("stapk-openai-incremental")

            val response = controller.generate(streamingRequest(server.baseUrl))
            val stream = requireNotNull(response.bodyStream)
            try {
                val firstBytes = stream.readExactly(firstEvent.toByteArray().size)

                assertEquals(firstEvent, firstBytes.toString(Charsets.UTF_8))
                assertTrue(server.gateReached)
                assertFalse(server.finished)
                server.releaseRemainingBody()
                assertTrue(server.awaitFinished())
            } finally {
                stream.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `provider response stream close is idempotent and cancels upstream call`() {
        val firstEvent = "data: {\"choices\":[]}\n\n"
        val server = GatedProviderServer(
            statusCode = 200,
            contentType = "text/event-stream",
            firstBody = firstEvent.toByteArray(),
            remainingBody = "data: [DONE]\n\n".toByteArray()
        )
        try {
            val providerCall = AtomicReference<Call?>()
            val client = OkHttpClient.Builder()
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) {
                        providerCall.compareAndSet(null, call)
                    }
                })
                .build()
            val controller = openAiController("stapk-openai-cancel", client)

            val response = controller.generate(streamingRequest(server.baseUrl))
            val stream = requireNotNull(response.bodyStream)
            val call = requireNotNull(providerCall.get())

            assertEquals(firstEvent, stream.readExactly(firstEvent.toByteArray().size).toString(Charsets.UTF_8))
            assertFalse(call.isCanceled())
            stream.close()
            stream.close()

            assertTrue(call.isCanceled())
        } finally {
            server.close()
        }
    }

    @Test
    fun `stream completion records one safe terminal diagnostic`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
        )
        server.start()
        try {
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-stream-completed-diagnostic").toFile())
            val logger = DiagnosticLogger(paths.logsDir)
            val controller = OpenAiCompatibleController(paths, OkHttpClient(), logger)
            controller.writeSecret("""{"key":"api_key_openai","value":"sk-private","label":"OpenAI"}""")

            requireNotNull(controller.generate(streamingRequest(server)).bodyStream).use { it.readBytes() }

            assertTerminalDiagnostic(paths, "completed", server.hostName)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `stream close before EOF records one safe canceled diagnostic`() {
        val firstEvent = "data: {\"choices\":[]}\n\n"
        val server = GatedProviderServer(
            statusCode = 200,
            contentType = "text/event-stream",
            firstBody = firstEvent.toByteArray(),
            remainingBody = "data: [DONE]\n\n".toByteArray()
        )
        try {
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-stream-canceled-diagnostic").toFile())
            val logger = DiagnosticLogger(paths.logsDir)
            val controller = OpenAiCompatibleController(paths, OkHttpClient(), logger)
            controller.writeSecret("""{"key":"api_key_openai","value":"sk-private","label":"OpenAI"}""")
            val stream = requireNotNull(controller.generate(streamingRequest(server.baseUrl)).bodyStream)

            stream.readExactly(firstEvent.toByteArray().size)
            stream.close()
            stream.close()

            assertTerminalDiagnostic(paths, "canceled", "127.0.0.1")
        } finally {
            server.close()
        }
    }

    @Test
    fun `stream read failure records one safe read error diagnostic`() {
        val firstEvent = "data: {\"choices\":[]}\n\n"
        val server = GatedProviderServer(
            statusCode = 200,
            contentType = "text/event-stream",
            firstBody = firstEvent.toByteArray(),
            remainingBody = ByteArray(0),
            contentLength = firstEvent.toByteArray().size.toLong() + 32L
        )
        try {
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-stream-read-error-diagnostic").toFile())
            val logger = DiagnosticLogger(paths.logsDir)
            val controller = OpenAiCompatibleController(paths, OkHttpClient(), logger)
            controller.writeSecret("""{"key":"api_key_openai","value":"sk-private","label":"OpenAI"}""")
            val request = """{"chat_completion_source":"openai","reverse_proxy":"${server.baseUrl}","stream":true,"messages":[{"role":"user","content":"private-prompt"}]}"""
            val stream = requireNotNull(controller.generate(request).bodyStream)

            stream.readExactly(firstEvent.toByteArray().size)
            server.releaseRemainingBody()
            assertThrows(IOException::class.java) { stream.readBytes() }
            stream.close()

            assertTerminalDiagnostic(paths, "read_error", "127.0.0.1", expectErrorClass = true)
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming generation wraps JSON provider response as one SSE event`() {
        val providerJson = """{"choices":[{"message":{"content":"Fallback"},"finish_reason":"stop"}]}"""
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(providerJson)
        )
        server.start()
        try {
            val controller = openAiController("stapk-openai-json-fallback")

            val response = controller.generate(streamingRequest(server))
            val body = requireNotNull(response.bodyStream).use { it.readBytes().toString(Charsets.UTF_8) }

            assertEquals("text/event-stream", response.mimeType.substringBefore(';'))
            assertEquals("data: $providerJson\n\ndata: [DONE]\n\n", body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `streaming JSON fallback normalizes pretty object with CRLF and blank lines`() {
        val providerJson = listOf(
            "{",
            "",
            "  \"choices\": [",
            "    { \"message\": { \"content\": \"Fallback\" } }",
            "  ]",
            "}"
        ).joinToString("\r\n")
        val expectedJson = """{"choices":[{"message":{"content":"Fallback"}}]}"""
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(providerJson)
        )
        server.start()
        try {
            val response = openAiController("stapk-openai-pretty-json-fallback")
                .generate(streamingRequest(server))
            val body = requireNotNull(response.bodyStream).use { it.readBytes().toString(Charsets.UTF_8) }

            assertEquals("text/event-stream", response.mimeType.substringBefore(';'))
            assertEquals("data: $expectedJson\n\ndata: [DONE]\n\n", body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `streaming JSON fallback accepts top level array and serializes one line`() {
        val providerJson = """[
            {"choice": 1},
            {"choice": 2}
        ]""".trimIndent()
        val expectedJson = """[{"choice":1},{"choice":2}]"""
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(providerJson)
        )
        server.start()
        try {
            val response = openAiController("stapk-openai-array-json-fallback")
                .generate(streamingRequest(server))
            val body = requireNotNull(response.bodyStream).use { it.readBytes().toString(Charsets.UTF_8) }

            assertEquals("data: $expectedJson\n\ndata: [DONE]\n\n", body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `streaming JSON fallback rejects malformed structured response`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{not-json")
        )
        server.start()
        try {
            val response = openAiController("stapk-openai-malformed-json-fallback")
                .generate(streamingRequest(server))

            assertEquals(502, response.statusCode)
            assertNull(response.bodyStream)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `streaming JSON fallback rejects primitive top level values`() {
        val primitives = listOf("null", "true", "42", "\"text\"")
        val server = MockWebServer()
        primitives.forEach { primitive ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(primitive)
            )
        }
        server.start()
        try {
            val controller = openAiController("stapk-openai-primitive-json-fallback")

            primitives.forEach {
                val response = controller.generate(streamingRequest(server))
                assertEquals(502, response.statusCode)
                assertNull(response.bodyStream)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `streaming generation wraps structured syntax JSON response as SSE`() {
        val providerJson = """{"error":{"message":"Fallback"}}"""
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/problem+json; charset=utf-8")
                .setBody(providerJson)
        )
        server.start()
        try {
            val controller = openAiController("stapk-openai-suffix-json-fallback")

            val response = controller.generate(streamingRequest(server))
            val body = requireNotNull(response.bodyStream).use { it.readBytes().toString(Charsets.UTF_8) }

            assertEquals("text/event-stream", response.mimeType.substringBefore(';'))
            assertEquals("data: $providerJson\n\ndata: [DONE]\n\n", body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `generate rejects non boolean stream without contacting provider`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        server.start()
        try {
            val controller = openAiController("stapk-openai-invalid-stream")

            val response = controller.generate(
                """{"chat_completion_source":"openai","reverse_proxy":"${server.url("/v1")}","stream":"true","messages":[]}"""
            )

            assertEquals(400, response.statusCode)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `streaming JSON fallback enforces exact byte limit`() {
        val prefix = "{\"padding\":\""
        val suffix = "\"}"
        val paddingAtLimit = MAX_STREAM_JSON_FALLBACK_BYTES -
            prefix.toByteArray().size - suffix.toByteArray().size
        val atLimit = prefix + "a".repeat(paddingAtLimit) + suffix
        val overLimit = prefix + "a".repeat(paddingAtLimit + 1) + suffix
        assertEquals(MAX_STREAM_JSON_FALLBACK_BYTES, atLimit.toByteArray().size)
        assertEquals(MAX_STREAM_JSON_FALLBACK_BYTES + 1, overLimit.toByteArray().size)

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(atLimit)
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setChunkedBody(overLimit, 8192)
        )
        server.start()
        try {
            val controller = openAiController("stapk-openai-json-limit")

            val accepted = controller.generate(streamingRequest(server))
            val acceptedBody = requireNotNull(accepted.bodyStream).use { it.readBytes().toString(Charsets.UTF_8) }
            val rejected = controller.generate(streamingRequest(server))

            assertEquals("data: $atLimit\n\ndata: [DONE]\n\n", acceptedBody)
            assertEquals(502, rejected.statusCode)
            assertNull(rejected.bodyStream)
            assertFalse(rejected.bodyText.orEmpty().contains(overLimit.takeLast(32)))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `streaming JSON fallback rejects oversized known content length before body arrives`() {
        val prefix = "{\"padding\":\""
        val suffix = "\"}"
        val oversizedBody = prefix + "a".repeat(
            MAX_STREAM_JSON_FALLBACK_BYTES + 1 - prefix.toByteArray().size - suffix.toByteArray().size
        ) + suffix
        assertEquals(MAX_STREAM_JSON_FALLBACK_BYTES + 1, oversizedBody.toByteArray().size)
        val server = GatedProviderServer(
            statusCode = 200,
            contentType = "application/json",
            firstBody = ByteArray(0),
            remainingBody = oversizedBody.toByteArray(),
            contentLength = oversizedBody.toByteArray().size.toLong()
        )
        try {
            val controller = openAiController("stapk-openai-known-json-limit")

            val response = controller.generate(streamingRequest(server.baseUrl))

            assertEquals(502, response.statusCode)
            assertNull(response.bodyStream)
            assertTrue(server.gateReached)
            assertFalse(server.finished)
        } finally {
            server.close()
        }
    }

    @Test
    fun `streaming provider error stops reading unknown body after bounded prefix`() {
        val privateTail = "private-provider-tail"
        val server = GatedProviderServer(
            statusCode = 429,
            contentType = "text/plain",
            firstBody = "x".repeat(STREAM_PROVIDER_ERROR_LIMIT_BYTES + 1).toByteArray(),
            remainingBody = privateTail.toByteArray()
        )
        try {
            val controller = openAiController("stapk-openai-provider-error-limit")

            val response = controller.generate(streamingRequest(server.baseUrl))

            assertEquals(429, response.statusCode)
            assertFalse(response.bodyText.orEmpty().contains(privateTail))
            assertTrue(server.gateReached)
            assertFalse(server.finished)
        } finally {
            server.close()
        }
    }

    @Test
    fun `generation allows provider responses longer than the base client read timeout`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"Delayed"}}]}""")
                .setBodyDelay(200, TimeUnit.MILLISECONDS)
        )
        server.start()
        try {
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-openai-delayed").toFile())
            val client = OkHttpClient.Builder()
                .readTimeout(50, TimeUnit.MILLISECONDS)
                .build()
            val controller = OpenAiCompatibleController(paths, client)
            controller.writeSecret("""{"key":"api_key_openai","value":"sk-test","label":"OpenAI"}""")

            val response = controller.generate(
                """{"chat_completion_source":"openai","reverse_proxy":"${server.url("/v1")}","messages":[]}"""
            )

            assertEquals(200, response.statusCode)
            assertTrue(response.bodyText!!.contains("Delayed"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `generate forwards only standard fields with stream disabled`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"Hello"}}]}"""))
        server.start()
        try {
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-openai").toFile())
            val controller = OpenAiCompatibleController(paths, OkHttpClient())
            controller.writeSecret("""{"key":"api_key_openai","value":"sk-test","label":"OpenAI"}""")

            val response = controller.generate(
                """{
                    "chat_completion_source":"openai",
                    "reverse_proxy":"${server.url("/v1")}",
                    "model":"gpt-test",
                    "messages":[{"role":"user","content":"Hi"}],
                    "temperature":0.7,
                    "stream":false,
                    "proxy_password":"local-only",
                    "custom_url":"https://ignored.example/v1"
                }""".trimIndent()
            )
            val request = server.takeRequest()
            val payload = JsonParser.parseString(request.body.readUtf8()).asJsonObject

            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer sk-test", request.getHeader("Authorization"))
            assertFalse(payload.get("stream").asBoolean)
            assertEquals("gpt-test", payload.get("model").asString)
            assertTrue(payload.has("messages"))
            assertTrue(payload.has("temperature"))
            assertFalse(payload.has("chat_completion_source"))
            assertFalse(payload.has("reverse_proxy"))
            assertFalse(payload.has("proxy_password"))
            assertFalse(payload.has("custom_url"))
            assertEquals(200, response.statusCode)
            assertNull(response.bodyStream)
            assertTrue(response.bodyText!!.contains("Hello"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `generate preserves frontend assembled world info message order and content`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        server.start()
        try {
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-openai-world-info").toFile())
            val controller = OpenAiCompatibleController(paths, OkHttpClient())
            controller.writeSecret("""{"key":"api_key_openai","value":"sk-test","label":"OpenAI"}""")
            val messages = """[
                {"role":"system","content":"Base system prompt"},
                {"role":"system","content":"[World Info]\nThe moon controls the silver tide."},
                {"role":"assistant","content":"Previous answer"},
                {"role":"user","content":"What happens tonight?"}
            ]""".trimIndent()

            val response = controller.generate(
                """{
                    "chat_completion_source":"openai",
                    "reverse_proxy":"${server.url("/v1")}",
                    "model":"gpt-test",
                    "messages":$messages
                }""".trimIndent()
            )
            val payload = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject

            assertEquals(200, response.statusCode)
            assertEquals(
                JsonParser.parseString(messages),
                payload.get("messages")
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `custom status uses custom key and URL then returns model data`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"custom-model"}]}"""))
        server.start()
        try {
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-custom").toFile())
            val controller = OpenAiCompatibleController(paths, OkHttpClient())
            controller.writeSecret("""{"key":"api_key_custom","value":"custom-key","label":"Custom"}""")

            val response = controller.status(
                """{"chat_completion_source":"custom","custom_url":"${server.url("/api")}"}"""
            )
            val request = server.takeRequest()

            assertEquals("/api/models", request.path)
            assertEquals("Bearer custom-key", request.getHeader("Authorization"))
            assertEquals(200, response.statusCode)
            assertEquals("custom-model", JsonParser.parseString(response.bodyText).asJsonObject
                .getAsJsonArray("data")[0].asJsonObject.get("id").asString)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `custom requests work without a key and omit authorization`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        server.start()
        try {
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-custom-keyless").toFile())
            val controller = OpenAiCompatibleController(paths, OkHttpClient())

            val response = controller.generate(
                """{"chat_completion_source":"custom","custom_url":"${server.url("/v1")}","messages":[]}"""
            )

            assertEquals(200, response.statusCode)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals(null, request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `provider errors retain status while redacting API key`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("provider rejected sk-secret"))
        server.start()
        try {
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-error").toFile())
            val controller = OpenAiCompatibleController(paths, OkHttpClient())
            controller.writeSecret("""{"key":"api_key_openai","value":"sk-secret","label":"OpenAI"}""")

            val response = controller.generate(
                """{"chat_completion_source":"openai","reverse_proxy":"${server.url("/v1")}","stream":true,"messages":[]}"""
            )

            assertEquals(429, response.statusCode)
            assertTrue(JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject.get("stream").asBoolean)
            assertTrue(response.bodyText!!.contains("provider rejected"))
            assertFalse(response.bodyText!!.contains("sk-secret"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `provider HTTP errors record only safe metadata`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("provider rejected sk-secret prompt-text"))
        server.start()
        try {
            val providerUrl = server.url("/v1")
            val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-provider-diagnostic").toFile())
            val logger = DiagnosticLogger(paths.logsDir, clock = { 1234L })
            val controller = OpenAiCompatibleController(paths, OkHttpClient(), logger, nanoTime = { 5_000_000L })
            controller.writeSecret("""{"key":"api_key_openai","value":"sk-secret","label":"OpenAI"}""")

            val response = controller.generate(
                """{"chat_completion_source":"openai","reverse_proxy":"$providerUrl","stream":true,"messages":[{"role":"user","content":"prompt-text"}]}"""
            )

            assertEquals(429, response.statusCode)
            val diagnostic = paths.logsDir.resolve("diagnostics.jsonl").readText()
            val event = JsonParser.parseString(diagnostic.trim()).asJsonObject
            assertEquals("PROVIDER", event.get("area").asString)
            assertEquals("provider_http_error", event.get("code").asString)
            assertEquals("429", event.getAsJsonObject("fields").get("status").asString)
            assertEquals(providerUrl.host, event.getAsJsonObject("fields").get("host").asString)
            assertTrue(event.getAsJsonObject("fields").has("durationMs"))
            assertEquals("true", event.getAsJsonObject("fields").get("stream").asString)
            assertFalse(diagnostic.contains("sk-secret"))
            assertFalse(diagnostic.contains("prompt-text"))
            assertFalse(diagnostic.contains("provider rejected"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `provider network errors record exception class without request data`() {
        val server = MockWebServer()
        server.start()
        val baseUrl = server.url("/v1").toString()
        server.shutdown()
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-provider-network-diagnostic").toFile())
        val logger = DiagnosticLogger(paths.logsDir)
        val controller = OpenAiCompatibleController(paths, OkHttpClient(), logger)

        val response = controller.generate(
            """{"chat_completion_source":"custom","custom_url":"$baseUrl","stream":true,"messages":[{"role":"user","content":"private prompt"}]}"""
        )

        assertEquals(502, response.statusCode)
        val diagnostic = paths.logsDir.resolve("diagnostics.jsonl").readText()
        val event = JsonParser.parseString(diagnostic.trim()).asJsonObject
        assertEquals("PROVIDER", event.get("area").asString)
        assertEquals("provider_network_error", event.get("code").asString)
        assertTrue(event.getAsJsonObject("fields").has("errorClass"))
        assertEquals("true", event.getAsJsonObject("fields").get("stream").asString)
        assertFalse(diagnostic.contains("private prompt"))
    }

    @Test
    fun `rejects non string request fields and handles malformed provider errors`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-invalid-types").toFile())
        val controller = OpenAiCompatibleController(paths, OkHttpClient())

        listOf(
            """{"key":[],"value":"key","label":"label"}""",
            """{"key":"api_key_openai","value":{},"label":"label"}""",
            """{"key":"api_key_openai","value":"key","label":null}"""
        ).forEach { body -> assertEquals(400, controller.writeSecret(body).statusCode) }
        assertEquals(400, controller.deleteSecret("""{"key":"api_key_openai","id":[]}""").statusCode)
        assertEquals(400, controller.generate("""{"chat_completion_source":[],"messages":[]}""").statusCode)
        assertEquals(400, controller.generate("""{"chat_completion_source":"openai","reverse_proxy":{},"messages":[]}""").statusCode)
        assertEquals(400, controller.generate("""{"chat_completion_source":"custom","custom_url":[],"messages":[]}""").statusCode)

        val provider = MockWebServer()
        provider.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":[]}"""))
        provider.start()
        try {
            controller.writeSecret("""{"key":"api_key_openai","value":"sk-invalid","label":"OpenAI"}""")
            val response = controller.generate(
                """{"chat_completion_source":"openai","reverse_proxy":"${provider.url("/v1")}","messages":[]}"""
            )

            assertEquals(422, response.statusCode)
            assertTrue(response.bodyText!!.contains("provider 请求失败"))
        } finally {
            provider.shutdown()
        }
    }

    private fun openAiController(
        directoryPrefix: String,
        client: OkHttpClient = OkHttpClient()
    ): OpenAiCompatibleController {
        val paths = NativeAdapterPaths(Files.createTempDirectory(directoryPrefix).toFile())
        return OpenAiCompatibleController(paths, client).also {
            it.writeSecret("""{"key":"api_key_openai","value":"sk-test","label":"OpenAI"}""")
        }
    }

    private fun streamingRequest(server: MockWebServer): String =
        streamingRequest(server.url("/v1").toString())

    private fun streamingRequest(baseUrl: String): String =
        """{"chat_completion_source":"openai","reverse_proxy":"$baseUrl","stream":true,"messages":[]}"""

    private fun assertTerminalDiagnostic(
        paths: NativeAdapterPaths,
        expectedTerminal: String,
        expectedHost: String,
        expectErrorClass: Boolean = false
    ) {
        val diagnostics = paths.logsDir.resolve("diagnostics.jsonl").readLines()
        val events = diagnostics.map { JsonParser.parseString(it).asJsonObject }
            .filter { it.get("code").asString == "provider_stream_terminal" }
        assertEquals(1, events.size)
        val fields = events.single().getAsJsonObject("fields")
        assertEquals(expectedTerminal, fields.get("terminal").asString)
        assertEquals(expectedHost, fields.get("host").asString)
        assertEquals("200", fields.get("status").asString)
        assertEquals("true", fields.get("stream").asString)
        assertTrue(fields.has("durationMs"))
        assertEquals(expectErrorClass, fields.has("errorClass"))
        val text = diagnostics.joinToString("\n")
        assertFalse(text.contains("sk-private"))
        assertFalse(text.contains("private-prompt"))
        assertFalse(text.contains("data:"))
    }

    private fun java.io.InputStream.readExactly(byteCount: Int): ByteArray {
        val result = ByteArray(byteCount)
        var offset = 0
        while (offset < result.size) {
            val count = read(result, offset, result.size - offset)
            check(count >= 0) { "Provider stream ended before the expected event" }
            offset += count
        }
        return result
    }

    private class GatedProviderServer(
        private val statusCode: Int,
        private val contentType: String,
        private val firstBody: ByteArray,
        private val remainingBody: ByteArray,
        private val contentLength: Long? = null
    ) : Closeable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val activeSocket = AtomicReference<Socket?>()
        private val release = CountDownLatch(1)
        private val workerFinished = CountDownLatch(1)
        private val gateWasReached = AtomicBoolean(false)
        private val responseWasFinished = AtomicBoolean(false)
        private val worker = thread(start = true, isDaemon = true, name = "stapk-gated-provider") {
            try {
                serverSocket.accept().use { socket ->
                    activeSocket.set(socket)
                    socket.soTimeout = 5_000
                    consumeRequest(socket.getInputStream())
                    val output = socket.getOutputStream()
                    writeHeaders(output)
                    if (firstBody.isNotEmpty()) writeBodyPart(output, firstBody)
                    gateWasReached.set(true)
                    output.flush()
                    release.await()
                    if (remainingBody.isNotEmpty()) writeBodyPart(output, remainingBody)
                    if (contentLength == null) output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                    responseWasFinished.set(true)
                }
            } catch (_: Exception) {
                // 客户端取消时服务端写入失败属于预期路径。
            } finally {
                workerFinished.countDown()
            }
        }

        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}/v1"
        val gateReached: Boolean get() = gateWasReached.get()
        val finished: Boolean get() = responseWasFinished.get()

        fun releaseRemainingBody() {
            release.countDown()
        }

        fun awaitFinished(): Boolean =
            workerFinished.await(5, TimeUnit.SECONDS) && responseWasFinished.get()

        override fun close() {
            release.countDown()
            worker.join(4_000)
            activeSocket.get()?.close()
            serverSocket.close()
            if (worker.isAlive) worker.join(1_000)
        }

        private fun writeHeaders(output: OutputStream) {
            val reason = when (statusCode) {
                200 -> "OK"
                429 -> "Too Many Requests"
                else -> "Response"
            }
            val framing = contentLength?.let { "Content-Length: $it\r\n" }
                ?: "Transfer-Encoding: chunked\r\n"
            output.write(
                (
                    "HTTP/1.1 $statusCode $reason\r\n" +
                        "Content-Type: $contentType\r\n" +
                        framing +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
        }

        private fun writeBodyPart(output: OutputStream, bytes: ByteArray) {
            if (contentLength == null) {
                output.write(bytes.size.toString(16).toByteArray(Charsets.US_ASCII))
                output.write("\r\n".toByteArray(Charsets.US_ASCII))
            }
            output.write(bytes)
            if (contentLength == null) output.write("\r\n".toByteArray(Charsets.US_ASCII))
        }

        private fun consumeRequest(input: InputStream) {
            val headerBytes = ByteArrayOutputStream()
            val delimiter = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
            var matched = 0
            while (matched < delimiter.size && headerBytes.size() <= MAX_TEST_REQUEST_HEADER_BYTES) {
                val value = input.read()
                check(value >= 0) { "Provider request ended before headers" }
                headerBytes.write(value)
                matched = if (value == delimiter[matched].toInt()) {
                    matched + 1
                } else if (value == delimiter[0].toInt()) {
                    1
                } else {
                    0
                }
            }
            check(matched == delimiter.size) { "Provider request headers are too large" }
            val headers = headerBytes.toString(Charsets.US_ASCII.name())
            val bodyLength = Regex("(?im)^Content-Length:\\s*(\\d+)\\s*$")
                .find(headers)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: 0
            var remaining = bodyLength
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                check(count > 0) { "Provider request ended before body" }
                remaining -= count
            }
        }
    }

    private companion object {
        const val STREAM_PROVIDER_ERROR_LIMIT_BYTES = 16 * 1024
        const val MAX_TEST_REQUEST_HEADER_BYTES = 64 * 1024
    }
}
