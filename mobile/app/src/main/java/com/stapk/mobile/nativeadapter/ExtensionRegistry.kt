package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser

class ExtensionRegistry(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {
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

    @Synchronized
    fun replaceAll(records: List<ExtensionRecord>) {
        require(records.indices.none { index ->
            records.indices.any { otherIndex ->
                otherIndex > index && records[index].folderName.equals(records[otherIndex].folderName, ignoreCase = true)
            }
        }) { "Extension folder already exists" }
        require(records.indices.none { index ->
            records.indices.any { otherIndex ->
                otherIndex > index && records[index].repositoryUrl.equals(records[otherIndex].repositoryUrl, ignoreCase = true)
            }
        }) { "Extension repository already exists" }
        writeRecords(records)
    }

    private fun readRecords(): MutableList<ExtensionRecord> {
        val file = paths.extensionRegistryFile
        if (!file.isFile) return mutableListOf()
        return try {
            JsonParser.parseString(file.readText()).asJsonArray
                .map(ExtensionRecordCodec::decode)
                .toMutableList()
        } catch (_: Exception) {
            store.quarantine(file, "invalid_extension_registry")
            mutableListOf()
        }
    }

    private fun writeRecords(records: List<ExtensionRecord>) {
        val json = records.sortedBy { it.folderName.lowercase() }
            .joinToString(prefix = "[", postfix = "]") { ExtensionRecordCodec.encode(it) }
        store.writeText(paths.extensionRegistryFile, json)
    }
}
