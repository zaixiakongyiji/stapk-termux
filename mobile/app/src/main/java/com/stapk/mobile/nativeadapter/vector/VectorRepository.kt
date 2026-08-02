package com.stapk.mobile.nativeadapter.vector

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class VectorNamespace(
    val collectionKey: String,
    val providerType: String,
    val endpointFingerprint: String,
    val model: String,
    val dimension: Int
)

data class VectorItemInput(
    val hash: Long,
    val text: String,
    val index: Int
)

data class VectorMetadata(
    val hash: Long,
    val text: String,
    val index: Int
)

data class VectorQueryResult(
    val hashes: List<Long>,
    val metadata: List<VectorMetadata>
)

data class VectorHit(
    val collectionKey: String,
    val score: Float,
    val metadata: VectorMetadata
)

class VectorRepository private constructor(
    private val helper: VectorDatabaseHelper,
    private val clock: () -> Long,
    private val itemWriteHooks: ItemWriteHooks,
    private val corruptionSeam: CorruptionSeam
) : VectorStore {
    private val lifecycleLock = ReentrantReadWriteLock(true)

    constructor(
        helper: VectorDatabaseHelper,
        clock: () -> Long = System::currentTimeMillis
    ) : this(helper, clock, ItemWriteHooks(NO_ITEM_WRITE), CorruptionSeam(NO_CORRUPTION))

    internal constructor(
        helper: VectorDatabaseHelper,
        clock: () -> Long,
        beforeItemWrite: (SQLiteDatabase, Long, Int) -> Unit
    ) : this(helper, clock, ItemWriteHooks(beforeItemWrite), CorruptionSeam(NO_CORRUPTION))

    internal constructor(
        helper: VectorDatabaseHelper,
        clock: () -> Long,
        corruptionForTesting: () -> Throwable?
    ) : this(helper, clock, ItemWriteHooks(NO_ITEM_WRITE), CorruptionSeam(corruptionForTesting))

    override fun listHashes(namespace: VectorNamespace): List<Long> {
        validateNamespace(namespace)
        return withCorruptionRecovery {
            helper.readableDatabase.rawQuery(
                """
                SELECT items.content_hash
                FROM vector_items AS items
                JOIN vector_collections AS collections ON collections.id = items.collection_id
                WHERE collections.collection_key = ?
                  AND collections.provider_type = ?
                  AND collections.endpoint_fingerprint = ?
                  AND collections.model = ?
                ORDER BY items.content_hash ASC
                """.trimIndent(),
                namespaceLookupArgs(namespace)
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }
        }
    }

    override fun upsertBatch(namespace: VectorNamespace, items: List<VectorItemInput>, vectors: List<EncodedVector>) {
        validateBatch(namespace, items, vectors)
        if (items.isEmpty()) return

        withCorruptionRecovery {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                val now = clock()
                ensureCollection(database, namespace, now)
                val collection = requireCollection(database, namespace)
                if (collection.dimension != namespace.dimension) {
                    throw EmbeddingFailure(409, "vector_dimension_changed")
                }
                items.zip(vectors).forEachIndexed { itemPosition, (item, vector) ->
                    itemWriteHooks.beforeItemWrite(database, collection.id, itemPosition)
                    val values = ContentValues().apply {
                        put("item_index", item.index)
                        put("text", item.text)
                        put("vector_blob", vector.blob)
                        put("updated_at", now)
                    }
                    val updated = database.update(
                        "vector_items",
                        values,
                        "collection_id = ? AND content_hash = ?",
                        arrayOf(collection.id.toString(), item.hash.toString())
                    )
                    if (updated == 0) {
                        values.put("collection_id", collection.id)
                        values.put("content_hash", item.hash)
                        database.insertOrThrow("vector_items", null, values)
                    }
                }
                database.update(
                    "vector_collections",
                    ContentValues().apply { put("updated_at", now) },
                    "id = ?",
                    arrayOf(collection.id.toString())
                )
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    override fun deleteHashes(namespace: VectorNamespace, hashes: List<Long>) {
        validateNamespace(namespace)
        if (hashes.isEmpty()) return

        withCorruptionRecovery {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                val collectionId = findCollection(database, namespace)?.id
                if (collectionId != null) {
                    hashes.distinct().chunked(MAX_HASHES_PER_DELETE_BATCH).forEach { batch ->
                        val placeholders = batch.joinToString(",") { "?" }
                        database.delete(
                            "vector_items",
                            "collection_id = ? AND content_hash IN ($placeholders)",
                            arrayOf(collectionId.toString()) + batch.map(Long::toString)
                        )
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    override fun purgeCollection(collectionKey: String) {
        require(collectionKey.isNotBlank()) { "Collection key is required" }
        withCorruptionRecovery {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                database.delete("vector_collections", "collection_key = ?", arrayOf(collectionKey))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    override fun purgeAll() {
        withCorruptionRecovery {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                database.delete("vector_collections", null, null)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    fun close() {
        lifecycleLock.write { helper.close() }
    }

    override fun query(
        namespace: VectorNamespace,
        queryVector: FloatArray,
        topK: Int,
        threshold: Float
    ): VectorQueryResult {
        validateQuery(namespace, queryVector, topK, threshold)
        val normalizedQuery = VectorCodec.normalize(queryVector)
        return withCorruptionRecovery {
            val heap = PriorityQueue(WORST_HIT_FIRST)
            val collection = findCollection(helper.readableDatabase, namespace)
                ?: return@withCorruptionRecovery VectorQueryResult(emptyList(), emptyList())
            if (collection.dimension != namespace.dimension) {
                throw EmbeddingFailure(409, "vector_dimension_changed")
            }
            scanCollection(helper.readableDatabase, collection.id, namespace.collectionKey, normalizedQuery, threshold, topK, heap)
            toQueryResult(heap)
        }
    }

    override fun queryMulti(
        namespaces: List<VectorNamespace>,
        queryVector: FloatArray,
        topK: Int,
        threshold: Float
    ): Map<String, VectorQueryResult> {
        require(namespaces.size in 1..MAX_MULTI_COLLECTIONS) { "Collection count is out of range" }
        require(namespaces.map(VectorNamespace::collectionKey).distinct().size == namespaces.size) {
            "Collection keys must be unique"
        }
        namespaces.forEach { validateQuery(it, queryVector, topK, threshold) }
        val normalizedQuery = VectorCodec.normalize(queryVector)
        return withCorruptionRecovery {
            val database = helper.readableDatabase
            val heap = PriorityQueue(WORST_HIT_FIRST)
            namespaces.forEach { namespace ->
                val collection = findCollection(database, namespace) ?: return@forEach
                if (collection.dimension != namespace.dimension) {
                    throw EmbeddingFailure(409, "vector_dimension_changed")
                }
                scanCollection(database, collection.id, namespace.collectionKey, normalizedQuery, threshold, topK, heap)
            }
            val grouped = LinkedHashMap<String, MutableList<VectorMetadata>>()
            orderedHits(heap).forEach { hit ->
                grouped.getOrPut(hit.collectionKey) { mutableListOf() }.add(hit.metadata)
            }
            grouped.mapValues { (_, metadata) ->
                VectorQueryResult(metadata.map(VectorMetadata::hash), metadata)
            }
        }
    }

    private fun validateBatch(namespace: VectorNamespace, items: List<VectorItemInput>, vectors: List<EncodedVector>) {
        validateNamespace(namespace)
        require(items.size == vectors.size) { "Item and vector counts must match" }
        require(items.map(VectorItemInput::hash).distinct().size == items.size) { "Content hashes must be unique within a batch" }
        vectors.forEach { vector ->
            require(vector.dimension == namespace.dimension) { "Vector dimension does not match namespace" }
            require(vector.blob.size == vector.dimension * Float.SIZE_BYTES) { "Vector blob dimension does not match" }
            VectorCodec.decode(vector.blob, vector.dimension)
        }
    }

    private fun validateNamespace(namespace: VectorNamespace) {
        require(namespace.collectionKey.isNotBlank()) { "Collection key is required" }
        require(namespace.providerType.isNotBlank()) { "Provider type is required" }
        require(namespace.endpointFingerprint.matches(SHA256)) { "Endpoint fingerprint is invalid" }
        require(namespace.model.isNotBlank()) { "Model is required" }
        require(namespace.dimension in 1..VectorCodec.MAX_DIMENSION) { "Namespace dimension is invalid" }
    }

    private fun validateQuery(namespace: VectorNamespace, queryVector: FloatArray, topK: Int, threshold: Float) {
        validateNamespace(namespace)
        require(topK in 1..MAX_TOP_K) { "Top K is out of range" }
        require(threshold.isFinite() && threshold in -1f..1f) { "Threshold is out of range" }
        require(queryVector.size == namespace.dimension) { "Query vector dimension does not match namespace" }
    }

    private fun scanCollection(
        database: SQLiteDatabase,
        collectionId: Long,
        collectionKey: String,
        queryVector: FloatArray,
        threshold: Float,
        topK: Int,
        heap: PriorityQueue<VectorHit>
    ) {
        database.rawQuery(
            "SELECT content_hash, item_index, text, vector_blob FROM vector_items WHERE collection_id = ?",
            arrayOf(collectionId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val vector = VectorCodec.decode(cursor.getBlob(3), queryVector.size)
                val score = VectorCodec.dot(queryVector, vector)
                if (score >= threshold) {
                    offerHit(
                        heap,
                        topK,
                        VectorHit(collectionKey, score, VectorMetadata(cursor.getLong(0), cursor.getString(2), cursor.getInt(1)))
                    )
                }
            }
        }
    }

    private fun offerHit(heap: PriorityQueue<VectorHit>, topK: Int, hit: VectorHit) {
        if (heap.size < topK) {
            heap.add(hit)
        } else if (BEST_HIT_FIRST.compare(hit, heap.peek()) < 0) {
            heap.poll()
            heap.add(hit)
        }
    }

    private fun toQueryResult(heap: PriorityQueue<VectorHit>): VectorQueryResult {
        val metadata = orderedHits(heap).map(VectorHit::metadata)
        return VectorQueryResult(metadata.map(VectorMetadata::hash), metadata)
    }

    private fun orderedHits(heap: PriorityQueue<VectorHit>): List<VectorHit> = heap.toList().sortedWith(BEST_HIT_FIRST)

    private fun <T> withCorruptionRecovery(operation: () -> T): T = try {
        lifecycleLock.read {
            corruptionSeam.beforeOperation()?.let { throw it }
            operation()
        }
    } catch (exception: SQLiteDatabaseCorruptException) {
        recoverCorruption(exception)
    } catch (exception: SQLiteException) {
        if (isCorruption(exception)) recoverCorruption(exception)
        throw exception
    }

    private fun recoverCorruption(cause: Throwable): Nothing = lifecycleLock.write {
        helper.quarantineAndRecreate(cause)
    }

    private fun isCorruption(exception: SQLiteException): Boolean = exception.message
        ?.uppercase()
        ?.let { it.contains("SQLITE_CORRUPT") || it.contains("FILE IS NOT A DATABASE") }
        ?: false

    private fun ensureCollection(database: SQLiteDatabase, namespace: VectorNamespace, now: Long) {
        database.insertWithOnConflict(
            "vector_collections",
            null,
            ContentValues().apply {
                put("collection_key", namespace.collectionKey)
                put("provider_type", namespace.providerType)
                put("endpoint_fingerprint", namespace.endpointFingerprint)
                put("model", namespace.model)
                put("dimension", namespace.dimension)
                put("created_at", now)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun requireCollection(database: SQLiteDatabase, namespace: VectorNamespace): CollectionRecord =
        requireNotNull(findCollection(database, namespace)) { "Collection was not created" }

    private fun findCollection(database: SQLiteDatabase, namespace: VectorNamespace): CollectionRecord? =
        database.rawQuery(
            """
            SELECT id, dimension FROM vector_collections
            WHERE collection_key = ? AND provider_type = ? AND endpoint_fingerprint = ? AND model = ?
            """.trimIndent(),
            namespaceLookupArgs(namespace)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else CollectionRecord(cursor.getLong(0), cursor.getInt(1))
        }

    private fun namespaceLookupArgs(namespace: VectorNamespace): Array<String> = arrayOf(
        namespace.collectionKey,
        namespace.providerType,
        namespace.endpointFingerprint,
        namespace.model
    )

    private data class CollectionRecord(val id: Long, val dimension: Int)

    private data class ItemWriteHooks(
        val beforeItemWrite: (SQLiteDatabase, Long, Int) -> Unit
    )

    private data class CorruptionSeam(
        val beforeOperation: () -> Throwable?
    )

    private companion object {
        const val MAX_SQL_VARIABLES = 999
        const val MAX_HASHES_PER_DELETE_BATCH = MAX_SQL_VARIABLES - 1
        const val MAX_TOP_K = 100
        const val MAX_MULTI_COLLECTIONS = 64
        val NO_ITEM_WRITE: (SQLiteDatabase, Long, Int) -> Unit = { _, _, _ -> }
        val NO_CORRUPTION: () -> Throwable? = { null }
        val SHA256 = Regex("[a-f0-9]{64}")
        val BEST_HIT_FIRST: Comparator<VectorHit> = compareByDescending<VectorHit> { it.score }
            .thenBy { it.collectionKey }
            .thenBy { it.metadata.index }
            .thenBy { it.metadata.hash }
        val WORST_HIT_FIRST: Comparator<VectorHit> = Comparator { left, right ->
            BEST_HIT_FIRST.compare(right, left)
        }
    }
}
