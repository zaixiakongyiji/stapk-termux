package com.stapk.mobile.nativeadapter

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.util.UUID

class ExtensionTransactionJournal(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir),
    private val fileRemover: (File) -> Boolean = File::delete
) {
    @Synchronized
    fun read(): ExtensionTransaction? {
        val file = paths.extensionTransactionFile
        if (!file.isFile) return null
        return try {
            decode(parseStrict(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            store.quarantine(file, "invalid_extension_transaction")
            null
        }
    }

    @Synchronized
    fun write(transaction: ExtensionTransaction) {
        validate(transaction)
        store.writeText(paths.extensionTransactionFile, encode(transaction))
    }

    @Synchronized
    fun clear() {
        val file = paths.extensionTransactionFile
        if (!file.exists()) return
        if (!fileRemover(file)) {
            throw IOException("Unable to remove extension transaction journal")
        }
    }

    private fun encode(transaction: ExtensionTransaction): String = GSON.toJson(JsonObject().apply {
        addProperty("schemaVersion", transaction.schemaVersion)
        addProperty("transactionId", transaction.transactionId)
        addProperty("operation", transaction.operation.toWireValue())
        addProperty("phase", transaction.phase.toWireValue())
        addProperty("folderName", transaction.folderName)
        add("oldRecord", transaction.oldRecord.toJsonElement())
        add("newRecord", transaction.newRecord.toJsonElement())
        addNullableString("stagingName", transaction.stagingName)
        addNullableString("backupName", transaction.backupName)
        addNullableString("trashName", transaction.trashName)
    })

    private fun decode(element: JsonElement): ExtensionTransaction {
        val source = try {
            element.asJsonObject
        } catch (exception: Exception) {
            throw IllegalArgumentException("Extension transaction must be an object", exception)
        }
        require(source.keySet() == FIELD_NAMES) { "Extension transaction fields are invalid" }
        val transaction = ExtensionTransaction(
            schemaVersion = source.requiredInt("schemaVersion"),
            transactionId = source.requiredString("transactionId"),
            operation = source.requiredOperation(),
            phase = source.requiredPhase(),
            folderName = source.requiredString("folderName"),
            oldRecord = source.optionalRecord("oldRecord"),
            newRecord = source.optionalRecord("newRecord"),
            stagingName = source.optionalString("stagingName"),
            backupName = source.optionalString("backupName"),
            trashName = source.optionalString("trashName")
        )
        validate(transaction)
        return transaction
    }

    private fun parseStrict(text: String): JsonElement = JsonReader(StringReader(text)).use { reader ->
        reader.setStrictness(Strictness.STRICT)
        val element = readStrictElement(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT) {
            "Extension transaction must contain exactly one JSON value"
        }
        element
    }

    private fun readStrictElement(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JsonObject().also { result ->
            val names = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(names.add(name)) { "Duplicate extension transaction field: $name" }
                result.add(name, readStrictElement(reader))
            }
            reader.endObject()
        }

        JsonToken.BEGIN_ARRAY -> JsonArray().also { result ->
            reader.beginArray()
            while (reader.hasNext()) result.add(readStrictElement(reader))
            reader.endArray()
        }

        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> JsonNull.INSTANCE.also { reader.nextNull() }
        else -> throw IllegalArgumentException("Invalid extension transaction JSON value")
    }

    private fun validate(transaction: ExtensionTransaction) {
        require(transaction.schemaVersion == SCHEMA_VERSION) { "Unsupported extension transaction schema" }
        val canonicalId = try {
            UUID.fromString(transaction.transactionId).toString()
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid extension transaction UUID", exception)
        }
        require(canonicalId == transaction.transactionId) { "Extension transaction UUID must be canonical" }

        val folder = SafePath.child(paths.extensionsDir, transaction.folderName)
        require(folder.name == transaction.folderName) { "Extension folder must be a basename" }
        transaction.oldRecord?.let {
            require(it.folderName == transaction.folderName) { "Old extension record folder does not match" }
        }
        transaction.newRecord?.let {
            require(it.folderName == transaction.folderName) { "New extension record folder does not match" }
        }

        when (transaction.operation) {
            ExtensionOperation.INSTALL -> {
                require(transaction.oldRecord == null) { "Install transaction must not contain an old record" }
                require(transaction.newRecord != null) { "Install transaction requires a new record" }
                require(transaction.stagingName != null) { "Install transaction requires staging" }
                require(transaction.backupName == null) { "Install transaction must not contain backup" }
                require(transaction.trashName == null) { "Install transaction must not contain trash" }
            }

            ExtensionOperation.UPDATE -> {
                require(transaction.oldRecord != null) { "Update transaction requires an old record" }
                require(transaction.newRecord != null) { "Update transaction requires a new record" }
                require(transaction.stagingName != null) { "Update transaction requires staging" }
                require(transaction.backupName != null) { "Update transaction requires backup" }
                require(transaction.trashName == null) { "Update transaction must not contain trash" }
            }

            ExtensionOperation.DELETE -> {
                require(transaction.oldRecord != null) { "Delete transaction requires an old record" }
                require(transaction.newRecord == null) { "Delete transaction must not contain a new record" }
                require(transaction.stagingName == null) { "Delete transaction must not contain staging" }
                require(transaction.backupName == null) { "Delete transaction must not contain backup" }
                require(transaction.trashName != null) { "Delete transaction requires trash" }
            }
        }

        transaction.stagingName?.let {
            validateTransactionName(it, canonicalId, "installing")
        }
        transaction.backupName?.let {
            validateTransactionName(it, canonicalId, "backup")
        }
        transaction.trashName?.let {
            validateTransactionName(it, canonicalId, "trash")
        }
    }

    private fun validateTransactionName(name: String, transactionId: String, suffix: String) {
        val child = SafePath.child(paths.extensionsDir, name)
        require(child.name == name) { "Extension transaction directory must be a basename" }
        require(name == ".stapk-txn-$transactionId.$suffix") {
            "Extension transaction directory does not match its transaction"
        }
    }

    private fun ExtensionOperation.toWireValue(): String = when (this) {
        ExtensionOperation.INSTALL -> "install"
        ExtensionOperation.UPDATE -> "update"
        ExtensionOperation.DELETE -> "delete"
    }

    private fun ExtensionTransactionPhase.toWireValue(): String = when (this) {
        ExtensionTransactionPhase.PREPARED -> "prepared"
        ExtensionTransactionPhase.FILES_ACTIVATED -> "files_activated"
        ExtensionTransactionPhase.REGISTRY_COMMITTED -> "registry_committed"
    }

    private fun JsonObject.requiredOperation(): ExtensionOperation = when (requiredString("operation")) {
        "install" -> ExtensionOperation.INSTALL
        "update" -> ExtensionOperation.UPDATE
        "delete" -> ExtensionOperation.DELETE
        else -> throw IllegalArgumentException("Unknown extension transaction operation")
    }

    private fun JsonObject.requiredPhase(): ExtensionTransactionPhase = when (requiredString("phase")) {
        "prepared" -> ExtensionTransactionPhase.PREPARED
        "files_activated" -> ExtensionTransactionPhase.FILES_ACTIVATED
        "registry_committed" -> ExtensionTransactionPhase.REGISTRY_COMMITTED
        else -> throw IllegalArgumentException("Unknown extension transaction phase")
    }

    private fun JsonObject.requiredString(name: String): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Invalid extension transaction $name")

    private fun JsonObject.requiredInt(name: String): Int {
        val value = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?: throw IllegalArgumentException("Invalid extension transaction $name")
        return value.asString.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid extension transaction $name")
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: throw IllegalArgumentException("Missing extension transaction $name")
        if (value.isJsonNull) return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Invalid extension transaction $name")
    }

    private fun JsonObject.optionalRecord(name: String): ExtensionRecord? {
        val value = get(name) ?: throw IllegalArgumentException("Missing extension transaction $name")
        return if (value.isJsonNull) null else ExtensionRecordCodec.decode(value)
    }

    private fun ExtensionRecord?.toJsonElement(): JsonElement =
        this?.let { JsonParser.parseString(ExtensionRecordCodec.encode(it)) } ?: JsonNull.INSTANCE

    private fun JsonObject.addNullableString(name: String, value: String?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        val GSON = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
        val FIELD_NAMES = setOf(
            "schemaVersion",
            "transactionId",
            "operation",
            "phase",
            "folderName",
            "oldRecord",
            "newRecord",
            "stagingName",
            "backupName",
            "trashName"
        )
    }
}
