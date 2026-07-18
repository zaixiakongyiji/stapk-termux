package com.stapk.mobile.nativeadapter

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionArchiveInstallerTest {
    @Test
    fun `installs a single root client extension after validating manifest assets`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-install").toFile())
        val installer = ExtensionArchiveInstaller(paths, clock = { 100L })

        val installed = installer.install(release(validArchive()))

        assertEquals("Test-Extension", installed.record.folderName)
        assertEquals("abc123", installed.record.commitSha)
        assertEquals(100L, installed.record.installedAt)
        assertTrue(installed.directory.resolve("manifest.json").isFile)
        assertEquals("export default true;", installed.directory.resolve("dist/index.js").readText())
        assertTrue(paths.extensionsDir.resolve("Test-Extension/style.css").isFile)
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

        val installed = installer.install(release(archive))

        assertTrue(installed.directory.resolve("dist/index.js").isFile)
        assertFalse(installed.directory.resolve("style.css").exists())
    }

    @Test
    fun `rejects a manifest without nonblank client assets`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-empty-assets").toFile())
        val archive = zip(
            "Test-Extension-abc123/manifest.json" to manifest(js = "", css = "")
        )

        assertThrows(InvalidExtensionArchiveException::class.java) {
            ExtensionArchiveInstaller(paths).install(release(archive))
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
                installer.install(release(archive))
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
                ExtensionArchiveInstaller(paths, limits = limits).install(release(archive))
            }
        }
    }

    @Test
    fun `restores previous extension when update activation fails`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-rollback").toFile())
        val target = paths.extensionsDir.resolve("Test-Extension").apply { mkdirs() }
        target.resolve("old.js").writeText("stable")
        val previous = ExtensionRecord(
            "Test-Extension",
            "https://github.com/owner/Test-Extension",
            "owner",
            "Test-Extension",
            "main",
            "old-commit",
            1L,
            2L
        )
        val installer = ExtensionArchiveInstaller(
            paths,
            directoryMover = { source, destination ->
                if (source.name.contains("installing") && destination.name == "Test-Extension") {
                    throw java.io.IOException("activation failed")
                }
                Files.move(source.toPath(), destination.toPath())
            }
        )

        assertThrows(InvalidExtensionArchiveException::class.java) {
            installer.install(release(validArchive()), replacing = previous)
        }

        assertEquals("stable", target.resolve("old.js").readText())
        assertFalse(target.resolve("dist/index.js").exists())
        assertFalse(paths.extensionsDir.listFiles().orEmpty().any { it.name.contains("previous") })
    }

    private fun release(archive: ByteArray) = ExtensionRelease(
        repository = GitHubRepository(
            "owner",
            "Test-Extension",
            "https://github.com/owner/Test-Extension"
        ),
        branch = "main",
        commitSha = "abc123",
        archive = archive.toResponseBody()
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
}
