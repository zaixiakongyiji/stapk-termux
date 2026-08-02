package com.stapk.mobile.nativeadapter.vector

import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorDatabaseHelperTest {
    @Test
    fun `creates versioned WAL schema with constraints and cascading collections`() {
        val fixture = fixture()
        fixture.helper.use { helper ->
            val database = helper.writableDatabase

            assertEquals(1, database.version)
            assertEquals(1L, database.rawQuery("PRAGMA foreign_keys", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            })
            assertEquals("wal", database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0).lowercase()
            })

            val schema = database.rawQuery(
                "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name IN ('vector_collections', 'vector_items')",
                null
            ).use { cursor ->
                generateSequence {
                    if (cursor.moveToNext()) cursor.getString(0) to cursor.getString(1) else null
                }.toMap()
            }
            assertEquals(setOf("vector_collections", "vector_items"), schema.keys)
            assertTrue(schema.getValue("vector_collections").contains("UNIQUE (collection_key, provider_type, endpoint_fingerprint, model)"))
            assertTrue(schema.getValue("vector_collections").contains("CHECK (dimension > 0 AND dimension <= 32768)"))
            assertTrue(schema.getValue("vector_items").contains("UNIQUE (collection_id, content_hash)"))
            assertTrue(schema.getValue("vector_items").contains("CHECK (length(vector_blob) > 0 AND length(vector_blob) % 4 = 0)"))
            assertTrue(schema.getValue("vector_items").contains("ON DELETE CASCADE"))

            assertThrows(Exception::class.java) {
                database.execSQL(
                    "INSERT INTO vector_collections(collection_key, provider_type, endpoint_fingerprint, model, dimension, created_at, updated_at) VALUES ('c', 'p', 'e', 'm', 0, 1, 1)"
                )
            }
            database.execSQL(
                "INSERT INTO vector_collections(collection_key, provider_type, endpoint_fingerprint, model, dimension, created_at, updated_at) VALUES ('c', 'p', 'e', 'm', 2, 1, 1)"
            )
            val collectionId = database.rawQuery("SELECT id FROM vector_collections", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
            assertThrows(Exception::class.java) {
                database.execSQL(
                    "INSERT INTO vector_items(collection_id, content_hash, item_index, text, vector_blob, updated_at) VALUES ($collectionId, 1, 0, 'text', X'00', 1)"
                )
            }
            database.execSQL(
                "INSERT INTO vector_items(collection_id, content_hash, item_index, text, vector_blob, updated_at) VALUES ($collectionId, 1, 0, 'text', X'00000000', 1)"
            )
            database.delete("vector_collections", "id = ?", arrayOf(collectionId.toString()))
            assertFalse(database.rawQuery("SELECT 1 FROM vector_items", null).use { it.moveToFirst() })
        }
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("vector-helper-test").toFile()
        val paths = NativeAdapterPaths(root)
        val helper = VectorDatabaseHelper(
            RuntimeEnvironment.getApplication(),
            paths,
            DiagnosticLogger(paths.logsDir),
            { 1L },
            "vector-helper-${System.nanoTime()}.db"
        )
        return Fixture(helper)
    }

    private data class Fixture(val helper: VectorDatabaseHelper)
}
