package com.stapk.mobile.nativeadapter.vector

import com.google.gson.JsonParser
import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import com.stapk.mobile.nativeadapter.NativeHttpServer
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorHttpIntegrationTest {
    @Test
    fun `real HTTP vector routes persist a configured index across subsystem recreation`() {
        val provider = MockWebServer()
        val paths = NativeAdapterPaths(Files.createTempDirectory("vector-http-integration").toFile())
        val databaseName = "vector-http-${System.nanoTime()}.db"
        provider.start()
        try {
            provider.enqueue(embeddingResponse("""[{"index":0,"embedding":[1,0]}]"""))
            provider.enqueue(embeddingResponse("""[
                {"index":0,"embedding":[1,0]},
                {"index":1,"embedding":[0,1]}
            ]"""))
            provider.enqueue(embeddingResponse("""[{"index":0,"embedding":[1,0]}]"""))

            val first = startSubsystem(paths, provider, databaseName)
            try {
                assertEquals(200, post(first.server, "/api/stapk/embeddings/config/save", configBody(provider)).first)
                val embeddingTestResponse = JsonParser.parseString(post(first.server, "/api/stapk/embeddings/test", "{}").second)
                    .asJsonObject
                assertEquals(2, embeddingTestResponse.get("dimension").asInt)
                assertEquals(true, embeddingTestResponse.get("ok").asBoolean)
                assertEquals(200, post(first.server, "/api/vector/insert", insertBody()).first)
                assertEquals("[10,11]", post(first.server, "/api/vector/list", collectionBody()).second)
                val query = JsonParser.parseString(post(first.server, "/api/vector/query", queryBody()).second).asJsonObject
                assertEquals(listOf(10L), query.getAsJsonArray("hashes").map { it.asLong })
            } finally {
                first.close()
            }

            val restored = startSubsystem(paths, provider, databaseName)
            try {
                assertEquals("[10,11]", post(restored.server, "/api/vector/list", collectionBody()).second)
                assertEquals(200, post(restored.server, "/api/vector/delete", """
                    {"source":"stapk_openai_compatible","collectionId":"memory","hashes":[10]}
                """.trimIndent()).first)
                assertEquals("[11]", post(restored.server, "/api/vector/list", collectionBody()).second)
                assertEquals(200, post(restored.server, "/api/vector/purge-all", "{}").first)
                assertEquals("[]", post(restored.server, "/api/vector/list", collectionBody()).second)
            } finally {
                restored.close()
            }
        } finally {
            RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
            provider.shutdown()
        }
    }

    private fun startSubsystem(
        paths: NativeAdapterPaths,
        provider: MockWebServer,
        databaseName: String
    ): StartedSubsystem {
        val logger = DiagnosticLogger(paths.logsDir)
        val subsystem = VectorSubsystem(
            RuntimeEnvironment.getApplication(),
            paths,
            logger,
            OkHttpClient(),
            databaseName
        )
        val server = NativeHttpServer(paths, vectorRoutes = subsystem.controller)
        server.start()
        return StartedSubsystem(server, subsystem)
    }

    private fun configBody(provider: MockWebServer): String = """
        {
          "type":"stapk_openai_compatible",
          "baseUrl":"${provider.url("/v1/").toString().trimEnd('/')}",
          "model":"test-embedding",
          "apiKey":"test-key"
        }
    """.trimIndent()

    private fun insertBody(): String = """
        {
          "source":"stapk_openai_compatible",
          "collectionId":"memory",
          "items":[
            {"hash":10,"text":"alpha","index":0},
            {"hash":11,"text":"beta","index":1}
          ]
        }
    """.trimIndent()

    private fun collectionBody(): String = """
        {"source":"stapk_openai_compatible","collectionId":"memory"}
    """.trimIndent()

    private fun queryBody(): String = """
        {"source":"stapk_openai_compatible","collectionId":"memory","searchText":"alpha","topK":1}
    """.trimIndent()

    private fun embeddingResponse(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"data\":$data}")

    private fun post(server: NativeHttpServer, path: String, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        return status to stream.bufferedReader().use { it.readText() }
    }

    private data class StartedSubsystem(
        val server: NativeHttpServer,
        val subsystem: VectorSubsystem
    ) : AutoCloseable {
        override fun close() {
            server.stop()
            subsystem.close()
        }
    }
}
