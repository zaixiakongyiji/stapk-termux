package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SecretStoreTest {
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
}
