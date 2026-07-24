package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ExtensionRegistryTest {
    @Test
    fun `registry installs updates finds and removes records atomically`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-registry").toFile())
        val registry = ExtensionRegistry(paths)
        val installed = record("ST-Prompt-Template", "zonde306", "ST-Prompt-Template", "commit-a")

        assertEquals(emptyList<ExtensionRecord>(), registry.list())
        assertNull(registry.find(installed.folderName))

        registry.install(installed)

        assertEquals(listOf(installed), registry.list())
        assertEquals(installed, registry.find(installed.folderName))
        val persisted = JsonParser.parseString(paths.extensionRegistryFile.readText()).asJsonArray.single()
        assertEquals(installed, ExtensionRecordCodec.decode(persisted.toString()))
        assertThrows(IllegalStateException::class.java) { registry.install(installed) }
        assertThrows(IllegalStateException::class.java) {
            registry.install(installed.copy(folderName = "same-repository"))
        }

        val updated = installed.copy(commitSha = "commit-b", updatedAt = 3L)
        registry.update(updated)

        assertEquals(updated, registry.find(installed.folderName))
        assertTrue(registry.remove(installed.folderName))
        assertFalse(registry.remove(installed.folderName))
        assertEquals(emptyList<ExtensionRecord>(), registry.list())
    }

    @Test
    fun `corrupt registry is quarantined and treated as empty`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-corrupt").toFile())
        paths.extensionRegistryFile.parentFile?.mkdirs()
        paths.extensionRegistryFile.writeText("[{invalid")

        val records = ExtensionRegistry(paths).list()

        assertEquals(emptyList<ExtensionRecord>(), records)
        assertFalse(paths.extensionRegistryFile.exists())
        assertTrue(
            paths.quarantineDir.walkTopDown().any {
                it.isFile && it.name == "extensions.json" && it.readText() == "[{invalid"
            }
        )
    }

    @Test
    fun `registry quarantines records that strict codec rejects`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-strict-registry").toFile())
        paths.extensionRegistryFile.parentFile?.mkdirs()
        paths.extensionRegistryFile.writeText(
            "[${ExtensionRecordCodec.encode(record("Test", "owner", "repo", "commit")).dropLast(1)},\"extra\":true}]"
        )

        assertEquals(emptyList<ExtensionRecord>(), ExtensionRegistry(paths).list())
        assertFalse(paths.extensionRegistryFile.exists())
    }

    @Test
    fun `replaceAll rejects folder and repository conflicts before writing`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-replace-conflicts").toFile())
        val writes = AtomicInteger()
        val store = AtomicFileStore.forTesting(paths.quarantineDir) { file, bytes ->
            writes.incrementAndGet()
            file.writeBytes(bytes)
        }
        val registry = ExtensionRegistry(paths, store)
        val first = record("First", "owner", "First", "commit-a")

        assertThrows(IllegalArgumentException::class.java) {
            registry.replaceAll(
                listOf(
                    first,
                    record("first", "other", "Other", "commit-b")
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.replaceAll(
                listOf(
                    first,
                    record("Second", "OWNER", "FIRST", "commit-b").copy(
                        repositoryUrl = first.repositoryUrl.uppercase()
                    )
                )
            )
        }
        assertEquals(0, writes.get())
        assertFalse(paths.extensionRegistryFile.exists())
    }

    @Test
    fun `replaceAll validates then persists the complete registry with one atomic write`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-extension-replace-all").toFile())
        val writes = AtomicInteger()
        val store = AtomicFileStore.forTesting(paths.quarantineDir) { file, bytes ->
            writes.incrementAndGet()
            file.writeBytes(bytes)
        }
        val registry = ExtensionRegistry(paths, store)
        val second = record("Second", "owner", "Second", "commit-b")
        val first = record("First", "owner", "First", "commit-a")

        registry.replaceAll(listOf(second, first))

        assertEquals(1, writes.get())
        assertEquals(listOf(first, second), registry.list())
        assertEquals(2, JsonParser.parseString(paths.extensionRegistryFile.readText()).asJsonArray.size())
    }

    private fun record(folder: String, owner: String, repository: String, commit: String) = ExtensionRecord(
        folderName = folder,
        repositoryUrl = "https://github.com/$owner/$repository",
        owner = owner,
        repository = repository,
        branch = "main",
        commitSha = commit,
        installedAt = 1L,
        updatedAt = 2L
    )
}
