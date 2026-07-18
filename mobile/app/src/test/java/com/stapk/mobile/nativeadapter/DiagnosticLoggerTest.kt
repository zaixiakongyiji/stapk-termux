package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {
    @Test
    fun `logger keeps only validated allowlisted fields and never writes secrets or content`() {
        val logsDir = Files.createTempDirectory("stapk-diagnostics").toFile()
        val logger = DiagnosticLogger(logsDir, clock = { 1_234L })

        logger.event(
            DiagnosticArea.PROVIDER,
            "provider_http_error",
            mapOf(
                "method" to "POST",
                "path" to "/v1/chat/completions?api_key=secret",
                "status" to "401",
                "host" to "api.example.com",
                "durationMs" to "42",
                "errorClass" to "java.io.IOException",
                "sha256" to "a".repeat(64),
                "Authorization" to "Bearer top-secret",
                "api_key" to "top-secret",
                "prompt" to "private prompt",
                "response" to "private response"
            )
        )

        val text = logsDir.resolve("diagnostics.jsonl").readText()
        val event = JsonParser.parseString(text.trim()).asJsonObject
        val fields = event.getAsJsonObject("fields")
        assertEquals(1_234L, event.get("timestamp").asLong)
        assertEquals("PROVIDER", event.get("area").asString)
        assertEquals("provider_http_error", event.get("code").asString)
        assertEquals("/v1/chat/completions", fields.get("path").asString)
        assertEquals("api.example.com", fields.get("host").asString)
        assertFalse(text.contains("top-secret"))
        assertFalse(text.contains("private prompt"))
        assertFalse(text.contains("private response"))
        assertFalse(fields.has("Authorization"))
        assertFalse(fields.has("api_key"))
    }

    @Test
    fun `invalid allowlisted values are dropped instead of being logged`() {
        val logsDir = Files.createTempDirectory("stapk-diagnostics-invalid").toFile()
        val logger = DiagnosticLogger(logsDir)

        logger.event(
            DiagnosticArea.HTTP,
            "http_error",
            mapOf(
                "host" to "Bearer secret",
                "status" to "401 secret",
                "file" to "../settings.json",
                "sha256" to "not-a-hash"
            )
        )

        val event = JsonParser.parseString(logsDir.resolve("diagnostics.jsonl").readText().trim()).asJsonObject
        assertEquals(0, event.getAsJsonObject("fields").size())
    }

    @Test
    fun `logger rotates at size limit and retains three backups`() {
        val logsDir = Files.createTempDirectory("stapk-diagnostics-rotate").toFile()
        val logger = DiagnosticLogger(logsDir, maxBytes = 180, backupCount = 3)

        repeat(12) { index ->
            logger.event(DiagnosticArea.HTTP, "http_error", mapOf("path" to "/api/test/$index", "status" to "500"))
        }

        assertTrue(logsDir.resolve("diagnostics.jsonl").isFile)
        assertTrue(logsDir.resolve("diagnostics.1.jsonl").isFile)
        assertTrue(logsDir.resolve("diagnostics.2.jsonl").isFile)
        assertTrue(logsDir.resolve("diagnostics.3.jsonl").isFile)
        assertFalse(logsDir.resolve("diagnostics.4.jsonl").exists())
    }

    @Test
    fun `quarantine records storage hash without damaged content`() {
        val root = Files.createTempDirectory("stapk-diagnostics-quarantine").toFile()
        val paths = NativeAdapterPaths(root)
        val logger = DiagnosticLogger(paths.logsDir)
        val damaged = paths.userConfigDir.resolve("settings.json").apply {
            parentFile?.mkdirs()
            writeText("{private damaged content")
        }

        AtomicFileStore(paths.quarantineDir, logger).quarantine(damaged, "invalid_json")

        val text = paths.logsDir.resolve("diagnostics.jsonl").readText()
        val event = JsonParser.parseString(text.trim()).asJsonObject
        assertEquals("STORAGE", event.get("area").asString)
        assertEquals("invalid_json", event.get("code").asString)
        assertEquals(64, event.getAsJsonObject("fields").get("sha256").asString.length)
        assertEquals("settings.json", event.getAsJsonObject("fields").get("file").asString)
        assertFalse(text.contains("private damaged content"))
    }
}
