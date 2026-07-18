package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenizerControllerTest {
    private val controller = TokenizerController()

    @Test
    fun `cl100k encode returns fixed vector chunks and decodes losslessly`() {
        val encoded = controller.encode("gpt-4-turbo", """{"text":"hello world"}""")

        assertEquals(200, encoded.statusCode)
        val body = JsonParser.parseString(encoded.bodyText).asJsonObject
        assertEquals(listOf(15339, 1917), body.getAsJsonArray("ids").map { it.asInt })
        assertEquals(2, body.get("count").asInt)
        assertEquals(listOf("hello", " world"), body.getAsJsonArray("chunks").map { it.asString })

        val decoded = controller.decode("gpt-4-turbo", """{"ids":[15339,1917]}""")
        assertEquals(200, decoded.statusCode)
        assertEquals("hello world", JsonParser.parseString(decoded.bodyText).asJsonObject.get("text").asString)
    }

    @Test
    fun `gpt4o and reasoning model families use o200k while other models use cl100k`() {
        listOf(
            "gpt-4o",
            "GPT-4O-mini",
            "gpt-4.1",
            "gpt-4.5-preview",
            "gpt-5",
            "gpt-5.5-2026-04-23",
            "openai/gpt-5-mini",
            "o1-mini",
            "o3-mini",
            "o4-mini"
        ).forEach { model ->
            val body = JsonParser.parseString(controller.encode(model, """{"text":"hello world"}""").bodyText)
                .asJsonObject
            assertEquals(model, listOf(24912, 2375), body.getAsJsonArray("ids").map { it.asInt })
        }

        val fallback = JsonParser.parseString(
            controller.encode("custom-openai-compatible", """{"text":"hello world"}""").bodyText
        ).asJsonObject
        assertEquals(listOf(15339, 1917), fallback.getAsJsonArray("ids").map { it.asInt })
    }

    @Test
    fun `chat count uses message value tokens name adjustment and reply padding`() {
        val response = controller.count(
            "gpt-4-turbo",
            """[{"role":"user","content":"hello world","name":"Alice"}]"""
        )

        assertEquals(200, response.statusCode)
        assertEquals(11, JsonParser.parseString(response.bodyText).asJsonObject.get("token_count").asInt)
        val multiple = controller.count(
            "gpt-4-turbo",
            """[{"role":"user","content":"hello"},{"role":"assistant","content":"world"}]"""
        )
        assertEquals(13, JsonParser.parseString(multiple.bodyText).asJsonObject.get("token_count").asInt)
        assertEquals(
            3,
            JsonParser.parseString(controller.count("gpt-4-turbo", "[]").bodyText)
                .asJsonObject.get("token_count").asInt
        )
    }

    @Test
    fun `decode rejects negative overflowing fractional and non numeric token ids`() {
        listOf(
            """{"ids":[-1]}""",
            """{"ids":[2147483648]}""",
            """{"ids":[1.5]}""",
            """{"ids":["15339"]}"""
        ).forEach { body ->
            assertEquals(body, 400, controller.decode("gpt-4", body).statusCode)
        }
    }

    @Test
    fun `malformed tokenizer requests return 400 instead of silent empty results`() {
        assertEquals(400, controller.encode("gpt-4", "{}").statusCode)
        assertEquals(400, controller.encode("gpt-4", "not-json").statusCode)
        assertEquals(400, controller.decode("gpt-4", "{}").statusCode)
        assertEquals(400, controller.count("gpt-4", "{}").statusCode)
        assertEquals(400, controller.count("gpt-4", """[{"role":{"nested":true}}]""").statusCode)
    }

    @Test
    fun `bias tokenizes text with shared model mapping and ignores blank text`() {
        val response = controller.bias(
            "gpt-4o-mini",
            """[{"text":"hello world","value":-50},{"text":"  ","value":10}]"""
        )

        assertEquals(200, response.statusCode)
        val body = JsonParser.parseString(response.bodyText).asJsonObject
        assertEquals(setOf("24912", "2375"), body.keySet())
        assertEquals(-50, body.get("24912").asInt)
        assertEquals(-50, body.get("2375").asInt)
    }

    @Test
    fun `bias rejects missing malformed and out of range values`() {
        listOf(
            """[{"text":"hello"}]""",
            """[{"text":"hello","value":"-50"}]""",
            """[{"text":1,"value":-50}]""",
            """[{"text":"hello","value":-101}]""",
            """[{"text":"hello","value":101}]""",
            """[{"text":" ","value":101}]""",
            """{"text":"hello","value":-50}"""
        ).forEach { body ->
            assertEquals(body, 400, controller.bias("gpt-4", body).statusCode)
        }
    }

    @Test
    fun `bias accepts inclusive boundaries and finite decimals`() {
        listOf(-100.0, 100.0, 1.5).forEach { value ->
            val response = controller.bias("gpt-4", """[{"text":"hello","value":$value}]""")
            assertEquals(value.toString(), 200, response.statusCode)
            val result = JsonParser.parseString(response.bodyText).asJsonObject
            assertEquals(value, result.get("15339").asDouble, 0.0)
        }
    }

    @Test
    fun `tokenizer request collections have endpoint specific budgets`() {
        val messages = List(8_193) { "{}" }.joinToString(prefix = "[", postfix = "]")
        val biasEntries = List(4_097) { """{"text":""}""" }.joinToString(prefix = "[", postfix = "]")
        val oversizedText = "a".repeat(2 * 1024 * 1024 + 1)

        assertEquals(413, controller.count("gpt-4", messages).statusCode)
        assertEquals(413, controller.bias("gpt-4", biasEntries).statusCode)
        assertEquals(413, controller.encode("gpt-4", """{"text":"$oversizedText"}""").statusCode)
        assertEquals(
            413,
            controller.bias("gpt-4", """[{"text":"$oversizedText","value":1}]""").statusCode
        )
    }

    @Test
    fun `native server registers openai tokenizer and bias routes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-tokenizer-routes").toFile())
        paths.webDir.mkdirs()
        paths.webDir.resolve("index.html").writeText("ok")
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val requests = listOf(
                Triple("/api/tokenizers/openai/encode?model=gpt-4", """{"text":"hello world"}""", "ids"),
                Triple("/api/tokenizers/openai/decode?model=gpt-4", """{"ids":[15339,1917]}""", "text"),
                Triple(
                    "/api/tokenizers/openai/count?model=gpt-4",
                    """[{"role":"user","content":"hello world"}]""",
                    "token_count"
                ),
                Triple(
                    "/api/backends/chat-completions/bias?model=gpt-4",
                    """[{"text":"hello world","value":-50}]""",
                    "15339"
                )
            )
            requests.forEach { (path, requestBody, responseKey) ->
                val response = postJson(server, path, requestBody)
                assertEquals(path, 200, response.first)
                assertFalse(path, response.second.contains("endpoint_not_found"))
                assertTrue(path, JsonParser.parseString(response.second).asJsonObject.has(responseKey))
            }
        } finally {
            server.stop()
        }
    }

    private fun postJson(server: NativeHttpServer, path: String, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val response = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to response
    }
}
