package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
