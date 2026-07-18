package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsControllerTest {
    @Test
    fun `summary returns only counts last error time and quarantine file count`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-diagnostics-summary").toFile())
        var now = 1_000L
        val logger = DiagnosticLogger(paths.logsDir, clock = { now })
        logger.event(DiagnosticArea.HTTP, "http_error", mapOf("status" to "500"))
        now = 2_000L
        logger.event(DiagnosticArea.PROVIDER, "provider_network_error", mapOf("host" to "api.example.com"))
        val batch = paths.quarantineDir.resolve("batch").apply { mkdirs() }
        batch.resolve("diagnostic.json").writeText("{}")
        batch.resolve("broken.json").writeText("broken")

        val response = DiagnosticsController(paths, logger, ExportStore(paths.exportsDir)).summary()

        assertEquals(200, response.statusCode)
        val body = JsonParser.parseString(response.bodyText).asJsonObject
        assertEquals(1, body.getAsJsonObject("counts").get("HTTP").asInt)
        assertEquals(1, body.getAsJsonObject("counts").get("PROVIDER").asInt)
        assertEquals(2_000L, body.get("lastErrorAt").asLong)
        assertEquals(1, body.get("quarantineFiles").asInt)
        assertEquals(setOf("counts", "lastErrorAt", "quarantineFiles"), body.keySet())
    }

    @Test
    fun `export creates a SAF ticket with only diagnostics manifest and transform metadata`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-diagnostics-export").toFile())
        val logger = DiagnosticLogger(paths.logsDir, clock = { 3_000L })
        logger.event(DiagnosticArea.STORAGE, "invalid_json", mapOf("file" to "broken.json"))
        paths.webManifestFile.parentFile?.mkdirs()
        paths.webManifestFile.writeText("{\"upstream\":{\"commit\":\"abc\"}}")
        paths.webDir.mkdirs()
        paths.webDir.resolve("transform-report.json").writeText("{\"nodeRuntime\":false}")
        paths.secretsDir.mkdirs()
        paths.secretsDir.resolve("api-key.json").writeText("top-secret")
        val store = ExportStore(paths.exportsDir)

        val response = DiagnosticsController(paths, logger, store, clock = { 4_000L }).export()

        assertEquals(200, response.statusCode)
        val token = response.headers["X-stAPK-Export-Token"]
        assertNotNull(token)
        val ticket = store.find(requireNotNull(token))
        assertEquals("stapk-diagnostics.zip", ticket?.fileName)
        assertEquals("application/zip", ticket?.mimeType)
        ZipFile(requireNotNull(ticket).file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(
                setOf(
                    "manifest.json",
                    "logs/diagnostics.jsonl",
                    "metadata/stapk-web-manifest.json",
                    "metadata/transform-report.json"
                ),
                names
            )
            val allText = names.joinToString("\n") { name ->
                zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.readText() }
            }
            assertFalse(allText.contains("top-secret"))
            assertTrue(allText.contains("invalid_json"))
            val manifest = JsonParser.parseString(
                zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
            ).asJsonObject
            assertEquals(1, manifest.get("schemaVersion").asInt)
            assertEquals(4_000L, manifest.get("createdAt").asLong)
        }
    }
}
