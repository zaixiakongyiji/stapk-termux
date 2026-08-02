package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SecretStoreTest {
    @Test
    fun `embedding key is supported and always redacted in read state`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-embedding-secret").toFile())
        val store = SecretStore(paths)

        store.write("api_key_embedding", "top-secret", "Embedding")

        assertEquals("top-secret", store.load("api_key_embedding")?.value)
        val state = JsonParser.parseString(store.readStateJson()).asJsonObject
        assertEquals("********", state["api_key_embedding"].asJsonArray[0].asJsonObject["value"].asString)
        assertFalse(store.readStateJson().contains("top-secret"))
    }

    @Test
    fun `writes masked history with a single active secret`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-secret").toFile())
        val store = SecretStore(paths)

        val firstId = store.write("api_key_openai", "sk-first", "First")!!
        val secondId = store.write("api_key_openai", "sk-second", "Second")!!
        val records = JsonParser.parseString(store.readStateJson()).asJsonObject
            .getAsJsonArray("api_key_openai")

        assertFalse(firstId == secondId)
        assertEquals(2, records.size())
        assertEquals("First", records[0].asJsonObject.get("label").asString)
        assertFalse(records[0].asJsonObject.get("active").asBoolean)
        assertEquals("Second", records[1].asJsonObject.get("label").asString)
        assertTrue(records[1].asJsonObject.get("active").asBoolean)
        records.forEach { record -> assertEquals("********", record.asJsonObject.get("value").asString) }
        assertEquals("sk-second", store.load("api_key_openai")?.value)
        assertFalse(store.readStateJson().contains("sk-first"))
        assertFalse(paths.settingsFile.exists())
    }

    @Test
    fun `delete by id reactivates the remaining record and supports legacy object data`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-secret").toFile())
        paths.secretsDir.mkdirs()
        File(paths.secretsDir, "openai-compatible.json").writeText(
            """{"api_key_openai":{"id":"legacy","label":"Legacy","value":"sk-legacy"}}"""
        )
        val store = SecretStore(paths)

        val replacementId = store.write("api_key_openai", "sk-replacement", "Replacement")!!
        assertEquals("sk-replacement", store.load("api_key_openai")?.value)
        assertTrue(store.delete("api_key_openai", replacementId))

        val records = JsonParser.parseString(store.readStateJson()).asJsonObject
            .getAsJsonArray("api_key_openai")
        assertEquals(1, records.size())
        assertEquals("legacy", records[0].asJsonObject.get("id").asString)
        assertTrue(records[0].asJsonObject.get("active").asBoolean)
        assertEquals("sk-legacy", store.load("api_key_openai")?.value)
        assertTrue(store.delete("api_key_openai", null))
        assertNull(store.load("api_key_openai"))
    }

    @Test
    fun `separate store instances preserve concurrent updates`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-secret-concurrent").toFile())
        val first = SecretStore(paths)
        val second = SecretStore(paths)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        executor.execute {
            ready.countDown()
            start.await()
            first.write("api_key_openai", "first-key", "First")
        }
        executor.execute {
            ready.countDown()
            start.await()
            second.write("api_key_embedding", "second-key", "Second")
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        val verifier = SecretStore(paths)
        assertEquals("first-key", verifier.load("api_key_openai")?.value)
        assertEquals("second-key", verifier.load("api_key_embedding")?.value)
    }
}
