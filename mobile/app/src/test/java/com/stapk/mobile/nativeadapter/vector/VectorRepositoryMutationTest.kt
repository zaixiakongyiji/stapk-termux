package com.stapk.mobile.nativeadapter.vector

import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorRepositoryMutationTest {
    @Test
    fun `creates collection upserts hashes in stable order and replaces item content`() = fixture().use { fixture ->
        val namespace = fixture.namespace()
        fixture.repository.upsertBatch(
            namespace,
            listOf(item(9, "old", 1), item(2, "second", 2)),
            listOf(vector(3f, 4f), vector(0f, 1f))
        )
        fixture.repository.upsertBatch(namespace, listOf(item(9, "new", 7)), listOf(vector(1f, 0f)))

        assertEquals(listOf(2L, 9L), fixture.repository.listHashes(namespace))
        fixture.helper.writableDatabase.rawQuery(
            "SELECT text, item_index, vector_blob, updated_at FROM vector_items WHERE content_hash = 9",
            null
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("new", cursor.getString(0))
            assertEquals(7, cursor.getInt(1))
            assertArrayEquals(vector(1f, 0f).blob, cursor.getBlob(2))
            assertEquals(101L, cursor.getLong(3))
        }
    }

    @Test
    fun `failed batch leaves no collection or partial item`() = fixture().use { fixture ->
        val namespace = fixture.namespace(3)
        val bad = EncodedVector(3, byteArrayOf(1, 2, 3, 4))

        assertThrows(IllegalArgumentException::class.java) {
            fixture.repository.upsertBatch(namespace, listOf(item(1), item(2)), listOf(vector3(), bad))
        }

        assertEquals(emptyList<Long>(), fixture.repository.listHashes(namespace))
        assertEquals(0, fixture.helper.writableDatabase.rawQuery("SELECT count(*) FROM vector_collections", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        })
    }

    @Test
    fun `database failure after first item rolls back collection and every item`() = fixture { database, collectionId, itemPosition ->
        if (itemPosition == 1) {
            assertEquals(1, database.rawQuery(
                "SELECT count(*) FROM vector_items WHERE collection_id = ?",
                arrayOf(collectionId.toString())
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
            database.execSQL(
                """
                INSERT INTO vector_items(collection_id, content_hash, item_index, text, vector_blob, updated_at)
                VALUES (?, ?, ?, ?, X'00', ?)
                """.trimIndent(),
                arrayOf(collectionId, 9_999L, 0, "forced database failure", 100L)
            )
        }
    }.use { fixture ->
        val namespace = fixture.namespace()

        assertThrows(SQLiteConstraintException::class.java) {
            fixture.repository.upsertBatch(
                namespace,
                listOf(item(1), item(2)),
                listOf(vector(1f, 0f), vector(0f, 1f))
            )
        }

        assertEquals(emptyList<Long>(), fixture.repository.listHashes(namespace))
        assertEquals(0, fixture.helper.writableDatabase.rawQuery("SELECT count(*) FROM vector_collections", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        })
        assertEquals(0, fixture.helper.writableDatabase.rawQuery("SELECT count(*) FROM vector_items", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        })
    }

    @Test
    fun `separates endpoint and model namespaces and rejects dimension changes`() = fixture().use { fixture ->
        val original = fixture.namespace(dimension = 2)
        val endpointChanged = original.copy(endpointFingerprint = "b".repeat(64))
        val modelChanged = original.copy(model = "other")
        fixture.repository.upsertBatch(original, listOf(item(1)), listOf(vector(1f, 0f)))
        fixture.repository.upsertBatch(endpointChanged, listOf(item(2)), listOf(vector(1f, 0f)))
        fixture.repository.upsertBatch(modelChanged, listOf(item(3)), listOf(vector(1f, 0f)))

        assertEquals(listOf(1L), fixture.repository.listHashes(original))
        assertEquals(listOf(2L), fixture.repository.listHashes(endpointChanged))
        assertEquals(listOf(3L), fixture.repository.listHashes(modelChanged))
        val failure = assertThrows(EmbeddingFailure::class.java) {
            fixture.repository.upsertBatch(original.copy(dimension = 3), listOf(item(4)), listOf(vector3()))
        }
        assertEquals(409, failure.httpStatus)
        assertEquals("vector_dimension_changed", failure.errorCode)
        assertEquals(listOf(1L), fixture.repository.listHashes(original))
    }

    @Test
    fun `delete is idempotent and purge collection removes every provider namespace while keeping schema`() = fixture().use { fixture ->
        val first = fixture.namespace()
        val second = first.copy(model = "other")
        fixture.repository.upsertBatch(first, listOf(item(1)), listOf(vector(1f, 0f)))
        fixture.repository.upsertBatch(second, listOf(item(2)), listOf(vector(1f, 0f)))

        fixture.repository.deleteHashes(first, listOf(1, 1, 99))
        fixture.repository.deleteHashes(first, listOf(1))
        assertEquals(emptyList<Long>(), fixture.repository.listHashes(first))
        fixture.repository.purgeCollection(first.collectionKey)
        assertEquals(emptyList<Long>(), fixture.repository.listHashes(second))
        fixture.repository.purgeAll()
        assertEquals(2, fixture.helper.writableDatabase.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('vector_collections', 'vector_items')",
            null
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
    }

    @Test
    fun `delete chunks reserve one binding for collection id at 998 and 999 hashes`() = fixture().use { fixture ->
        val namespace = fixture.namespace()
        val hashes = (1L..999L).toList()
        val encoded = vector(1f, 0f)
        fixture.repository.upsertBatch(
            namespace,
            hashes.map(::item),
            hashes.map { encoded }
        )

        fixture.repository.deleteHashes(namespace, hashes.take(998))
        assertEquals(listOf(999L), fixture.repository.listHashes(namespace))

        fixture.repository.upsertBatch(
            namespace,
            hashes.map(::item),
            hashes.map { encoded }
        )
        fixture.repository.deleteHashes(namespace, hashes)
        assertEquals(emptyList<Long>(), fixture.repository.listHashes(namespace))
    }

    private fun item(hash: Long, text: String = "text-$hash", index: Int = hash.toInt()): VectorItemInput =
        VectorItemInput(hash, text, index)

    private fun vector(vararg values: Float): EncodedVector = VectorCodec.encodeNormalized(values)

    private fun vector3(): EncodedVector = vector(1f, 0f, 0f)

    private fun fixture(
        beforeItemWrite: ((SQLiteDatabase, Long, Int) -> Unit)? = null
    ): Fixture {
        val paths = NativeAdapterPaths(Files.createTempDirectory("vector-repository-test").toFile())
        val ticks = longArrayOf(100L)
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "v${System.nanoTime().toULong().toString(36)}.db"
        check(context.getDatabasePath(databaseName).parentFile?.let { it.exists() || it.mkdirs() } == true)
        val helper = VectorDatabaseHelper(
            context,
            paths,
            DiagnosticLogger(paths.logsDir),
            { ticks[0] },
            databaseName
        )
        val clock = { ticks[0]++ }
        val repository = if (beforeItemWrite == null) {
            VectorRepository(helper, clock)
        } else {
            VectorRepository(helper, clock, beforeItemWrite)
        }
        return Fixture(helper, repository)
    }

    private data class Fixture(val helper: VectorDatabaseHelper, val repository: VectorRepository) : AutoCloseable {
        override fun close() = helper.close()

        fun namespace(dimension: Int = 2): VectorNamespace = VectorNamespace(
            "collection",
            "stapk_openai_compatible",
            "a".repeat(64),
            "model",
            dimension
        )
    }
}
