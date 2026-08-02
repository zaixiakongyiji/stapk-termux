package com.stapk.mobile.nativeadapter.vector

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import java.io.File
import java.util.Random
import java.util.UUID
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VectorStorePerformanceInstrumentedTest {
    @Test
    fun exactTopTenScanMeetsPixel8Api35Baseline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testId = UUID.randomUUID().toString()
        val databaseName = "vector-store-performance-$testId.db"
        val testRoot = File(context.cacheDir, "vector-store-performance-$testId")
        val paths = NativeAdapterPaths(testRoot)
        val helper = VectorDatabaseHelper(
            context,
            paths,
            DiagnosticLogger(paths.logsDir),
            System::currentTimeMillis,
            databaseName
        )
        val repository = VectorRepository(helper)
        val namespace = VectorNamespace(
            collectionKey = "performance",
            providerType = "openai",
            endpointFingerprint = "b".repeat(64),
            model = "performance-model",
            dimension = DIMENSION
        )
        try {
            val random = Random(SEED)
            var nextHash = 1L
            repeat(ITEM_COUNT / BATCH_SIZE) {
                val items = ArrayList<VectorItemInput>(BATCH_SIZE)
                val vectors = ArrayList<EncodedVector>(BATCH_SIZE)
                repeat(BATCH_SIZE) {
                    items += VectorItemInput(nextHash, "item-$nextHash", nextHash.toInt())
                    vectors += VectorCodec.encodeNormalized(nextNormalizedVector(random))
                    nextHash++
                }
                repository.upsertBatch(namespace, items, vectors)
            }
            val query = nextNormalizedVector(Random(SEED + 1))
            repository.query(namespace, query, TOP_K, -1f)
            Runtime.getRuntime().gc()
            Thread.sleep(100)

            val runtime = Runtime.getRuntime()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            val durations = LongArray(3) {
                val started = System.nanoTime()
                val result = repository.query(namespace, query, TOP_K, -1f)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                assertEquals(TOP_K, result.hashes.size)
                elapsedMs
            }
            val heapAfter = runtime.totalMemory() - runtime.freeMemory()
            val medianMs = durations.sorted()[1]
            val heapDelta = (heapAfter - heapBefore).coerceAtLeast(0L)

            Log.i(
                PERFORMANCE_LOG_TAG,
                "durationsMs=${durations.joinToString(",")} " +
                    "medianMs=$medianMs heapBeforeBytes=$heapBefore " +
                    "heapAfterBytes=$heapAfter heapDeltaBytes=$heapDelta"
            )
            assertTrue("median scan took ${medianMs}ms", medianMs <= MAX_SCAN_MS)
            assertTrue("heap grew $heapDelta bytes", heapDelta <= MAX_HEAP_DELTA)
        } finally {
            repository.close()
            listOf(
                context.getDatabasePath(databaseName),
                context.getDatabasePath("$databaseName-wal"),
                context.getDatabasePath("$databaseName-shm")
            ).forEach { file -> if (file.exists()) file.delete() }
            if (testRoot.exists()) testRoot.deleteRecursively()
        }
    }

    private fun nextNormalizedVector(random: Random): FloatArray {
        val vector = FloatArray(DIMENSION)
        var squaredNorm = 0.0
        for (index in vector.indices) {
            val value = random.nextFloat() - 0.5f
            vector[index] = value
            squaredNorm += value.toDouble() * value
        }
        val norm = sqrt(squaredNorm).toFloat()
        for (index in vector.indices) vector[index] /= norm
        return vector
    }

    private companion object {
        const val ITEM_COUNT = 10_000
        const val DIMENSION = 1_536
        const val BATCH_SIZE = 100
        const val TOP_K = 10
        const val SEED = 13_037L
        const val MAX_SCAN_MS = 5_000L
        const val MAX_HEAP_DELTA = 64L * 1024L * 1024L
        const val PERFORMANCE_LOG_TAG = "VectorStorePerformance"
    }
}
