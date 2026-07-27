package com.stapk.mobile.nativeadapter

import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType
import okhttp3.ResponseBody
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
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionArchiveInstallerTest {
    @Test
    fun `prepare leaves target untouched writes a strict sidecar and close is idempotent`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-prepare").toFile())
        val archive = TrackingResponseBody(validArchive())
        val prepared = ExtensionArchiveInstaller(paths, clock = { 100L }).prepare(release(archive))

        assertFalse(paths.extensionsDir.resolve("Test-Extension").exists())
        assertTrue(prepared.stagingDirectory.name.matches(Regex("\\.stapk-txn-[0-9a-f-]+\\.installing")))
        assertEquals(prepared.record, ExtensionRecordCodec.decode(
            prepared.stagingDirectory.resolve(".stapk-extension.json").readText(Charsets.UTF_8)
        ))
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionRecordCodec.decode("""{"folderName":"Test-Extension"}""")
        }
        assertTrue(archive.closed)

        prepared.close()
        prepared.close()
        assertFalse(prepared.stagingDirectory.exists())
    }

    @Test
    fun `prepare closes the response body for repository mismatch and static validation failures`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-closed-failures").toFile())
        val existing = ExtensionRecord(
            "Test-Extension", "https://github.com/owner/Test-Extension", "owner", "Test-Extension",
            "main", "old", 1L, 1L
        )
        val mismatch = TrackingResponseBody(validArchive())
        assertThrows(InvalidExtensionArchiveException::class.java) {
            ExtensionArchiveInstaller(paths).prepare(release(mismatch, repository = "Other-Extension"), existing)
        }
        assertTrue(mismatch.closed)

        val invalid = TrackingResponseBody(zip("root/manifest.json" to manifest(js = "missing.js")))
        assertThrows(InvalidExtensionArchiveException::class.java) {
            ExtensionArchiveInstaller(paths).prepare(release(invalid))
        }
        assertTrue(invalid.closed)
        assertTrue(paths.extensionsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `prepare rejects archive supplied sidecars and cleans staging`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-reserved-sidecar").toFile())
        val archive = TrackingResponseBody(zip(
            "root/.stapk-extension.json" to "forged",
            "root/manifest.json" to manifest(),
            "root/dist/index.js" to "export default true;",
            "root/style.css" to "body {}"
        ))

        assertThrows(InvalidExtensionArchiveException::class.java) {
            ExtensionArchiveInstaller(paths).prepare(release(archive))
        }
        assertTrue(archive.closed)
        assertTrue(paths.extensionsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `prepare rejects path depth 25 invalid requires and invalid i18n`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-manifest-validation").toFile())
        val deepPath = (1..25).joinToString("/") { "dir$it" } + "/index.js"
        val cases = listOf(
            zip("root/manifest.json" to """{"js":"$deepPath"}""", "root/$deepPath" to "x"),
            zip("root/manifest.json" to """{"js":"index.js","requires":"module"}""", "root/index.js" to "x"),
            zip("root/manifest.json" to """{"js":"index.js","requires":[1]}""", "root/index.js" to "x"),
            zip("root/manifest.json" to """{"js":"index.js","requires":["module"]}""", "root/index.js" to "x"),
            zip("root/manifest.json" to """{"js":"index.js","i18n":"locale.json"}""", "root/index.js" to "x"),
            zip("root/manifest.json" to """{"js":"index.js","i18n":{"en":""}}""", "root/index.js" to "x"),
            zip("root/manifest.json" to """{"js":"index.js","i18n":{"en":"../locale.json"}}""", "root/index.js" to "x"),
            zip("root/manifest.json" to """{"js":"index.js","i18n":{"en":"locale.json"}}""", "root/index.js" to "x")
        )

        cases.forEach { archive ->
            assertThrows(InvalidExtensionArchiveException::class.java) {
                ExtensionArchiveInstaller(paths).prepare(release(archive))
            }
        }
        assertTrue(paths.extensionsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `prepare rejects whitespace only requires module`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-requires-whitespace").toFile())
        val archive = zip(
            "root/manifest.json" to """{"js":"index.js","requires":[" "]}""",
            "root/index.js" to "export default true;"
        )

        assertThrows(InvalidExtensionArchiveException::class.java) {
            ExtensionArchiveInstaller(paths).prepare(release(archive))
        }
    }

    @Test
    fun `prepare accepts empty requires and existing locale files`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-locale").toFile())
        val archive = zip(
            "root/manifest.json" to """{"js":"index.js","requires":[],"i18n":{"en":"locales/en.json"}}""",
            "root/index.js" to "export default true;",
            "root/locales/en.json" to "{}"
        )

        ExtensionArchiveInstaller(paths).prepare(release(archive)).use { prepared ->
            assertTrue(prepared.stagingDirectory.resolve("locales/en.json").isFile)
        }
    }

    @Test
    fun `prepare maps archive body IOException to a source exception and closes it`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-transport").toFile())
        val archive = FailingResponseBody(validArchive(), 16)

        val failure = assertThrows(ExtensionArchiveTransportException::class.java) {
            ExtensionArchiveInstaller(paths).prepare(release(archive))
        }

        assertTrue(failure is ExtensionSourceException)
        assertEquals(ExtensionSourcePhase.ARCHIVE_READ, failure.phase)
        assertTrue(archive.closed)
        assertTrue(paths.extensionsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `prepares a single root client extension after validating manifest assets`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-install").toFile())
        val installer = ExtensionArchiveInstaller(paths, clock = { 100L })

        installer.prepare(release(validArchive())).use { prepared ->
            assertEquals("Test-Extension", prepared.record.folderName)
            assertEquals("abc123", prepared.record.commitSha)
            assertEquals(100L, prepared.record.installedAt)
            assertTrue(prepared.stagingDirectory.resolve("manifest.json").isFile)
            assertEquals("export default true;", prepared.stagingDirectory.resolve("dist/index.js").readText())
            assertTrue(prepared.stagingDirectory.resolve("style.css").isFile)
        }
        assertFalse(paths.extensionsDir.listFiles().orEmpty().any { it.name.contains("installing") })
    }

    @Test
    fun `accepts an empty optional css path when a valid js asset exists`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-empty-css").toFile())
        val installer = ExtensionArchiveInstaller(paths)
        val archive = zip(
            "Test-Extension-abc123/manifest.json" to manifest(css = ""),
            "Test-Extension-abc123/dist/index.js" to "export default true;"
        )

        installer.prepare(release(archive)).use { prepared ->
            assertTrue(prepared.stagingDirectory.resolve("dist/index.js").isFile)
            assertFalse(prepared.stagingDirectory.resolve("style.css").exists())
        }
    }

    @Test
    fun `rejects a manifest without nonblank client assets`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-empty-assets").toFile())
        val archive = zip(
            "Test-Extension-abc123/manifest.json" to manifest(js = "", css = "")
        )

        assertThrows(InvalidExtensionArchiveException::class.java) {
            ExtensionArchiveInstaller(paths).prepare(release(archive))
        }
    }

    @Test
    fun `rejects malformed roots unsafe paths and manifest asset escapes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-invalid").toFile())
        val installer = ExtensionArchiveInstaller(paths)
        val cases = listOf(
            zip("root/index.js" to "export default true;") ,
            zip("../escape.txt" to "escape", "root/manifest.json" to manifest()),
            zip("/absolute.txt" to "escape", "root/manifest.json" to manifest()),
            zip("one/manifest.json" to manifest(), "two/index.js" to "export default true;"),
            zip(
                "root/manifest.json" to manifest(js = "../outside.js"),
                "root/outside.js" to "export default true;"
            )
        )

        cases.forEach { archive ->
            assertThrows(InvalidExtensionArchiveException::class.java) {
                installer.prepare(release(archive))
            }
        }
        assertTrue(paths.extensionsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `enforces entry single file and expanded total limits`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-limits").toFile())
        val tooMany = zip(
            "root/manifest.json" to """{"js":"a.js"}""",
            "root/a.js" to "a",
            "root/b.js" to "b",
            "root/c.js" to "c"
        )
        val tooLarge = zip(
            "root/manifest.json" to """{"js":"a.js"}""",
            "root/a.js" to "12345678901234567"
        )
        val tooLargeTotal = zip(
            "root/manifest.json" to """{"js":"a.js"}""",
            "root/a.js" to "12345678",
            "root/b.js" to "12345678"
        )

        listOf(
            ExtensionArchiveLimits(maxEntries = 3, maxSingleFileBytes = 1024, maxExpandedBytes = 2048) to tooMany,
            ExtensionArchiveLimits(maxEntries = 10, maxSingleFileBytes = 16, maxExpandedBytes = 2048) to tooLarge,
            ExtensionArchiveLimits(maxEntries = 10, maxSingleFileBytes = 1024, maxExpandedBytes = 20) to tooLargeTotal
        ).forEach { (limits, archive) ->
            assertThrows(InvalidExtensionArchiveException::class.java) {
                ExtensionArchiveInstaller(paths, limits = limits).prepare(release(archive))
            }
        }
    }

    private fun release(archive: ByteArray) = release(archive.toResponseBody())

    private fun release(archive: ResponseBody, repository: String = "Test-Extension") = ExtensionRelease(
        repository = GitHubRepository(
            "owner",
            repository,
            "https://github.com/owner/$repository"
        ),
        branch = "main",
        commitSha = "abc123",
        archive = archive
    )

    private fun validArchive(): ByteArray = zip(
        "Test-Extension-abc123/manifest.json" to manifest(),
        "Test-Extension-abc123/dist/index.js" to "export default true;",
        "Test-Extension-abc123/style.css" to "body { color: red; }"
    )

    private fun manifest(js: String = "dist/index.js", css: String = "style.css"): String =
        """{"display_name":"Test","js":"$js","css":"$css"}"""

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

    private class TrackingResponseBody(bytes: ByteArray) : ResponseBody() {
        private val source = Buffer().write(bytes)
        var closed = false

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = source.size
        override fun source(): BufferedSource = source
        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FailingResponseBody(bytes: ByteArray, private val failAfter: Int) : ResponseBody() {
        private val source = object : Source {
            private var offset = 0
            private var closed = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (offset >= failAfter) throw IOException("network interrupted")
                val count = minOf(byteCount.toInt(), failAfter - offset, bytes.size - offset)
                if (count <= 0) return -1L
                sink.write(bytes, offset, count)
                offset += count
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        var closed = false

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = source
        override fun close() {
            closed = true
            super.close()
        }
    }
}
