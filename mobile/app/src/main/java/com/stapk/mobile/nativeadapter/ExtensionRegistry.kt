package com.stapk.mobile.nativeadapter

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class ExtensionRegistry(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    @Synchronized
    fun list(): List<ExtensionRecord> = readRecords().sortedBy { it.folderName.lowercase() }

    @Synchronized
    fun find(folderName: String): ExtensionRecord? =
        readRecords().firstOrNull { it.folderName.equals(folderName, ignoreCase = true) }

    @Synchronized
    fun install(record: ExtensionRecord): ExtensionRecord {
        val records = readRecords()
        check(records.none { it.folderName.equals(record.folderName, ignoreCase = true) }) {
            "Extension folder already exists"
        }
        check(records.none { it.repositoryUrl.equals(record.repositoryUrl, ignoreCase = true) }) {
            "Extension repository already exists"
        }
        records += record
        writeRecords(records)
        return record
    }

    @Synchronized
    fun update(record: ExtensionRecord): ExtensionRecord {
        val records = readRecords()
        val index = records.indexOfFirst { it.folderName.equals(record.folderName, ignoreCase = true) }
        check(index >= 0) { "Extension is not installed" }
        check(records.withIndex().none { (otherIndex, existing) ->
            otherIndex != index && existing.repositoryUrl.equals(record.repositoryUrl, ignoreCase = true)
        }) { "Extension repository already exists" }
        records[index] = record
        writeRecords(records)
        return record
    }

    @Synchronized
    fun remove(folderName: String): Boolean {
        val records = readRecords()
        val removed = records.removeAll { it.folderName.equals(folderName, ignoreCase = true) }
        if (removed) writeRecords(records)
        return removed
    }

    private fun readRecords(): MutableList<ExtensionRecord> {
        val file = paths.extensionRegistryFile
        if (!file.isFile) return mutableListOf()
        return try {
            JsonParser.parseString(file.readText()).asJsonArray
                .map(::parseRecord)
                .toMutableList()
        } catch (_: Exception) {
            store.quarantine(file, "invalid_extension_registry")
            mutableListOf()
        }
    }

    private fun writeRecords(records: List<ExtensionRecord>) {
        store.writeText(paths.extensionRegistryFile, gson.toJson(records.sortedBy { it.folderName.lowercase() }))
    }

    private fun parseRecord(element: JsonElement): ExtensionRecord {
        val source = element.asJsonObject
        return ExtensionRecord(
            folderName = source.requiredString("folderName"),
            repositoryUrl = source.requiredString("repositoryUrl"),
            owner = source.requiredString("owner"),
            repository = source.requiredString("repository"),
            branch = source.requiredString("branch"),
            commitSha = source.requiredString("commitSha"),
            installedAt = source.requiredLong("installedAt"),
            updatedAt = source.requiredLong("updatedAt")
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Missing $name")

    private fun JsonObject.requiredLong(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            ?: throw IllegalArgumentException("Missing $name")
}
