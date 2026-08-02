package com.stapk.mobile.nativeadapter.vector

import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorRepositoryRecoveryTest {
    @Test
    fun `concurrent insert and purge leave either a complete batch or an empty collection`() = fixture().use { fixture ->
        val namespace = fixture.namespace()
        repeat(20) {
            fixture.repository.purgeAll()
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val insert = pool.submit<Unit> {
                    start.await()
                    try {
                        fixture.repository.upsertBatch(
                            namespace,
                            listOf(item(1), item(2)),
                            listOf(vector(1f, 0f), vector(0f, 1f))
                        )
                    } finally {
                        done.countDown()
                    }
                }
                val purge = pool.submit<Unit> {
                    start.await()
                    try {
                        fixture.repository.purgeCollection(namespace.collectionKey)
                    } finally {
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue(done.await(10, TimeUnit.SECONDS))
                insert.get(10, TimeUnit.SECONDS)
                purge.get(10, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
            assertTrue(fixture.repository.listHashes(namespace) in setOf(emptyList(), listOf(1L, 2L)))
        }
    }

    @Test
    fun `corrupt database is quarantined recreated once and reports explicit rebuild requirement`() {
        val root = Files.createTempDirectory("vector-recovery-test").toFile()
        val paths = NativeAdapterPaths(root)
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "vector-store.db"
        context.deleteDatabase(databaseName)
        val database = context.getDatabasePath(databaseName)
        check(database.parentFile?.let { it.exists() || it.mkdirs() } == true)
        database.writeBytes("not a sqlite database and not user text".toByteArray())
        database.resolveSibling("${database.name}-wal").writeBytes(byteArrayOf(1, 2, 3))
        database.resolveSibling("${database.name}-shm").writeBytes(byteArrayOf(4, 5, 6))
        val helper = VectorDatabaseHelper(context, paths, DiagnosticLogger(paths.logsDir), { 123L }, databaseName)
        val triggerRecovery = AtomicBoolean(true)
        val repository = VectorRepository(helper, { 123L }, corruptionForTesting = {
            if (triggerRecovery.compareAndSet(true, false)) {
                android.database.sqlite.SQLiteDatabaseCorruptException("fixture")
            } else {
                null
            }
        })
        val namespace = VectorNamespace("collection", "stapk_openai_compatible", "a".repeat(64), "model", 2)

        val failure = assertThrows(EmbeddingFailure::class.java) { repository.listHashes(namespace) }

        assertEquals(409, failure.httpStatus)
        assertEquals("vector_index_rebuild_required", failure.errorCode)
        val quarantine = paths.quarantineDir.resolve("vector-store-123")
        assertTrue(quarantine.resolve(database.name).isFile)
        assertTrue(quarantine.resolve("${database.name}-wal").isFile)
        assertTrue(quarantine.resolve("${database.name}-shm").isFile)
        assertEquals(emptyList<Long>(), repository.listHashes(namespace))
        assertTrue(paths.logsDir.walkTopDown().filter { it.isFile }.none { it.readText().contains("not a sqlite database") })
        repository.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `failed corruption recovery reports controlled store corruption`() {
        val root = Files.createTempDirectory("vector-recovery-failure-test").toFile()
        val paths = NativeAdapterPaths(root)
        paths.quarantineDir.writeText("not a directory")
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "vector-store-failure-${System.nanoTime().toULong().toString(36)}.db"
        context.deleteDatabase(databaseName)
        val helper = VectorDatabaseHelper(context, paths, DiagnosticLogger(paths.logsDir), { 124L }, databaseName)
        val recoveryOnce = AtomicBoolean(true)
        val repository = VectorRepository(helper, { 124L }, corruptionForTesting = {
            if (recoveryOnce.compareAndSet(true, false)) android.database.sqlite.SQLiteDatabaseCorruptException("fixture") else null
        })
        val namespace = VectorNamespace("collection", "stapk_openai_compatible", "a".repeat(64), "model", 2)

        val failure = assertThrows(EmbeddingFailure::class.java) { repository.listHashes(namespace) }

        assertEquals(500, failure.httpStatus)
        assertEquals("vector_store_corrupt", failure.errorCode)
        repository.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `successful recovery remains rebuild required when diagnostic log cannot be written`() {
        val root = Files.createTempDirectory("vector-recovery-log-failure-test").toFile()
        val paths = NativeAdapterPaths(root)
        paths.logsDir.writeText("not a directory")
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "vector-store-log-${System.nanoTime().toULong().toString(36)}.db"
        context.deleteDatabase(databaseName)
        val helper = VectorDatabaseHelper(context, paths, DiagnosticLogger(paths.logsDir), { 125L }, databaseName)
        val recoveryOnce = AtomicBoolean(true)
        val repository = VectorRepository(helper, { 125L }, corruptionForTesting = {
            if (recoveryOnce.compareAndSet(true, false)) android.database.sqlite.SQLiteDatabaseCorruptException("fixture") else null
        })
        val namespace = VectorNamespace("collection", "stapk_openai_compatible", "a".repeat(64), "model", 2)

        val failure = assertThrows(EmbeddingFailure::class.java) { repository.listHashes(namespace) }

        assertEquals(409, failure.httpStatus)
        assertEquals("vector_index_rebuild_required", failure.errorCode)
        repository.close()
        context.deleteDatabase(databaseName)
    }

    private fun item(hash: Long): VectorItemInput = VectorItemInput(hash, "text-$hash", hash.toInt())

    private fun vector(vararg values: Float): EncodedVector = VectorCodec.encodeNormalized(values)

    private fun fixture(): Fixture {
        val paths = NativeAdapterPaths(Files.createTempDirectory("vector-concurrency-test").toFile())
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "vector-concurrency-${System.nanoTime().toULong().toString(36)}.db"
        check(context.getDatabasePath(databaseName).parentFile?.let { it.exists() || it.mkdirs() } == true)
        val helper = VectorDatabaseHelper(context, paths, DiagnosticLogger(paths.logsDir), { 1L }, databaseName)
        return Fixture(helper, VectorRepository(helper, { 1L }))
    }

    private data class Fixture(val helper: VectorDatabaseHelper, val repository: VectorRepository) : AutoCloseable {
        override fun close() = repository.close()

        fun namespace(): VectorNamespace = VectorNamespace(
            "collection",
            "stapk_openai_compatible",
            "a".repeat(64),
            "model",
            2
        )
    }
}
