package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicFileStoreTest {
    @Test
    fun `writes text and replaces an existing file`() {
        val userDataDir = Files.createTempDirectory("stapk-atomic").toFile()
        val target = userDataDir.resolve("settings.json")
        val store = AtomicFileStore(userDataDir.resolve("quarantine"))

        store.writeText(target, "first")
        store.writeBytes(target, "second".toByteArray())

        assertEquals("second", target.readText())
        assertFalse(requireNotNull(target.parentFile).listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `write failure keeps the previous file unchanged`() {
        val userDataDir = Files.createTempDirectory("stapk-atomic").toFile()
        val target = userDataDir.resolve("settings.json")
        target.writeText("old-value")
        val store = AtomicFileStore.forTesting(userDataDir.resolve("quarantine")) { _, _ -> throw IOException("simulated write failure") }

        try {
            store.writeText(target, "new-value")
        } catch (_: IOException) {
            // 预期写入临时文件失败。
        }

        assertEquals("old-value", target.readText())
    }

    @Test
    fun `serializer failure keeps the previous file unchanged`() {
        val userDataDir = Files.createTempDirectory("stapk-atomic").toFile()
        val target = userDataDir.resolve("settings.json")
        target.writeText("old-value")
        val store = AtomicFileStore(userDataDir.resolve("quarantine"))

        try {
            store.writeText(target) { throw IOException("simulated serialization failure") }
        } catch (_: IOException) {
            // 预期 store 内部序列化失败。
        }

        assertEquals("old-value", target.readText())
    }

    @Test
    fun `invalid JSON is quarantined below user data`() {
        val userDataDir = Files.createTempDirectory("stapk-atomic").toFile()
        val target = userDataDir.resolve("characters/broken.json")
        requireNotNull(target.parentFile).mkdirs()
        target.writeText("{broken")
        val quarantineDir = userDataDir.resolve("quarantine")
        val store = AtomicFileStore(quarantineDir)

        assertNull(store.readJsonObject(target))

        val quarantine = quarantineDir.listFiles().orEmpty().single()
        val quarantined = quarantine.resolve("characters/broken.json")
        val diagnostic = quarantine.resolve("diagnostic.json").readText()
        assertTrue(quarantined.isFile)
        assertFalse(target.exists())
        assertTrue(diagnostic.contains("invalid_json"))
        assertFalse(diagnostic.contains("{broken"))
    }

    @Test
    fun `quarantine move failure leaves source intact after diagnostic is written`() {
        val userDataDir = Files.createTempDirectory("stapk-atomic").toFile()
        val target = userDataDir.resolve("characters/broken.json")
        requireNotNull(target.parentFile).mkdirs()
        target.writeText("{broken")
        val quarantineDir = userDataDir.resolve("quarantine")
        val store = AtomicFileStore.forTestingWithQuarantineMover(quarantineDir) { _, _ ->
            throw IOException("simulated quarantine move failure")
        }

        try {
            store.readJsonObject(target)
            fail("Expected quarantine move failure")
        } catch (_: IOException) {
            // 预期诊断写入后移动源文件失败。
        }

        val batch = quarantineDir.listFiles().orEmpty().single()
        assertEquals("{broken", target.readText())
        assertTrue(batch.resolve("diagnostic.json").isFile)
        assertFalse(batch.resolve("characters/broken.json").exists())
    }

    @Test
    fun `concurrent writes leave a parseable JSON file`() {
        val userDataDir = Files.createTempDirectory("stapk-atomic").toFile()
        val target = userDataDir.resolve("state.json")
        val store = AtomicFileStore(userDataDir.resolve("quarantine"))
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)

        repeat(8) { worker ->
            executor.execute {
                ready.countDown()
                start.await()
                repeat(25) { iteration ->
                    store.writeText(target, """{"worker":$worker,"iteration":$iteration}""")
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        val parsed = JsonParser.parseString(target.readText()).asJsonObject
        assertNotNull(parsed)
        assertTrue(parsed.has("worker"))
        assertTrue(parsed.has("iteration"))
    }

    @Test
    fun `write and quarantine reject targets outside the app private root`() {
        val userDataDir = Files.createTempDirectory("stapk-atomic").toFile()
        val outside = Files.createTempFile("stapk-outside", ".json").toFile()
        val store = AtomicFileStore(userDataDir.resolve("quarantine"))

        assertRejected { store.writeText(outside, "outside") }
        assertRejected { store.quarantine(outside, "invalid_json") }
    }

    @Test
    fun `canonical symlink escape is rejected when the platform supports links`() {
        val userDataDir = Files.createTempDirectory("stapk-atomic").toFile()
        val outsideDir = Files.createTempDirectory("stapk-outside").toFile()
        val link = userDataDir.resolve("linked")
        val linked = runCatching {
            Files.createSymbolicLink(link.toPath(), outsideDir.toPath())
            true
        }.getOrDefault(false)
        assumeTrue(linked)
        val store = AtomicFileStore(userDataDir.resolve("quarantine"))

        assertRejected { store.writeText(link.resolve("escaped.json"), "outside") }
    }

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
