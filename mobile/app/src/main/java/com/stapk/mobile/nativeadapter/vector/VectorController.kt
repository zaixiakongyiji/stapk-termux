package com.stapk.mobile.nativeadapter.vector

import android.database.sqlite.SQLiteFullException
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.HttpResponse
import kotlin.math.max

class VectorController(
    private val configStore: EmbeddingProviderConfigStore,
    private val providerClient: EmbeddingGateway,
    private val repository: VectorStore,
    private val usableSpace: () -> Long,
    @Suppress("UNUSED_PARAMETER") private val diagnosticLogger: DiagnosticLogger
) : VectorRoutes {
    override fun list(body: String): HttpResponse = respond {
        val input = objectBody(body)
        rejectOverrides(input)
        val snapshot = configStore.snapshot()
        validateSourceAndModel(input, snapshot)
        val collectionId = collectionId(input)
        json(Gson().toJsonTree(repository.listHashes(namespace(snapshot, collectionId, PLACEHOLDER_DIMENSION))))
    }

    override fun insert(body: String): HttpResponse = respond {
        val input = objectBody(body)
        rejectOverrides(input)
        val snapshot = configStore.snapshot()
        validateSourceAndModel(input, snapshot)
        val collectionId = collectionId(input)
        val items = items(input)
        checkFreeSpace(MIN_FREE_BYTES)
        val batch = providerClient.embed(snapshot, items.map(VectorItemInput::text))
        if (batch.vectors.size != items.size || batch.dimension !in 1..VectorCodec.MAX_DIMENSION ||
            batch.vectors.any { it.size != batch.dimension }
        ) {
            throw EmbeddingFailure(422, "embedding_invalid_vector")
        }
        val encoded = try {
            batch.vectors.map { vector -> VectorCodec.encodeNormalized(vector) }
        } catch (_: IllegalArgumentException) {
            throw EmbeddingFailure(422, "embedding_invalid_vector")
        }
        val projectedBatchBytes = encoded.sumOf { it.blob.size.toLong() } +
            items.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() + ITEM_OVERHEAD_BYTES }
        checkFreeSpace(max(MIN_FREE_BYTES, projectedBatchBytes * 2L))
        repository.upsertBatch(namespace(snapshot, collectionId, batch.dimension), items, encoded)
        json(JsonObject())
    }

    override fun delete(body: String): HttpResponse = respond {
        val input = objectBody(body)
        rejectOverrides(input)
        val snapshot = configStore.snapshot()
        validateSourceAndModel(input, snapshot)
        val hashes = longArray(input, "hashes", requireNonEmpty = true)
        repository.deleteHashes(namespace(snapshot, collectionId(input), PLACEHOLDER_DIMENSION), hashes)
        json(JsonObject())
    }

    override fun query(body: String): HttpResponse = respond {
        val input = objectBody(body)
        rejectOverrides(input)
        val snapshot = configStore.snapshot()
        validateSourceAndModel(input, snapshot)
        val batch = providerClient.embed(snapshot, listOf(searchText(input)))
        val vector = singleQueryVector(batch)
        json(repository.query(
            namespace(snapshot, collectionId(input), batch.dimension),
            vector,
            topK(input),
            threshold(input)
        ).toJson())
    }

    override fun queryMulti(body: String): HttpResponse = respond {
        val input = objectBody(body)
        rejectOverrides(input)
        val snapshot = configStore.snapshot()
        validateSourceAndModel(input, snapshot)
        val collectionIds = stringArray(input, "collectionIds", 1..MAX_COLLECTIONS)
        require(collectionIds.distinct().size == collectionIds.size) { "Duplicate collection IDs" }
        collectionIds.forEach(::validateCollectionId)
        val batch = providerClient.embed(snapshot, listOf(searchText(input)))
        val vector = singleQueryVector(batch)
        val result = repository.queryMulti(
            collectionIds.map { namespace(snapshot, it, batch.dimension) },
            vector,
            topK(input),
            threshold(input)
        )
        json(JsonObject().apply { result.forEach { (collection, value) -> add(collection, value.toJson()) } })
    }

    override fun purge(body: String): HttpResponse = respond {
        val input = objectBody(body)
        rejectOverrides(input)
        repository.purgeCollection(collectionId(input))
        json(JsonObject())
    }

    override fun purgeAll(): HttpResponse = respond {
        repository.purgeAll()
        json(JsonObject())
    }

    override fun getConfig(): HttpResponse = respond {
        json(configView(configStore.load()))
    }

    override fun saveConfig(body: String): HttpResponse = respond {
        val input = objectBody(body)
        val typeName = requiredString(input, "type")
        val type = EmbeddingProviderType.values().firstOrNull { it.wireName == typeName }
            ?: invalidRequest()
        val apiKey = optionalString(input, "apiKey")
        val baseUrl = if (type == EmbeddingProviderType.OPENAI) {
            OPENAI_BASE_URL
        } else {
            requiredString(input, "baseUrl")
        }
        val saved = configStore.save(
            EmbeddingProviderConfig(type, baseUrl, requiredString(input, "model")),
            apiKey
        )
        json(configView(saved))
    }

    override fun testConfig(): HttpResponse = respond {
        val batch = providerClient.embed(configStore.snapshot(), listOf(CONNECTION_TEST_TEXT))
        if (batch.vectors.size != 1 || batch.dimension !in 1..VectorCodec.MAX_DIMENSION || batch.vectors.single().size != batch.dimension) {
            throw EmbeddingFailure(422, "embedding_invalid_vector")
        }
        json(JsonObject().apply {
            addProperty("ok", true)
            addProperty("dimension", batch.dimension)
        })
    }

    private fun configView(config: EmbeddingProviderConfig): JsonObject = JsonObject().apply {
        addProperty("type", config.type.wireName)
        addProperty("baseUrl", config.baseUrl)
        addProperty("model", config.model)
        addProperty("keyConfigured", configStore.keyConfigured(config.type))
    }

    private fun singleQueryVector(batch: EmbeddingBatch): FloatArray {
        if (batch.vectors.size != 1 || batch.dimension !in 1..VectorCodec.MAX_DIMENSION || batch.vectors.single().size != batch.dimension) {
            throw EmbeddingFailure(422, "embedding_invalid_vector")
        }
        return batch.vectors.single()
    }

    private fun checkFreeSpace(required: Long) {
        if (usableSpace() < required) throw EmbeddingFailure(507, "vector_storage_full")
    }

    private fun namespace(snapshot: EmbeddingProviderSnapshot, collectionId: String, dimension: Int) = VectorNamespace(
        collectionId,
        snapshot.config.type.sourceId,
        snapshot.endpointFingerprint,
        snapshot.config.model,
        dimension
    )

    private fun validateSourceAndModel(input: JsonObject, snapshot: EmbeddingProviderSnapshot) {
        if (requiredString(input, "source") != snapshot.config.type.sourceId) invalidRequest()
        optionalString(input, "model")?.let { model ->
            if (model != snapshot.config.model) invalidRequest()
        }
    }

    private fun rejectOverrides(input: JsonObject) {
        if (FORBIDDEN_OVERRIDE_FIELDS.any(input::has)) invalidRequest()
    }

    private fun collectionId(input: JsonObject): String = requiredString(input, "collectionId").also(::validateCollectionId)

    private fun validateCollectionId(value: String) {
        require(value.isNotBlank() && value.length <= MAX_COLLECTION_ID_LENGTH && value.none(Char::isISOControl)) {
            "Invalid collection ID"
        }
    }

    private fun items(input: JsonObject): List<VectorItemInput> {
        val values = requiredArray(input, "items")
        require(values.size() in 1..MAX_INSERT_ITEMS) { "Invalid item count" }
        return values.map { raw ->
            require(raw.isJsonObject) { "Invalid item" }
            val item = raw.asJsonObject
            val text = requiredString(item, "text")
            require(text.length <= MAX_TEXT_LENGTH) { "Text is too large" }
            val index = strictInt(item.get("index")) ?: invalidRequest()
            require(index >= 0) { "Invalid index" }
            VectorItemInput(strictLong(item.get("hash")) ?: invalidRequest(), text, index)
        }.also { parsed -> require(parsed.map(VectorItemInput::hash).distinct().size == parsed.size) { "Duplicate hash" } }
    }

    private fun searchText(input: JsonObject): String = requiredString(input, "searchText").also { text ->
        require(text.length <= MAX_TEXT_LENGTH) { "Text is too large" }
    }

    private fun topK(input: JsonObject): Int {
        val value = input.get("topK")?.let { strictInt(it) ?: invalidRequest() } ?: DEFAULT_TOP_K
        require(value in 1..MAX_TOP_K) { "Invalid topK" }
        return value
    }

    private fun threshold(input: JsonObject): Float {
        val value = input.get("threshold")?.let { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) invalidRequest()
            element.asString.toFloatOrNull()?.takeIf { it.isFinite() } ?: invalidRequest()
        } ?: DEFAULT_THRESHOLD
        require(value in 0f..1f) { "Invalid threshold" }
        return value
    }

    private fun longArray(input: JsonObject, name: String, requireNonEmpty: Boolean): List<Long> =
        requiredArray(input, name).map { strictLong(it) ?: invalidRequest() }.also { values ->
            if (requireNonEmpty) require(values.isNotEmpty()) { "Missing $name" }
        }

    private fun stringArray(input: JsonObject, name: String, count: IntRange): List<String> = requiredArray(input, name)
        .also { require(it.size() in count) { "Invalid $name count" } }
        .map { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "Invalid $name" }
            element.asString
        }

    private fun requiredArray(input: JsonObject, name: String): JsonArray = input.get(name)
        ?.takeIf(JsonElement::isJsonArray)
        ?.asJsonArray
        ?: invalidRequest()

    private fun requiredString(input: JsonObject, name: String): String = optionalString(input, name) ?: invalidRequest()

    private fun optionalString(input: JsonObject, name: String): String? {
        val value = input.get(name) ?: return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: invalidRequest()
    }

    private fun strictLong(value: JsonElement?): Long? = value
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString
        ?.toLongOrNull()

    private fun strictInt(value: JsonElement?): Int? = value
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString
        ?.toIntOrNull()

    private fun objectBody(body: String): JsonObject = try {
        JsonParser.parseString(body).takeIf(JsonElement::isJsonObject)?.asJsonObject ?: invalidRequest()
    } catch (_: Exception) {
        invalidRequest()
    }

    private fun VectorQueryResult.toJson(): JsonObject = JsonObject().apply {
        add("hashes", Gson().toJsonTree(hashes))
        add("metadata", JsonArray().apply {
            metadata.forEach { item ->
                add(JsonObject().apply {
                    addProperty("hash", item.hash)
                    addProperty("text", item.text)
                    addProperty("index", item.index)
                })
            }
        })
    }

    private fun json(value: JsonElement): HttpResponse = HttpResponse.json(200, value.toString())

    private fun respond(operation: () -> HttpResponse): HttpResponse = try {
        operation()
    } catch (failure: EmbeddingFailure) {
        failure(failure)
    } catch (_: SQLiteFullException) {
        failure(EmbeddingFailure(507, "vector_storage_full"))
    } catch (_: IllegalArgumentException) {
        invalidResponse()
    } catch (_: Exception) {
        HttpResponse.json(502, "{\"error\":\"embedding_provider_error\"}")
    }

    private fun failure(error: EmbeddingFailure): HttpResponse =
        HttpResponse.json(error.httpStatus, "{\"error\":\"${error.errorCode}\"}")

    private fun invalidResponse(): HttpResponse = HttpResponse.json(400, "{\"error\":\"vector_invalid_request\"}")

    private fun invalidRequest(): Nothing = throw EmbeddingFailure(400, "vector_invalid_request")

    private companion object {
        const val MAX_INSERT_ITEMS = 64
        const val MAX_COLLECTIONS = 64
        const val MAX_TEXT_LENGTH = 100_000
        const val MAX_COLLECTION_ID_LENGTH = 512
        const val MAX_TOP_K = 100
        const val DEFAULT_TOP_K = 10
        const val DEFAULT_THRESHOLD = 0f
        const val PLACEHOLDER_DIMENSION = 1
        const val MIN_FREE_BYTES = 64L * 1024L * 1024L
        const val ITEM_OVERHEAD_BYTES = 128L
        const val CONNECTION_TEST_TEXT = "stAPK embedding connection test"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        val FORBIDDEN_OVERRIDE_FIELDS = setOf("baseUrl", "apiUrl", "urlOverride", "apiKey")
    }
}
