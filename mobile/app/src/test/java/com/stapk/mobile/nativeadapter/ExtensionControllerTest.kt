package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionControllerTest {
    @Test
    fun `discovers vector storage system extension`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-vector-extension").toFile())
        val controller = ExtensionController(
            paths,
            ExtensionRegistry(paths),
            ExtensionSource { _, _ -> error("remote source must not be called") },
            ExtensionArchiveInstaller(paths)
        )

        val discovered = JsonParser.parseString(controller.discover().bodyText).asJsonArray

        assertTrue(discovered.any { it.asJsonObject.get("name").asString == "vectors" })
    }

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
    fun `records safe source failure diagnostics for install and version`() {
        val paths = paths("source-diagnostics")
        val registry = ExtensionRegistry(paths)
        val logger = DiagnosticLogger(paths.logsDir)
        val source = ExtensionSource { _, _ ->
            throw ExtensionSourceException(
                "private-owner private-repository request failed",
                SocketTimeoutException("private network details"),
                ExtensionSourcePhase.ARCHIVE_DOWNLOAD
            )
        }
        val controller = ExtensionController(
            paths,
            registry,
            source,
            ExtensionArchiveInstaller(paths),
            coordinator(paths, registry),
            diagnosticLogger = logger
        )

        assertError(controller.install(INSTALL_BODY), 502, "extension_source_unavailable")
        registry.install(record("old"))
        assertError(controller.version(UPDATE_BODY), 502, "extension_source_unavailable")

        val text = paths.logsDir.resolve("diagnostics.jsonl").readText()
        val events = text.lineSequence().filter(String::isNotBlank).map {
            JsonParser.parseString(it).asJsonObject
        }.toList()
        assertEquals(2, events.size)
        assertEquals(listOf("install", "version"), events.map {
            it.getAsJsonObject("fields").get("operation").asString
        })
        events.forEach { event ->
            val fields = event.getAsJsonObject("fields")
            assertEquals("HTTP", event.get("area").asString)
            assertEquals("extension_source_failed", event.get("code").asString)
            assertEquals("archive_download", fields.get("phase").asString)
            assertEquals("java.net.SocketTimeoutException", fields.get("errorClass").asString)
        }
        assertFalse(text.contains("private-owner"))
        assertFalse(text.contains("private-repository"))
        assertFalse(text.contains("private network details"))
        assertFalse(text.contains("github.com"))
    }

    @Test
    fun `maps every typed mutation failure to its fixed status and error code`() {
        run {
            val paths = paths("already-installed")
            val registry = ExtensionRegistry(paths)
            val existing = record("old")
            registry.install(existing)
            seedTarget(paths, existing)
            val response = controller(paths, registry).install(INSTALL_BODY)
            assertError(response, 409, "extension_already_installed")
        }

        run {
            val paths = paths("stale-update")
            val registry = ExtensionRegistry(paths)
            val existing = record("old")
            registry.install(existing)
            seedTarget(paths, existing)
            val source = ExtensionSource { _, _ ->
                registry.update(existing.copy(commitSha = "concurrent", updatedAt = 3L))
                release("new", validArchive())
            }
            val response = controller(paths, registry, source).update(UPDATE_BODY)
            assertError(response, 409, "extension_operation_conflict")
        }

        run {
            val paths = paths("invalid-archive")
            val response = controller(
                paths,
                source = ExtensionSource { _, _ -> release("new", zip("root/index.js" to "invalid")) }
            ).install(INSTALL_BODY)
            assertError(response, 422, "invalid_extension_archive")
        }

        run {
            val paths = paths("archive-transport")
            val source = ExtensionSource { _, _ ->
                ExtensionRelease(
                    GitHubRepository("owner", "Test-Extension", "https://github.com/owner/Test-Extension"),
                    "main",
                    "new",
                    FailingResponseBody(validArchive(), 16)
                )
            }
            val response = controller(paths, source = source).install(INSTALL_BODY)
            assertError(response, 502, "extension_source_unavailable")
        }

        run {
            val paths = paths("registry-write")
            val store = AtomicFileStore.forTesting(paths.quarantineDir) { _, _ ->
                throw IOException("registry write failed")
            }
            val registry = ExtensionRegistry(paths, store)
            val response = controller(paths, registry).install(INSTALL_BODY)
            assertError(response, 500, "extension_registry_write_failed")
        }

        run {
            val paths = paths("transaction-write")
            val registry = ExtensionRegistry(paths)
            val journalStore = AtomicFileStore.forTesting(paths.quarantineDir) { _, _ ->
                throw IOException("journal write failed")
            }
            val coordinator = coordinator(
                paths,
                registry,
                journal = ExtensionTransactionJournal(paths, journalStore)
            )
            val response = controller(paths, registry, coordinator = coordinator).install(INSTALL_BODY)
            assertError(response, 500, "extension_transaction_failed")
        }

        run {
            val paths = paths("recovery-required")
            val registry = ExtensionRegistry(paths)
            val coordinator = coordinator(paths, registry).also { it.setRecoveryReady(false) }
            val controller = controller(paths, registry, coordinator = coordinator)
            assertError(controller.install(INSTALL_BODY), 503, "extension_recovery_required")
            assertError(controller.update(UPDATE_BODY), 503, "extension_recovery_required")
            assertError(controller.delete(UPDATE_BODY), 503, "extension_recovery_required")
        }
    }

    @Test
    fun `update and delete wait for committed cleanup then return recovery required instead of not found`() {
        listOf("update", "delete").forEach { endpoint ->
            val paths = paths("committed-cleanup-$endpoint")
            val registry = ExtensionRegistry(paths)
            val existing = record("old")
            registry.install(existing)
            seedTarget(paths, existing)
            val cleanupEntered = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val deletingCoordinator = ExtensionMutationCoordinator(
                paths,
                registry,
                ExtensionTransactionJournal(paths),
                ExtensionDirectoryQuarantine(paths),
                directoryRemover = { file ->
                    if (file.name.endsWith(".trash")) {
                        cleanupEntered.countDown()
                        assertTrue(releaseCleanup.await(5, TimeUnit.SECONDS))
                        false
                    } else {
                        file.deleteRecursively()
                    }
                }
            )
            val observingCoordinator = coordinator(paths, registry)
            val controller = controller(
                paths,
                registry,
                source = ExtensionSource { _, _ -> error("source must not be resolved") },
                coordinator = observingCoordinator
            )
            val executor = Executors.newFixedThreadPool(2)
            try {
                val deletion = executor.submit<Boolean> { deletingCoordinator.delete(existing) }
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
                val requestFinished = CountDownLatch(1)
                val response = executor.submit<HttpResponse> {
                    val result = if (endpoint == "update") {
                        controller.update(UPDATE_BODY)
                    } else {
                        controller.delete(UPDATE_BODY)
                    }
                    requestFinished.countDown()
                    result
                }

                val completedWhileCleanupHeldLock = requestFinished.await(300, TimeUnit.MILLISECONDS)
                releaseCleanup.countDown()
                assertTrue(deletion.get(5, TimeUnit.SECONDS))
                assertFalse(completedWhileCleanupHeldLock)
                assertError(response.get(5, TimeUnit.SECONDS), 503, "extension_recovery_required")
                assertEquals(null, registry.find(existing.folderName))
                assertTrue(paths.extensionTransactionFile.isFile)
                assertThrows(ExtensionRecoveryRequiredException::class.java) {
                    observingCoordinator.requireRecoveryReady()
                }
            } finally {
                releaseCleanup.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `update and delete return not found after locked lookup confirms missing record`() {
        val paths = paths("missing-record")
        val controller = controller(paths)

        assertError(controller.update(UPDATE_BODY), 404, "extension_not_found")
        assertError(controller.delete(UPDATE_BODY), 404, "extension_not_found")
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

    @Test
    fun `native server creates extension subsystem synchronously and injects its routes`() {
        val paths = paths("subsystem-factory")
        var factoryCalls = 0
        val routes = object : ExtensionRoutes {
            override fun discover() = HttpResponse.json(200, "{\"source\":\"factory\"}")
            override fun install(body: String) = HttpResponse.json(200, "{}")
            override fun version(body: String) = HttpResponse.json(200, "{}")
            override fun update(body: String) = HttpResponse.json(200, "{}")
            override fun delete(body: String) = HttpResponse.json(200, "{}")
        }
        val server = NativeHttpServer(paths, extensionSubsystemFactory = { suppliedPaths, _, _ ->
            assertEquals(paths, suppliedPaths)
            factoryCalls += 1
            ExtensionSubsystem(routes, ExtensionRecoveryResult(true, 0, 0))
        })
        assertEquals(1, factoryCalls)

        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            assertEquals(
                200 to "{\"source\":\"factory\"}",
                get("http://127.0.0.1:${server.listeningPort}/api/extensions/discover")
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `native server retains the public four parameter JVM constructor`() {
        val constructor = NativeHttpServer::class.java.getConstructor(
            NativeAdapterPaths::class.java,
            Int::class.javaPrimitiveType,
            ExportStore::class.java,
            DiagnosticLogger::class.java
        )

        assertTrue(java.lang.reflect.Modifier.isPublic(constructor.modifiers))
    }

    @Test
    fun `real extension routes keep read endpoints available when recovery is not ready`() {
        val paths = paths("recovery-not-ready-http")
        val registry = ExtensionRegistry(paths)
        val extension = record("current")
        registry.install(extension)
        var sourceUnavailable = false
        val coordinator = coordinator(paths, registry).also { it.setRecoveryReady(false) }
        val routes = ExtensionController(
            paths,
            registry,
            ExtensionSource { _, _ ->
                if (sourceUnavailable) throw ExtensionSourceException("offline")
                release("current", validArchive())
            },
            ExtensionArchiveInstaller(paths),
            coordinator
        )
        val server = NativeHttpServer(paths, extensionSubsystemFactory = { _, _, _ ->
            ExtensionSubsystem(routes, ExtensionRecoveryResult(false, 0, 0))
        })

        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val base = "http://127.0.0.1:${server.listeningPort}"
            assertEquals(200, get("$base/api/extensions/discover").first)
            assertEquals(200, postJson("$base/api/extensions/version", UPDATE_BODY).first)
            assertErrorResponse(
                postJson("$base/api/extensions/version", """{"extensionName":"/missing"}"""),
                404,
                "extension_not_found"
            )
            sourceUnavailable = true
            assertErrorResponse(postJson("$base/api/extensions/version", UPDATE_BODY), 502, "extension_source_unavailable")
            assertErrorResponse(postJson("$base/api/extensions/install", INSTALL_BODY), 503, "extension_recovery_required")
            assertErrorResponse(postJson("$base/api/extensions/update", UPDATE_BODY), 503, "extension_recovery_required")
            assertErrorResponse(postJson("$base/api/extensions/delete", UPDATE_BODY), 503, "extension_recovery_required")
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

    private fun paths(name: String) =
        NativeAdapterPaths(Files.createTempDirectory("stapk-extension-controller-$name").toFile())

    private fun record(commit: String) = ExtensionRecord(
        "Test-Extension",
        "https://github.com/owner/Test-Extension",
        "owner",
        "Test-Extension",
        "main",
        commit,
        1L,
        2L
    )

    private fun seedTarget(paths: NativeAdapterPaths, record: ExtensionRecord) {
        paths.extensionsDir.resolve(record.folderName).apply {
            mkdirs()
            resolve("manifest.json").writeText("""{"display_name":"Test Extension","js":"index.js"}""")
            resolve("index.js").writeText(record.commitSha)
            resolve(".stapk-extension.json").writeText(ExtensionRecordCodec.encode(record))
        }
    }

    private fun coordinator(
        paths: NativeAdapterPaths,
        registry: ExtensionRegistry,
        journal: ExtensionTransactionJournal = ExtensionTransactionJournal(paths)
    ) = ExtensionMutationCoordinator(
        paths,
        registry,
        journal,
        ExtensionDirectoryQuarantine(paths)
    )

    private fun controller(
        paths: NativeAdapterPaths,
        registry: ExtensionRegistry = ExtensionRegistry(paths),
        source: ExtensionSource = ExtensionSource { _, _ -> release("new", validArchive()) },
        coordinator: ExtensionMutationCoordinator = coordinator(paths, registry)
    ) = ExtensionController(
        paths,
        registry,
        source,
        ExtensionArchiveInstaller(paths),
        coordinator
    )

    private fun assertError(response: HttpResponse, status: Int, code: String) {
        assertEquals(status, response.statusCode)
        assertEquals("""{"error":"$code"}""", response.bodyText)
    }

    private fun assertErrorResponse(response: Pair<Int, String>, status: Int, code: String) {
        assertEquals(status, response.first)
        assertEquals("""{"error":"$code"}""", response.second)
    }

    private class FailingResponseBody(bytes: ByteArray, private val failAfter: Int) : ResponseBody() {
        private val source = object : Source {
            private var offset = 0

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (offset >= failAfter) throw IOException("network interrupted")
                val count = minOf(byteCount.toInt(), failAfter - offset, bytes.size - offset)
                if (count <= 0) return -1L
                sink.write(bytes, offset, count)
                offset += count
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = source
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

    private companion object {
        const val INSTALL_BODY =
            """{"url":"https://github.com/owner/Test-Extension","global":false,"branch":"main"}"""
        const val UPDATE_BODY = """{"extensionName":"third-party/Test-Extension","global":false}"""
    }
}
