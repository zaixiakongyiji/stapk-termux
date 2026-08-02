package com.stapk.mobile.nativeadapter.vector

import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorRepositoryQueryTest {
    @Test
    fun `query returns descending threshold filtered top K with deterministic ties`() = fixture().use { fixture ->
        val namespace = fixture.namespace("alpha")
        fixture.repository.upsertBatch(
            namespace,
            listOf(item(30, 3), item(20, 2), item(10, 1), item(40, 4)),
            listOf(vector(1f, 0f), vector(1f, 0f), vector(0f, 1f), vector(-1f, 0f))
        )

        val result = fixture.repository.query(namespace, floatArrayOf(1f, 0f), topK = 3, threshold = -0.5f)

        assertEquals(listOf(20L, 30L, 10L), result.hashes)
        assertEquals(listOf(20L, 30L, 10L), result.metadata.map(VectorMetadata::hash))
        assertEquals(listOf(2, 3, 1), result.metadata.map(VectorMetadata::index))
        assertEquals(listOf(20L, 30L), fixture.repository.query(namespace, floatArrayOf(1f, 0f), 3, 1f).hashes)
    }

    @Test
    fun `query keeps negative similarities and validates bounds dimensions and empty collections`() = fixture().use { fixture ->
        val namespace = fixture.namespace("alpha")
        fixture.repository.upsertBatch(namespace, listOf(item(1, 1)), listOf(vector(-1f, 0f)))

        assertEquals(listOf(1L), fixture.repository.query(namespace, floatArrayOf(1f, 0f), 1, -1f).hashes)
        assertEquals(emptyList<Long>(), fixture.repository.query(fixture.namespace("empty"), floatArrayOf(1f, 0f), 1, -1f).hashes)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.repository.query(namespace, floatArrayOf(1f, 0f), 0, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.repository.query(namespace, floatArrayOf(1f, 0f), 101, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.repository.query(namespace, floatArrayOf(1f, 0f), 1, Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.repository.query(namespace, floatArrayOf(1f, 0f), 1, 1.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.repository.query(namespace, floatArrayOf(1f, 0f, 0f), 1, 0f)
        }
        Unit
    }

    @Test
    fun `multi query applies one global top K before grouping by collection`() = fixture().use { fixture ->
        val alpha = fixture.namespace("alpha")
        val beta = fixture.namespace("beta")
        fixture.repository.upsertBatch(
            alpha,
            listOf(item(1, 1), item(2, 2)),
            listOf(vector(0.8f, 0.6f), vector(0.7f, 0.71414286f))
        )
        fixture.repository.upsertBatch(
            beta,
            listOf(item(3, 1), item(4, 2)),
            listOf(vector(1f, 0f), vector(0.9f, 0.4358899f))
        )

        val result = fixture.repository.queryMulti(listOf(alpha, beta), floatArrayOf(1f, 0f), topK = 2, threshold = 0f)

        assertEquals(setOf("beta"), result.keys)
        assertEquals(listOf(3L, 4L), result.getValue("beta").hashes)
        assertEquals(listOf(3L, 4L), result.getValue("beta").metadata.map(VectorMetadata::hash))

        fixture.repository.upsertBatch(alpha, listOf(item(5, 0)), listOf(vector(1f, 0f)))
        val tied = fixture.repository.queryMulti(listOf(beta, alpha), floatArrayOf(1f, 0f), topK = 1, threshold = 0f)
        assertEquals(setOf("alpha"), tied.keys)
        assertEquals(listOf(5L), tied.getValue("alpha").hashes)
    }

    private fun item(hash: Long, index: Int): VectorItemInput = VectorItemInput(hash, "text-$hash", index)

    private fun vector(vararg values: Float): EncodedVector = VectorCodec.encodeNormalized(values)

    private fun fixture(): Fixture {
        val paths = NativeAdapterPaths(Files.createTempDirectory("vector-query-test").toFile())
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "vector-query-${System.nanoTime().toULong().toString(36)}.db"
        check(context.getDatabasePath(databaseName).parentFile?.let { it.exists() || it.mkdirs() } == true)
        val helper = VectorDatabaseHelper(context, paths, DiagnosticLogger(paths.logsDir), { 1L }, databaseName)
        return Fixture(helper, VectorRepository(helper, { 1L }))
    }

    private data class Fixture(val helper: VectorDatabaseHelper, val repository: VectorRepository) : AutoCloseable {
        override fun close() = repository.close()

        fun namespace(collectionKey: String): VectorNamespace = VectorNamespace(
            collectionKey,
            "stapk_openai_compatible",
            "a".repeat(64),
            "model",
            2
        )
    }
}
