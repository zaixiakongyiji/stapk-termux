package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {
    @Test
    fun `vector diagnostics retain only allowlisted metadata`() {
        val logsDir = Files.createTempDirectory("stapk-vector-diagnostics").toFile()
        val logger = DiagnosticLogger(logsDir)

        logger.event(
            DiagnosticArea.VECTOR,
            "vector_query_failed",
            mapOf(
                "host" to "example.com",
                "batchCount" to "2",
                "dimension" to "1536",
                "itemCount" to "1",
                "databaseBytes" to "0",
                "collectionSha256" to "a".repeat(64),
                "modelSha256" to "b".repeat(64),
                "text" to "private chunk",
                "prompt" to "private prompt",
                "vector" to "[0.1,0.2]",
                "apiKey" to "top-secret",
                "authorization" to "Bearer top-secret",
                "baseUrl" to "https://example.com/v1?token=secret"
            )
        )

        val text = logsDir.resolve("diagnostics.jsonl").readText()
        val event = JsonParser.parseString(text.trim()).asJsonObject
        val fields = event.getAsJsonObject("fields")
        assertEquals("VECTOR", event.get("area").asString)
        assertEquals("2", fields.get("batchCount").asString)
        assertEquals("1536", fields.get("dimension").asString)
        assertEquals("1", fields.get("itemCount").asString)
        assertEquals("0", fields.get("databaseBytes").asString)
        assertEquals("a".repeat(64), fields.get("collectionSha256").asString)
        assertEquals("b".repeat(64), fields.get("modelSha256").asString)
        assertFalse(text.contains("private chunk"))
        assertFalse(text.contains("private prompt"))
        assertFalse(text.contains("[0.1,0.2]"))
        assertFalse(text.contains("top-secret"))
        assertFalse(text.contains("token=secret"))
    }

    @Test
    fun `logger keeps only strict provider terminal metadata`() {
        val logsDir = Files.createTempDirectory("stapk-diagnostics-terminal").toFile()
        val logger = DiagnosticLogger(logsDir)

        listOf("completed", "canceled", "read_error", "arbitrary").forEach { terminal ->
            logger.event(DiagnosticArea.PROVIDER, "terminal_$terminal", mapOf("terminal" to terminal))
        }

        val terminalFields = logsDir.resolve("diagnostics.jsonl").readLines().map {
            JsonParser.parseString(it).asJsonObject.getAsJsonObject("fields")
        }
        assertEquals("completed", terminalFields[0].get("terminal").asString)
        assertEquals("canceled", terminalFields[1].get("terminal").asString)
        assertEquals("read_error", terminalFields[2].get("terminal").asString)
        assertFalse(terminalFields[3].has("terminal"))
    }

    @Test
    fun `logger keeps only strict boolean stream metadata`() {
        val logsDir = Files.createTempDirectory("stapk-diagnostics-stream").toFile()
        val logger = DiagnosticLogger(logsDir)

        logger.event(DiagnosticArea.PROVIDER, "stream_true", mapOf("stream" to "true"))
        logger.event(DiagnosticArea.PROVIDER, "stream_false", mapOf("stream" to "false"))
        logger.event(DiagnosticArea.PROVIDER, "stream_invalid", mapOf("stream" to "yes"))

        val events = logsDir.resolve("diagnostics.jsonl").readLines().map {
            JsonParser.parseString(it).asJsonObject
        }
        assertEquals("true", events[0].getAsJsonObject("fields").get("stream").asString)
        assertEquals("false", events[1].getAsJsonObject("fields").get("stream").asString)
        assertFalse(events[2].getAsJsonObject("fields").has("stream"))
    }

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
    fun `extension transaction diagnostics keep only strict metadata`() {
        val logsDir = Files.createTempDirectory("stapk-diagnostics-extension-transaction").toFile()
        val logger = DiagnosticLogger(logsDir)

        logger.event(
            DiagnosticArea.STORAGE,
            "extension_transaction_recovered",
            mapOf(
                "operation" to "update",
                "phase" to "files_activated",
                "folder" to "ST-Prompt-Template",
                "result" to "recovered",
                "errorClass" to "java.io.IOException",
                "recoveredCount" to "2",
                "quarantinedCount" to "1",
                "responseBody" to "private response body",
                "manifest" to "private manifest",
                "apiKey" to "top-secret",
                "prompt" to "private prompt"
            )
        )

        val text = logsDir.resolve("diagnostics.jsonl").readText()
        val fields = JsonParser.parseString(text.trim()).asJsonObject.getAsJsonObject("fields")
        assertEquals(
            setOf(
                "operation",
                "phase",
                "folder",
                "result",
                "errorClass",
                "recoveredCount",
                "quarantinedCount"
            ),
            fields.keySet()
        )
        assertEquals("update", fields.get("operation").asString)
        assertEquals("files_activated", fields.get("phase").asString)
        assertEquals("ST-Prompt-Template", fields.get("folder").asString)
        assertEquals("recovered", fields.get("result").asString)
        assertEquals("2", fields.get("recoveredCount").asString)
        assertEquals("1", fields.get("quarantinedCount").asString)
        assertFalse(text.contains("private response body"))
        assertFalse(text.contains("private manifest"))
        assertFalse(text.contains("top-secret"))
        assertFalse(text.contains("private prompt"))
    }

    @Test
    fun `extension source diagnostics keep only operation phase and error class`() {
        val logsDir = Files.createTempDirectory("stapk-diagnostics-extension-source").toFile()
        val logger = DiagnosticLogger(logsDir)

        logger.event(
            DiagnosticArea.HTTP,
            "extension_source_failed",
            mapOf(
                "operation" to "version",
                "phase" to "archive_download",
                "errorClass" to "java.net.SocketTimeoutException",
                "repository" to "private-owner/private-repository",
                "url" to "https://github.com/private-owner/private-repository",
                "message" to "private network details"
            )
        )

        val text = logsDir.resolve("diagnostics.jsonl").readText()
        val fields = JsonParser.parseString(text.trim()).asJsonObject.getAsJsonObject("fields")
        assertEquals(setOf("operation", "phase", "errorClass"), fields.keySet())
        assertEquals("version", fields.get("operation").asString)
        assertEquals("archive_download", fields.get("phase").asString)
        assertEquals("java.net.SocketTimeoutException", fields.get("errorClass").asString)
        assertFalse(text.contains("private-owner"))
        assertFalse(text.contains("private-repository"))
        assertFalse(text.contains("private network details"))
    }

    @Test
    fun `extension source diagnostics retain unknown phase without allowing it for transactions`() {
        val logsDir = Files.createTempDirectory("stapk-diagnostics-extension-source-unknown").toFile()
        val logger = DiagnosticLogger(logsDir)

        logger.event(
            DiagnosticArea.HTTP,
            "extension_source_failed",
            mapOf(
                "operation" to "install",
                "phase" to "unknown",
                "errorClass" to "com.stapk.mobile.nativeadapter.ExtensionSourceException"
            )
        )
        logger.event(
            DiagnosticArea.STORAGE,
            "extension_transaction_invalid",
            mapOf("operation" to "install", "phase" to "unknown")
        )

        val events = logsDir.resolve("diagnostics.jsonl").readLines().map {
            JsonParser.parseString(it).asJsonObject.getAsJsonObject("fields")
        }
        assertEquals("unknown", events[0].get("phase").asString)
        assertFalse(events[1].has("phase"))
    }

    @Test
    fun `invalid extension transaction metadata is dropped`() {
        val logsDir = Files.createTempDirectory("stapk-diagnostics-invalid-extension-transaction").toFile()
        val logger = DiagnosticLogger(logsDir)

        logger.event(
            DiagnosticArea.STORAGE,
            "extension_transaction_invalid",
            mapOf(
                "operation" to "INSTALL",
                "phase" to "unknown",
                "folder" to "../escape",
                "result" to "contains secret whitespace",
                "recoveredCount" to "-1",
                "quarantinedCount" to "not-a-number"
            )
        )

        val fields = JsonParser.parseString(logsDir.resolve("diagnostics.jsonl").readText().trim())
            .asJsonObject.getAsJsonObject("fields")
        assertEquals(0, fields.size())
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
