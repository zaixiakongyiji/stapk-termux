package com.stapk.mobile.nativeadapter.vector

import android.database.sqlite.SQLiteFullException
import com.google.gson.JsonParser
import com.stapk.mobile.nativeadapter.AtomicFileStore
import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import com.stapk.mobile.nativeadapter.NativeHttpServer
import com.stapk.mobile.nativeadapter.SecretStore
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorControllerTest {
    @Test
    fun `native HTTP routes preserve upstream vector response shapes and never return secrets`() = fixture().use { fixture ->
        fixture.server.start()
        try {
            val saved = fixture.post(
                "/api/stapk/embeddings/config/save",
                """{"type":"stapk_openai_compatible","baseUrl":"http://127.0.0.1:8080/v1","model":"test-model","apiKey":"private-key"}"""
            )
            assertEquals(200, saved.status)
            assertFalse(saved.body.contains("private-key"))
            assertFalse(saved.body.contains("Authorization"))
            val current = fixture.json("/api/stapk/embeddings/config/get", "{}")
            assertEquals("stapk_openai_compatible", current.get("type").asString)
            assertEquals("test-model", current.get("model").asString)
            assertTrue(current.get("keyConfigured").asBoolean)
            assertFalse(current.toString().contains("private-key"))

            assertEquals(200, fixture.post("/api/vector/insert", insertRequest()).status)
            assertEquals(listOf(123L, 456L), fixture.array("/api/vector/list", vectorRequest()))

            val query = fixture.json("/api/vector/query", vectorRequest("searchText", "needle", "topK", 1))
            assertEquals(listOf(123L), query.getAsJsonArray("hashes").map { it.asLong })
            assertEquals("chunk", query.getAsJsonArray("metadata")[0].asJsonObject.get("text").asString)

            val multi = fixture.json(
                "/api/vector/query-multi",
                vectorRequest("collectionIds", listOf("collection-a"), "searchText", "needle")
            )
            assertEquals(123L, multi.getAsJsonObject("collection-a").getAsJsonArray("hashes")[0].asLong)

            assertEquals(200, fixture.post("/api/vector/delete", vectorRequest("hashes", listOf(123L))).status)
            assertEquals(200, fixture.post("/api/vector/purge", """{"collectionId":"collection-a"}""").status)
            assertEquals(200, fixture.post("/api/vector/purge-all", "{}").status)

            val tested = fixture.json("/api/stapk/embeddings/test", "{}")
            assertEquals(2, tested.get("dimension").asInt)
            assertEquals(listOf("stAPK embedding connection test"), fixture.gateway.inputs.last())
            assertTrue(fixture.store.upserts.isNotEmpty())
        } finally {
            fixture.server.stop()
        }
    }

    @Test
    fun `invalid or unsafe vector requests fail closed before provider or store`() = fixture().use { fixture ->
        fixture.server.start()
        try {
            fixture.post(
                "/api/stapk/embeddings/config/save",
                """{"type":"stapk_openai_compatible","baseUrl":"http://127.0.0.1:8080/v1","model":"test-model","apiKey":"private-key"}"""
            )
            listOf(
                """{"collectionId":"collection-a","source":"stapk_openai_compatible","items":[]}""",
                """{"collectionId":"collection-a","source":"stapk_openai_compatible","baseUrl":"https://evil.example","items":[{"hash":1,"text":"x","index":0}]}""",
                """{"collectionId":"collection-a","source":"wrong","items":[{"hash":1,"text":"x","index":0}]}""",
                """{"collectionId":"collection-a","source":"stapk_openai_compatible","items":[{"hash":1.5,"text":"x","index":0}]}"""
            ).forEach { body ->
                val response = fixture.post("/api/vector/insert", body)
                assertEquals(400, response.status)
                assertEquals("vector_invalid_request", JsonParser.parseString(response.body).asJsonObject.get("error").asString)
            }
            assertEquals(0, fixture.gateway.inputs.size)
            assertEquals(0, fixture.store.upserts.size)

            val unknown = fixture.post("/api/vector/not-real", "{}")
            assertEquals(404, unknown.status)
            assertEquals("endpoint_not_found", JsonParser.parseString(unknown.body).asJsonObject.get("error").asString)
        } finally {
            fixture.server.stop()
        }
    }

    @Test
    fun `insert normalizes gateway vectors before the atomic repository write`() = fixture().use { fixture ->
        fixture.gateway.vector = floatArrayOf(3f, 4f)
        fixture.server.start()
        try {
            fixture.post(
                "/api/stapk/embeddings/config/save",
                """{"type":"stapk_openai_compatible","baseUrl":"http://127.0.0.1:8080/v1","model":"test-model","apiKey":"private-key"}"""
            )

            assertEquals(200, fixture.post("/api/vector/insert", insertRequest()).status)
            val stored = requireNotNull(fixture.store.vectors.single().firstOrNull())
            assertEquals(0.6f, VectorCodec.decode(stored.blob, stored.dimension)[0], 0.0001f)
            assertEquals(0.8f, VectorCodec.decode(stored.blob, stored.dimension)[1], 0.0001f)
        } finally {
            fixture.server.stop()
        }
    }

    @Test
    fun `storage checks and provider failures never begin a repository write`() {
        fixture(usableSpace = { 0L }).use { fixture ->
            fixture.server.start()
            try {
                fixture.post("/api/stapk/embeddings/config/save", customConfig())
                val response = fixture.post("/api/vector/insert", insertRequest())
                assertEquals(507, response.status)
                assertEquals("vector_storage_full", JsonParser.parseString(response.body).asJsonObject.get("error").asString)
                assertTrue(fixture.gateway.inputs.isEmpty())
                assertTrue(fixture.store.upserts.isEmpty())
            } finally {
                fixture.server.stop()
            }
        }

        fixture().use { fixture ->
            fixture.gateway.failure = EmbeddingFailure(429, "embedding_rate_limited")
            fixture.server.start()
            try {
                fixture.post("/api/stapk/embeddings/config/save", customConfig())
                val response = fixture.post("/api/vector/insert", insertRequest())
                assertEquals(429, response.status)
                assertEquals("embedding_rate_limited", JsonParser.parseString(response.body).asJsonObject.get("error").asString)
                assertTrue(fixture.store.upserts.isEmpty())
            } finally {
                fixture.server.stop()
            }
        }
    }

    @Test
    fun `SQLite full during repository write maps to vector storage full`() = fixture().use { fixture ->
        fixture.store.failure = SQLiteFullException("database or disk is full")
        fixture.server.start()
        try {
            fixture.post("/api/stapk/embeddings/config/save", customConfig())

            val response = fixture.post("/api/vector/insert", insertRequest())

            assertEquals(507, response.status)
            assertEquals(
                "vector_storage_full",
                JsonParser.parseString(response.body).asJsonObject.get("error").asString
            )
        } finally {
            fixture.server.stop()
        }
    }

    @Test
    fun `explicit query bounds reject invalid values instead of silently using the repository`() = fixture().use { fixture ->
        fixture.server.start()
        try {
            fixture.post("/api/stapk/embeddings/config/save", customConfig())
            listOf(
                vectorRequest("searchText", "needle", "topK", 0),
                vectorRequest("searchText", "needle", "topK", 101),
                vectorRequest("searchText", "needle", "threshold", -0.01),
                vectorRequest("searchText", "needle", "threshold", 1.01)
            ).forEach { request ->
                val response = fixture.post("/api/vector/query", request)
                assertEquals(400, response.status)
                assertEquals("vector_invalid_request", JsonParser.parseString(response.body).asJsonObject.get("error").asString)
            }
        } finally {
            fixture.server.stop()
        }
    }

    @Test
    fun `OpenAI configuration ignores client base URL and keeps missing keys unchanged`() = fixture().use { fixture ->
        fixture.server.start()
        try {
            val first = fixture.post(
                "/api/stapk/embeddings/config/save",
                """{"type":"openai","baseUrl":"https://evil.example/v1","model":"text-embedding-3-large","apiKey":"openai-private"}"""
            )
            assertEquals(200, first.status)
            assertEquals("https://api.openai.com/v1", JsonParser.parseString(first.body).asJsonObject.get("baseUrl").asString)

            val preserved = fixture.post(
                "/api/stapk/embeddings/config/save",
                """{"type":"openai","model":"text-embedding-3-small"}"""
            )
            assertEquals(preserved.body, 200, preserved.status)
            assertTrue(JsonParser.parseString(preserved.body).asJsonObject.get("keyConfigured").asBoolean)

            val deleted = fixture.post(
                "/api/stapk/embeddings/config/save",
                """{"type":"openai","model":"text-embedding-3-small","apiKey":""}"""
            )
            assertEquals(200, deleted.status)
            assertFalse(JsonParser.parseString(deleted.body).asJsonObject.get("keyConfigured").asBoolean)
        } finally {
            fixture.server.stop()
        }
    }

    @Test
    fun `native server without vector routes keeps vector requests at 404`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("vector-routes-absent").toFile())
        val server = NativeHttpServer(paths)
        server.start()
        try {
            val response = postServer(server, "/api/vector/list", "{}")
            assertEquals(404, response.status)
            assertEquals("endpoint_not_found", JsonParser.parseString(response.body).asJsonObject.get("error").asString)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `second storage check and a blocking provider have no write side effect`() = fixture(
        usableSpace = object : () -> Long {
            private var checks = 0
            override fun invoke(): Long = if (checks++ == 0) Long.MAX_VALUE else 0L
        }
    ).use { fixture ->
        fixture.server.start()
        try {
            fixture.post("/api/stapk/embeddings/config/save", customConfig())
            val response = fixture.post("/api/vector/insert", insertRequest())
            assertEquals(507, response.status)
            assertEquals(1, fixture.gateway.inputs.size)
            assertTrue(fixture.store.upserts.isEmpty())
        } finally {
            fixture.server.stop()
        }

        fixture().use { blockedFixture ->
            blockedFixture.gateway.release = CountDownLatch(1)
            blockedFixture.server.start()
            try {
                blockedFixture.post("/api/stapk/embeddings/config/save", customConfig())
                val result = arrayOfNulls<HttpResult>(1)
                val worker = Thread { result[0] = blockedFixture.post("/api/vector/insert", insertRequest()) }
                worker.start()
                assertTrue(blockedFixture.gateway.started.await(5, TimeUnit.SECONDS))
                assertTrue(blockedFixture.store.upserts.isEmpty())
                blockedFixture.gateway.release.countDown()
                worker.join(5_000)
                assertEquals(200, requireNotNull(result[0]).status)
            } finally {
                blockedFixture.gateway.release.countDown()
                blockedFixture.server.stop()
            }
        }
    }

    @Test
    fun `all specified provider failures map to fixed error codes and input limits fail closed`() {
        fixture().use { fixture ->
            fixture.server.start()
            try {
                val response = fixture.post(
                    "/api/vector/query",
                    """{"collectionId":"collection-a","source":"openai","searchText":"needle"}"""
                )
                assertEquals(401, response.status)
                assertEquals("embedding_key_missing", JsonParser.parseString(response.body).asJsonObject.get("error").asString)
            } finally {
                fixture.server.stop()
            }
        }

        listOf(
            EmbeddingFailure(409, "vector_dimension_changed"),
            EmbeddingFailure(413, "vector_request_too_large"),
            EmbeddingFailure(422, "embedding_invalid_vector"),
            EmbeddingFailure(429, "embedding_rate_limited"),
            EmbeddingFailure(502, "embedding_provider_error"),
            EmbeddingFailure(504, "embedding_timeout")
        ).forEach { expected ->
            fixture().use { fixture ->
                fixture.gateway.failure = expected
                fixture.server.start()
                try {
                    fixture.post("/api/stapk/embeddings/config/save", customConfig())
                    val response = fixture.post("/api/vector/query", vectorRequest("searchText", "needle"))
                    assertEquals(expected.httpStatus, response.status)
                    assertEquals(expected.errorCode, JsonParser.parseString(response.body).asJsonObject.get("error").asString)
                } finally {
                    fixture.server.stop()
                }
            }
        }

        fixture().use { fixture ->
            fixture.server.start()
            try {
                fixture.post("/api/stapk/embeddings/config/save", customConfig())
                val sixtyFive = List(65) { index -> mapOf("hash" to index.toLong(), "text" to "x", "index" to index) }
                val cases = listOf(
                    vectorRequest("items", sixtyFive),
                    vectorRequest("items", listOf(mapOf("hash" to 1L, "text" to "x".repeat(100_001), "index" to 0))),
                    """{"collectionId":"bad\ncollection","source":"stapk_openai_compatible","items":[{"hash":1,"text":"x","index":0}]}""",
                    vectorRequest("collectionIds", emptyList<String>(), "searchText", "needle"),
                    vectorRequest("collectionIds", List(65) { "c$it" }, "searchText", "needle")
                )
                cases.forEachIndexed { index, body ->
                    val path = if (index < 3) "/api/vector/insert" else "/api/vector/query-multi"
                    val response = fixture.post(path, body)
                    assertEquals(400, response.status)
                    assertEquals("vector_invalid_request", JsonParser.parseString(response.body).asJsonObject.get("error").asString)
                }
            } finally {
                fixture.server.stop()
            }
        }
    }

    private fun insertRequest(): String = vectorRequest(
        "items", listOf(
            mapOf("hash" to 123L, "text" to "chunk", "index" to 2),
            mapOf("hash" to 456L, "text" to "other", "index" to 3)
        )
    )

    private fun vectorRequest(vararg fields: Any): String {
        val objectValue = com.google.gson.JsonObject().apply {
            addProperty("collectionId", "collection-a")
            addProperty("source", "stapk_openai_compatible")
            var index = 0
            while (index < fields.size) {
                val name = fields[index] as String
                val value = fields[index + 1]
                add(name, com.google.gson.Gson().toJsonTree(value))
                index += 2
            }
        }
        return objectValue.toString()
    }

    private fun customConfig(): String =
        """{"type":"stapk_openai_compatible","baseUrl":"http://127.0.0.1:8080/v1","model":"test-model","apiKey":"private-key"}"""

    private fun fixture(usableSpace: () -> Long = { Long.MAX_VALUE }): Fixture {
        val paths = NativeAdapterPaths(Files.createTempDirectory("vector-controller").toFile())
        val logger = DiagnosticLogger(paths.logsDir)
        val configStore = EmbeddingProviderConfigStore(paths, SecretStore(paths), AtomicFileStore(paths.quarantineDir, logger))
        val gateway = FakeGateway()
        val store = FakeStore()
        val controller = VectorController(configStore, gateway, store, usableSpace, logger)
        return Fixture(NativeHttpServer(paths, vectorRoutes = controller), gateway, store)
    }

    private data class Fixture(
        val server: NativeHttpServer,
        val gateway: FakeGateway,
        val store: FakeStore
    ) : AutoCloseable {
        fun post(path: String, body: String): HttpResult {
            val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val response = (if (status >= 400) connection.errorStream else connection.inputStream)
                .bufferedReader().use { it.readText() }
            connection.disconnect()
            return HttpResult(status, response)
        }

        fun json(path: String, body: String) = JsonParser.parseString(post(path, body).body).asJsonObject

        fun array(path: String, body: String) = JsonParser.parseString(post(path, body).body).asJsonArray.map { it.asLong }

        override fun close() = server.stop()
    }

    private fun postServer(server: NativeHttpServer, path: String, body: String): HttpResult {
            val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val response = (if (status >= 400) connection.errorStream else connection.inputStream)
                .bufferedReader().use { it.readText() }
            connection.disconnect()
            return HttpResult(status, response)
    }

    private data class HttpResult(val status: Int, val body: String)

    private class FakeGateway : EmbeddingGateway {
        val inputs = mutableListOf<List<String>>()
        var vector = floatArrayOf(1f, 0f)
        var failure: EmbeddingFailure? = null
        val started = CountDownLatch(1)
        var release = CountDownLatch(0)

        override fun embed(snapshot: EmbeddingProviderSnapshot, inputs: List<String>): EmbeddingBatch {
            this.inputs += inputs
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            failure?.let { throw it }
            return EmbeddingBatch(inputs.map { vector.copyOf() }, vector.size)
        }
    }

    private class FakeStore : VectorStore {
        val upserts = mutableListOf<List<VectorItemInput>>()
        val vectors = mutableListOf<List<EncodedVector>>()
        var failure: RuntimeException? = null

        override fun listHashes(namespace: VectorNamespace): List<Long> = listOf(123L, 456L)

        override fun upsertBatch(namespace: VectorNamespace, items: List<VectorItemInput>, vectors: List<EncodedVector>) {
            failure?.let { throw it }
            upserts += items
            this.vectors += vectors
        }

        override fun deleteHashes(namespace: VectorNamespace, hashes: List<Long>) = Unit

        override fun purgeCollection(collectionKey: String) = Unit

        override fun purgeAll() = Unit

        override fun query(namespace: VectorNamespace, queryVector: FloatArray, topK: Int, threshold: Float): VectorQueryResult =
            VectorQueryResult(listOf(123L), listOf(VectorMetadata(123L, "chunk", 2)))

        override fun queryMulti(
            namespaces: List<VectorNamespace>,
            queryVector: FloatArray,
            topK: Int,
            threshold: Float
        ): Map<String, VectorQueryResult> = mapOf(
            "collection-a" to VectorQueryResult(listOf(123L), listOf(VectorMetadata(123L, "chunk", 2)))
        )
    }
}
