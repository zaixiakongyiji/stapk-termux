package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assume.assumeNoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionDirectoryQuarantineTest {
    @Test
    fun `moves the complete directory into a unique batch and writes minimal diagnostics`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-directory-quarantine").toFile())
        val source = paths.extensionsDir.resolve("Unknown-Extension").apply {
            resolve("nested").mkdirs()
            resolve("manifest.json").writeText("private manifest")
            resolve("nested/content.js").writeText("private extension body")
        }
        val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val quarantine = ExtensionDirectoryQuarantine(paths, clock = { 1_234L }, uuid = { id })

        val destination = quarantine.move(source, "unregistered_extension", "recovery")

        val batch = paths.quarantineDir.resolve("extensions/1234-$id")
        assertEquals(batch.resolve("Unknown-Extension").canonicalFile, destination.canonicalFile)
        assertFalse(source.exists())
        assertEquals("private manifest", destination.resolve("manifest.json").readText())
        assertEquals("private extension body", destination.resolve("nested/content.js").readText())
        assertTrue(batch.resolve("diagnostic.json").isFile)
        val diagnostic = JsonParser.parseString(batch.resolve("diagnostic.json").readText()).asJsonObject
        assertEquals(setOf("reason", "source", "operation", "timestamp"), diagnostic.keySet())
        assertEquals("unregistered_extension", diagnostic.get("reason").asString)
        assertEquals("Unknown-Extension", diagnostic.get("source").asString)
        assertEquals("recovery", diagnostic.get("operation").asString)
        assertEquals(1_234L, diagnostic.get("timestamp").asLong)
        assertFalse(diagnostic.toString().contains("private"))
    }

    @Test
    fun `rejects an extensions sibling symlink without moving its target`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-quarantine-sibling-link").toFile())
        val target = paths.extensionsDir.resolve("Real-Sibling").apply {
            mkdirs()
            resolve("content.js").writeText("sibling body")
        }
        val source = paths.extensionsDir.resolve("Linked-Name")
        createSymbolicLinkOrSkip(source, target)

        assertThrows(IllegalArgumentException::class.java) {
            ExtensionDirectoryQuarantine(paths).move(source, "unregistered_extension", "recovery")
        }
        assertTrue(Files.isSymbolicLink(source.toPath()))
        assertEquals("sibling body", target.resolve("content.js").readText())
        assertFalse(paths.quarantineDir.resolve("extensions").exists())
    }

    @Test
    fun `rejects an extensions symlink to an outside directory without moving its target`() {
        val filesDir = Files.createTempDirectory("stapk-quarantine-outside-link")
        val paths = NativeAdapterPaths(filesDir.resolve("app").toFile())
        val target = filesDir.resolve("outside-target").toFile().apply {
            mkdirs()
            resolve("content.js").writeText("outside body")
        }
        val source = paths.extensionsDir.resolve("Outside-Link")
        paths.extensionsDir.mkdirs()
        createSymbolicLinkOrSkip(source, target)

        assertThrows(IllegalArgumentException::class.java) {
            ExtensionDirectoryQuarantine(paths).move(source, "unregistered_extension", "recovery")
        }
        assertTrue(Files.isSymbolicLink(source.toPath()))
        assertEquals("outside body", target.resolve("content.js").readText())
        assertFalse(paths.quarantineDir.resolve("extensions").exists())
    }

    @Test
    fun `rejects a source classified as a symlink before invoking the mover`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-quarantine-link-check").toFile())
        val source = paths.extensionsDir.resolve("Linked-Name").apply { mkdirs() }
        var moved = false
        val quarantine = ExtensionDirectoryQuarantine(
            paths,
            directoryMover = { _, _ -> moved = true },
            symbolicLinkChecker = { true }
        )

        assertThrows(IllegalArgumentException::class.java) {
            quarantine.move(source, "unregistered_extension", "recovery")
        }
        assertTrue(source.isDirectory)
        assertFalse(moved)
        assertFalse(paths.quarantineDir.resolve("extensions").exists())
    }

    private fun createSymbolicLinkOrSkip(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (exception: UnsupportedOperationException) {
            assumeNoException(exception)
        } catch (exception: IOException) {
            assumeNoException(exception)
        } catch (exception: SecurityException) {
            assumeNoException(exception)
        }
    }
}
