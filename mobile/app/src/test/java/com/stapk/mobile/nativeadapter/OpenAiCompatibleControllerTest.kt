package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class OpenAiCompatibleControllerTest {
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
                    "stream":true,
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
                """{"chat_completion_source":"openai","reverse_proxy":"${server.url("/v1")}","messages":[]}"""
            )

            assertEquals(429, response.statusCode)
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
                """{"chat_completion_source":"openai","reverse_proxy":"$providerUrl","messages":[{"role":"user","content":"prompt-text"}]}"""
            )

            assertEquals(429, response.statusCode)
            val diagnostic = paths.logsDir.resolve("diagnostics.jsonl").readText()
            val event = JsonParser.parseString(diagnostic.trim()).asJsonObject
            assertEquals("PROVIDER", event.get("area").asString)
            assertEquals("provider_http_error", event.get("code").asString)
            assertEquals("429", event.getAsJsonObject("fields").get("status").asString)
            assertEquals(providerUrl.host, event.getAsJsonObject("fields").get("host").asString)
            assertTrue(event.getAsJsonObject("fields").has("durationMs"))
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
            """{"chat_completion_source":"custom","custom_url":"$baseUrl","messages":[{"role":"user","content":"private prompt"}]}"""
        )

        assertEquals(502, response.statusCode)
        val diagnostic = paths.logsDir.resolve("diagnostics.jsonl").readText()
        val event = JsonParser.parseString(diagnostic.trim()).asJsonObject
        assertEquals("PROVIDER", event.get("area").asString)
        assertEquals("provider_network_error", event.get("code").asString)
        assertTrue(event.getAsJsonObject("fields").has("errorClass"))
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
}
