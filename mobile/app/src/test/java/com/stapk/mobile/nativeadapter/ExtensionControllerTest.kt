package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionControllerTest {
    @Test
    fun `discovers system extensions and completes local extension lifecycle`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-controller").toFile())
        val registry = ExtensionRegistry(paths)
        var remoteCommit = "commit-a"
        val source = ExtensionSource { _, _ -> release(remoteCommit, validArchive()) }
        val controller = ExtensionController(paths, registry, source, ExtensionArchiveInstaller(paths))

        val initial = JsonParser.parseString(controller.discover().bodyText).asJsonArray
        assertTrue(initial.any { it.asJsonObject.get("name").asString == "regex" })
        assertTrue(initial.any { it.asJsonObject.get("name").asString == "memory" })

        val installed = controller.install(
            """{"url":"https://github.com/owner/Test-Extension","global":false,"branch":"main"}"""
        )
        val installBody = JsonParser.parseString(installed.bodyText).asJsonObject
        assertEquals(200, installed.statusCode)
        assertEquals("Test Extension", installBody.get("display_name").asString)
        assertEquals("Test-Extension", installBody.get("folderName").asString)
        assertEquals("/scripts/extensions/third-party/Test-Extension", installBody.get("extensionPath").asString)
        assertTrue(paths.extensionsDir.resolve("Test-Extension/index.js").isFile)

        val discovered = JsonParser.parseString(controller.discover().bodyText).asJsonArray
        assertTrue(discovered.any {
            it.asJsonObject.get("name").asString == "third-party/Test-Extension" &&
                it.asJsonObject.get("type").asString == "local"
        })
        assertEquals(
            409,
            controller.install("""{"url":"https://github.com/owner/Test-Extension","global":false}""").statusCode
        )

        val version = JsonParser.parseString(
            controller.version("""{"extensionName":"/Test-Extension","global":false}""").bodyText
        ).asJsonObject
        assertEquals("main", version.get("currentBranchName").asString)
        assertEquals("commit-a", version.get("currentCommitHash").asString)
        assertTrue(version.get("isUpToDate").asBoolean)

        remoteCommit = "commit-b"
        val updated = JsonParser.parseString(
            controller.update("""{"extensionName":"third-party/Test-Extension","global":false}""").bodyText
        ).asJsonObject
        assertFalse(updated.get("isUpToDate").asBoolean)
        assertEquals("commit-", updated.get("shortCommitHash").asString)
        assertEquals("commit-b", registry.find("Test-Extension")?.commitSha)

        assertEquals(
            200,
            controller.delete("""{"extensionName":"/Test-Extension","global":false}""").statusCode
        )
        assertFalse(paths.extensionsDir.resolve("Test-Extension").exists())
        assertEquals(null, registry.find("Test-Extension"))
        assertEquals(404, controller.delete("""{"extensionName":"/Test-Extension"}""").statusCode)
    }

    @Test
    fun `maps unsupported invalid network and archive failures to stable statuses`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-errors").toFile())
        val registry = ExtensionRegistry(paths)

        val invalid = ExtensionController(
            paths,
            registry,
            ExtensionSource { _, _ -> throw IllegalArgumentException("invalid") },
            ExtensionArchiveInstaller(paths)
        )
        assertEquals(400, invalid.install("""{"url":"https://example.com/repo"}""").statusCode)
        assertEquals(400, invalid.install("""{"url":"https://github.com/owner/repo","global":true}""").statusCode)

        val network = ExtensionController(
            paths,
            registry,
            ExtensionSource { _, _ -> throw ExtensionSourceException("offline") },
            ExtensionArchiveInstaller(paths)
        )
        assertEquals(502, network.install("""{"url":"https://github.com/owner/repo"}""").statusCode)

        val invalidArchive = ExtensionController(
            paths,
            registry,
            ExtensionSource { _, _ -> release("commit-a", zip("root/index.js" to "invalid")) },
            ExtensionArchiveInstaller(paths)
        )
        assertEquals(422, invalidArchive.install("""{"url":"https://github.com/owner/repo"}""").statusCode)
        assertEquals(400, invalid.install("{invalid").statusCode)
    }

    @Test
    fun `native server registers five extension routes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-routes").toFile())
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val base = "http://127.0.0.1:${server.listeningPort}"
            val discover = get("$base/api/extensions/discover")
            assertEquals(200, discover.first)
            assertFalse(discover.second.contains("endpoint_not_found"))

            mapOf(
                "/api/extensions/install" to """{"url":"https://github.com/owner/repo","global":true}""",
                "/api/extensions/version" to """{"extensionName":"/missing"}""",
                "/api/extensions/update" to """{"extensionName":"/missing"}""",
                "/api/extensions/delete" to """{"extensionName":"/missing"}"""
            ).forEach { (path, body) ->
                val response = postJson("$base$path", body)
                assertFalse("route not registered: $path", response.second.contains("endpoint_not_found"))
            }
        } finally {
            server.stop()
        }
    }

    private fun release(commit: String, archive: ByteArray) = ExtensionRelease(
        GitHubRepository("owner", "Test-Extension", "https://github.com/owner/Test-Extension"),
        "main",
        commit,
        archive.toResponseBody()
    )

    private fun validArchive() = zip(
        "root/manifest.json" to """{"display_name":"Test Extension","js":"index.js"}""",
        "root/index.js" to "export default true;"
    )

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun get(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        val status = connection.responseCode
        val body = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to body
    }

    private fun postJson(url: String, body: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
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
