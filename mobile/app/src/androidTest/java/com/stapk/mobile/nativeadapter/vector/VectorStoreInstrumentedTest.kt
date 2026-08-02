package com.stapk.mobile.nativeadapter.vector

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VectorStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val testId = UUID.randomUUID().toString()
    private val databaseName = "vector-store-instrumented-$testId.db"
    private val testRoot = File(context.cacheDir, "vector-store-instrumented-$testId")
    private val paths = NativeAdapterPaths(testRoot)
    private val helper = VectorDatabaseHelper(
        context,
        paths,
        DiagnosticLogger(paths.logsDir),
        System::currentTimeMillis,
        databaseName
    )

    @After
    fun tearDown() {
        helper.close()
        listOf(
            context.getDatabasePath(databaseName),
            context.getDatabasePath("$databaseName-wal"),
            context.getDatabasePath("$databaseName-shm")
        ).forEach { file ->
            if (file.exists()) assertTrue("Unable to delete ${file.absolutePath}", file.delete())
        }
        if (testRoot.exists()) assertTrue(testRoot.deleteRecursively())
    }

    @Test
    fun vectorsPersistAcrossRepositoryReopen() {
        val namespace = namespace(dimension = 3)
        val encoded = VectorCodec.encodeNormalized(floatArrayOf(1f, 2f, 3f))
        VectorRepository(helper).useRepository { repository ->
            repository.upsertBatch(
                namespace,
                listOf(VectorItemInput(101L, "persistent text", 0)),
                listOf(encoded)
            )
        }

        val reopenedHelper = VectorDatabaseHelper(
            context,
            paths,
            DiagnosticLogger(paths.logsDir),
            System::currentTimeMillis,
            databaseName
        )
        try {
            val reopened = VectorRepository(reopenedHelper)
            assertEquals(listOf(101L), reopened.listHashes(namespace))
            assertEquals(
                listOf(101L),
                reopened.query(namespace, floatArrayOf(1f, 2f, 3f), 10, -1f).hashes
            )
            val mode = reopenedHelper.readableDatabase
                .rawQuery("PRAGMA journal_mode", null)
                .use { cursor -> cursor.moveToFirst(); cursor.getString(0) }
            assertEquals("wal", mode.lowercase())
        } finally {
            reopenedHelper.close()
        }
    }

    @Test
    fun purgeAllKeepsCanonicalDataAndDatabaseSchema() {
        val canonical = File(paths.chatsDir, "canonical-chat.json").apply {
            parentFile?.mkdirs()
            writeText("""{"message":"keep me"}""")
        }
        val repository = VectorRepository(helper)
        repository.upsertBatch(
            namespace(dimension = 2),
            listOf(VectorItemInput(7L, "derived text", 0)),
            listOf(VectorCodec.encodeNormalized(floatArrayOf(1f, 1f)))
        )

        repository.purgeAll()

        assertTrue(canonical.exists())
        assertEquals("""{"message":"keep me"}""", canonical.readText())
        val tables = helper.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('vector_collections','vector_items')",
            null
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(setOf("vector_collections", "vector_items"), tables)
    }

    private fun namespace(dimension: Int) = VectorNamespace(
        collectionKey = "instrumented",
        providerType = "openai",
        endpointFingerprint = "a".repeat(64),
        model = "test-model",
        dimension = dimension
    )

    private inline fun VectorRepository.useRepository(block: (VectorRepository) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
