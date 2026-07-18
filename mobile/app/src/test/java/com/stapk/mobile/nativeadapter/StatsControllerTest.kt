package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class StatsControllerTest {
    @Test
    fun `loopback server registers stats routes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-stats-routes").toFile())
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            assertEquals(200, postJson(server, "/api/stats/get", "{}").first)
            assertEquals(200, postJson(server, "/api/stats/recreate", "{}").first)
            assertEquals(200, postJson(server, "/api/stats/update", "{}").first)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `recreate aggregates valid ordinary chats and skips corrupt files with sanitized diagnostics`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-stats-recreate").toFile())
        val alice = paths.chatsDir.resolve("alice/chat.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText(
                """{"chat_metadata":{}}
{"name":"User","is_user":true,"mes":"hello two words","send_date":"2026-07-15T00:00:00Z"}
{"name":"Alice","is_user":false,"mes":"three answer words","gen_started":"2026-07-15T00:00:01Z","gen_finished":"2026-07-15T00:00:02Z","swipes":["three answer words","alternate reply"],"swipe_info":[{"gen_started":"2026-07-15T00:00:01Z","gen_finished":"2026-07-15T00:00:02Z"},{"gen_started":"2026-07-15T00:00:03Z","gen_finished":"2026-07-15T00:00:05Z"}]}"""
            )
            setLastModified(3_000L)
        }
        val broken = paths.chatsDir.resolve("bob/broken.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("not-json")
        }
        paths.chatsDir.resolve("bob/valid.jsonl").writeText(
            """{"chat_metadata":{}}
{"name":"User","is_user":true,"mes":"one"}"""
        )
        val controller = StatsController(paths)

        assertEquals(200, controller.recreateStats().statusCode)
        val stats = JsonParser.parseString(requireNotNull(controller.getStats().bodyText)).asJsonObject
        val aliceStats = stats.getAsJsonObject("alice")
        val bobStats = stats.getAsJsonObject("bob")
        val diagnostic = paths.logsDir.resolve("diagnostics.jsonl").readText()

        assertEquals(3_000L, aliceStats.get("total_gen_time").asLong)
        assertEquals(3, aliceStats.get("user_word_count").asInt)
        assertEquals(5, aliceStats.get("non_user_word_count").asInt)
        assertEquals(1, aliceStats.get("user_msg_count").asInt)
        assertEquals(2, aliceStats.get("non_user_msg_count").asInt)
        assertEquals(1, aliceStats.get("total_swipe_count").asInt)
        assertEquals(alice.length(), aliceStats.get("chat_size").asLong)
        assertEquals(alice.length(), aliceStats.get("total_chat_size").asLong)
        assertEquals(3_000L, aliceStats.get("date_last_chat").asLong)
        assertEquals(1, bobStats.get("user_msg_count").asInt)
        assertTrue(diagnostic.contains("broken.jsonl"))
        assertFalse(diagnostic.contains(requireNotNull(broken.parentFile).absolutePath))
    }

    @Test
    fun `update atomically merges character increments without dropping existing fields`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-stats-update").toFile())
        paths.statsFile.parentFile?.mkdirs()
        paths.statsFile.writeText(
            """{"alice":{"total_gen_time":1000,"user_msg_count":2,"vendor":{"keep":true}}}"""
        )
        val controller = StatsController(paths)

        val response = controller.updateStats(
            """{"alice":{"user_msg_count":3,"vendor":{"added":true}},"bob":{"user_msg_count":1}}"""
        )
        val saved = JsonParser.parseString(paths.statsFile.readText()).asJsonObject

        assertEquals(200, response.statusCode)
        assertEquals(1_000L, saved.getAsJsonObject("alice").get("total_gen_time").asLong)
        assertEquals(3, saved.getAsJsonObject("alice").get("user_msg_count").asInt)
        assertTrue(saved.getAsJsonObject("alice").getAsJsonObject("vendor").get("keep").asBoolean)
        assertTrue(saved.getAsJsonObject("alice").getAsJsonObject("vendor").get("added").asBoolean)
        assertEquals(1, saved.getAsJsonObject("bob").get("user_msg_count").asInt)
    }

    private fun postJson(server: NativeHttpServer, path: String, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val response = (if (status >= 400) connection.errorStream else connection.inputStream)
            .bufferedReader().use { it.readText() }
        return status to response
    }
}
