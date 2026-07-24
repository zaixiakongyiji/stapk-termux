package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionTransactionJournalTest {
    @Test
    fun `schema version one round trips with strict lowercase wire values`() {
        val paths = paths("round-trip")
        val journal = ExtensionTransactionJournal(paths)
        val transactions = listOf(
            installTransaction(),
            updateTransaction(),
            deleteTransaction()
        )

        transactions.forEach { transaction ->
            journal.write(transaction)

            assertEquals(transaction, journal.read())
            val json = JsonParser.parseString(paths.extensionTransactionFile.readText()).asJsonObject
            assertEquals(
                setOf(
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
                ),
                json.keySet()
            )
            assertEquals(1, json.get("schemaVersion").asInt)
            assertEquals(transaction.operation.name.lowercase(), json.get("operation").asString)
            assertEquals(transaction.phase.name.lowercase(), json.get("phase").asString)
        }
    }

    @Test
    fun `read quarantines malformed unknown and unsafe journal values`() {
        val cases = listOf<(String) -> String>(
            { "{invalid" },
            { it.replace("\"schemaVersion\":1", "\"schemaVersion\":2") },
            { it.replace("\"operation\":\"install\"", "\"operation\":\"upgrade\"") },
            { it.replace("\"phase\":\"prepared\"", "\"phase\":\"moving\"") },
            { it.replace(stagingName(), "/absolute.installing") },
            { it.replace(stagingName(), "../escape.installing") }
        )

        cases.forEachIndexed { index, mutate ->
            val paths = paths("invalid-$index")
            val journal = ExtensionTransactionJournal(paths)
            journal.write(installTransaction())
            paths.extensionTransactionFile.writeText(mutate(paths.extensionTransactionFile.readText()))

            assertNull(journal.read())
            assertFalse(paths.extensionTransactionFile.exists())
            val diagnostic = paths.quarantineDir.walkTopDown()
                .first { it.isFile && it.name == "diagnostic.json" }
            assertEquals(
                "invalid_extension_transaction",
                JsonParser.parseString(diagnostic.readText()).asJsonObject.get("reason").asString
            )
            assertTrue(
                paths.quarantineDir.walkTopDown().any {
                    it.isFile && it.name == "extension-transaction.json"
                }
            )
        }
    }

    @Test
    fun `read quarantines every nonstandard JSON syntax and trailing content`() {
        val valid = encoded(installTransaction())
        val cases = listOf(
            "single-quotes" to valid.replace('"', '\''),
            "unquoted-name" to valid.replaceFirst("\"schemaVersion\"", "schemaVersion"),
            "comment" to valid.replaceFirst("{", "{/*comment*/"),
            "duplicate-field" to valid.replaceFirst(
                "\"schemaVersion\":1",
                "\"schemaVersion\":1,\"schemaVersion\":1"
            ),
            "trailing-content" to "$valid true"
        )

        cases.forEach { (name, contents) ->
            assertJournalQuarantined("strict-$name", contents)
        }
    }

    @Test
    fun `read quarantines each missing field and every extra field`() {
        val valid = JsonParser.parseString(encoded(installTransaction())).asJsonObject

        FIELD_NAMES.forEach { field ->
            val missing = valid.deepCopy().apply { remove(field) }
            assertJournalQuarantined("missing-$field", missing.toString())
        }

        val extra = valid.deepCopy().apply { addProperty("unexpected", true) }
        assertJournalQuarantined("extra-field", extra.toString())
    }

    @Test
    fun `read quarantines a wrong JSON type for every field`() {
        val invalidValues = mapOf(
            "schemaVersion" to JsonPrimitive("1"),
            "transactionId" to JsonPrimitive(1),
            "operation" to JsonPrimitive(true),
            "phase" to JsonPrimitive(1),
            "folderName" to JsonPrimitive(false),
            "oldRecord" to JsonPrimitive("record"),
            "newRecord" to JsonPrimitive("record"),
            "stagingName" to JsonPrimitive(1),
            "backupName" to JsonPrimitive(false),
            "trashName" to JsonPrimitive(1)
        )

        invalidValues.forEach { (field, value) ->
            val invalid = JsonParser.parseString(encoded(installTransaction())).asJsonObject.apply {
                add(field, value)
            }
            assertJournalQuarantined("wrong-type-$field", invalid.toString())
        }
    }

    @Test
    fun `read quarantines noncanonical UUID`() {
        val invalid = JsonParser.parseString(encoded(installTransaction())).asJsonObject.apply {
            addProperty("transactionId", TRANSACTION_ID.uppercase())
        }

        assertJournalQuarantined("noncanonical-uuid", invalid.toString())
    }

    @Test
    fun `read quarantines every invalid install field combination`() {
        val cases = listOf(
            "old-record-present" to mapOf("oldRecord" to recordElement("old")),
            "new-record-missing" to mapOf("newRecord" to null),
            "staging-missing" to mapOf("stagingName" to null),
            "backup-present" to mapOf("backupName" to JsonPrimitive(backupName())),
            "trash-present" to mapOf("trashName" to JsonPrimitive(trashName()))
        )

        cases.forEach { (name, replacements) ->
            assertJournalQuarantined(
                "install-$name",
                replaceFields(installTransaction(), replacements)
            )
        }
    }

    @Test
    fun `read quarantines every invalid update field combination`() {
        val cases = listOf(
            "old-record-missing" to mapOf("oldRecord" to null),
            "new-record-missing" to mapOf("newRecord" to null),
            "staging-missing" to mapOf("stagingName" to null),
            "backup-missing" to mapOf("backupName" to null),
            "trash-present" to mapOf("trashName" to JsonPrimitive(trashName()))
        )

        cases.forEach { (name, replacements) ->
            assertJournalQuarantined(
                "update-$name",
                replaceFields(updateTransaction(), replacements)
            )
        }
    }

    @Test
    fun `read quarantines every invalid delete field combination`() {
        val cases = listOf(
            "old-record-missing" to mapOf("oldRecord" to null),
            "new-record-present" to mapOf("newRecord" to recordElement("new")),
            "staging-present" to mapOf("stagingName" to JsonPrimitive(stagingName())),
            "backup-present" to mapOf("backupName" to JsonPrimitive(backupName())),
            "trash-missing" to mapOf("trashName" to null)
        )

        cases.forEach { (name, replacements) ->
            assertJournalQuarantined(
                "delete-$name",
                replaceFields(deleteTransaction(), replacements)
            )
        }
    }

    @Test
    fun `write rejects every operation field cross constraint`() {
        val paths = paths("operation-constraints")
        val journal = ExtensionTransactionJournal(paths)
        val old = record("old")
        val new = record("new")
        val install = installTransaction()
        val update = updateTransaction()
        val delete = deleteTransaction()
        val invalid = listOf(
            install.copy(oldRecord = old),
            install.copy(newRecord = null),
            install.copy(stagingName = null),
            install.copy(backupName = backupName()),
            install.copy(trashName = trashName()),
            update.copy(oldRecord = null),
            update.copy(newRecord = null),
            update.copy(stagingName = null),
            update.copy(backupName = null),
            update.copy(trashName = trashName()),
            delete.copy(oldRecord = null),
            delete.copy(newRecord = new),
            delete.copy(stagingName = stagingName()),
            delete.copy(backupName = backupName()),
            delete.copy(trashName = null)
        )

        invalid.forEach { transaction ->
            assertThrows(IllegalArgumentException::class.java) {
                journal.write(transaction)
            }
        }
        assertFalse(paths.extensionTransactionFile.exists())
    }

    @Test
    fun `write rejects mismatched records noncanonical UUID and inexact transaction names`() {
        val journal = ExtensionTransactionJournal(paths("identity-constraints"))
        val mismatched = record("other").copy(folderName = "Other")
        val otherId = "123e4567-e89b-12d3-a456-426614174001"
        val invalid = listOf(
            installTransaction().copy(newRecord = mismatched),
            updateTransaction().copy(oldRecord = mismatched),
            updateTransaction().copy(newRecord = mismatched),
            installTransaction().copy(transactionId = "not-a-uuid"),
            installTransaction().copy(transactionId = TRANSACTION_ID.uppercase()),
            installTransaction().copy(stagingName = "safe-name"),
            installTransaction().copy(stagingName = ".stapk-txn-$otherId.installing"),
            installTransaction().copy(stagingName = ".stapk-txn-$TRANSACTION_ID.backup"),
            updateTransaction().copy(backupName = "safe-name"),
            deleteTransaction().copy(trashName = "safe-name")
        )

        invalid.forEach { transaction ->
            assertThrows(IllegalArgumentException::class.java) {
                journal.write(transaction)
            }
        }
    }

    @Test
    fun `clear is idempotent and invokes the remover only for an existing journal`() {
        val paths = paths("clear")
        var removals = 0
        val journal = ExtensionTransactionJournal(
            paths,
            AtomicFileStore(paths.quarantineDir),
            fileRemover = { file ->
                removals += 1
                file.delete()
            }
        )

        journal.clear()
        assertEquals(0, removals)
        journal.write(installTransaction())
        journal.clear()
        journal.clear()

        assertEquals(1, removals)
        assertFalse(paths.extensionTransactionFile.exists())
    }

    @Test
    fun `clear exposes an injected deletion failure and preserves the journal`() {
        val paths = paths("clear-failure")
        val journal = ExtensionTransactionJournal(
            paths,
            AtomicFileStore(paths.quarantineDir),
            fileRemover = { false }
        )
        journal.write(installTransaction())

        assertThrows(IOException::class.java) { journal.clear() }
        assertTrue(paths.extensionTransactionFile.isFile)
    }

    private fun paths(name: String) =
        NativeAdapterPaths(Files.createTempDirectory("stapk-extension-journal-$name").toFile())

    private fun encoded(transaction: ExtensionTransaction): String {
        val paths = paths("encoded")
        ExtensionTransactionJournal(paths).write(transaction)
        return paths.extensionTransactionFile.readText(Charsets.UTF_8)
    }

    private fun replaceFields(
        transaction: ExtensionTransaction,
        replacements: Map<String, com.google.gson.JsonElement?>
    ): String = JsonParser.parseString(encoded(transaction)).asJsonObject.apply {
        replacements.forEach { (field, value) ->
            if (value == null) add(field, com.google.gson.JsonNull.INSTANCE) else add(field, value)
        }
    }.toString()

    private fun recordElement(commit: String) = JsonParser.parseString(ExtensionRecordCodec.encode(record(commit)))

    private fun assertJournalQuarantined(name: String, contents: String) {
        val paths = paths(name)
        paths.extensionTransactionFile.parentFile?.mkdirs()
        paths.extensionTransactionFile.writeText(contents, Charsets.UTF_8)

        assertNull("$name should be rejected", ExtensionTransactionJournal(paths).read())
        assertFalse("$name should remove the active journal", paths.extensionTransactionFile.exists())
        val diagnostic = paths.quarantineDir.walkTopDown()
            .first { it.isFile && it.name == "diagnostic.json" }
        assertEquals(
            "$name should use the journal quarantine reason",
            "invalid_extension_transaction",
            JsonParser.parseString(diagnostic.readText()).asJsonObject.get("reason").asString
        )
        assertTrue(
            "$name should preserve the rejected journal",
            paths.quarantineDir.walkTopDown().any {
                it.isFile && it.name == "extension-transaction.json"
            }
        )
    }

    private fun installTransaction() = ExtensionTransaction(
        transactionId = TRANSACTION_ID,
        operation = ExtensionOperation.INSTALL,
        phase = ExtensionTransactionPhase.PREPARED,
        folderName = FOLDER,
        oldRecord = null,
        newRecord = record("new"),
        stagingName = stagingName(),
        backupName = null,
        trashName = null
    )

    private fun updateTransaction() = ExtensionTransaction(
        transactionId = TRANSACTION_ID,
        operation = ExtensionOperation.UPDATE,
        phase = ExtensionTransactionPhase.FILES_ACTIVATED,
        folderName = FOLDER,
        oldRecord = record("old"),
        newRecord = record("new"),
        stagingName = stagingName(),
        backupName = backupName(),
        trashName = null
    )

    private fun deleteTransaction() = ExtensionTransaction(
        transactionId = TRANSACTION_ID,
        operation = ExtensionOperation.DELETE,
        phase = ExtensionTransactionPhase.REGISTRY_COMMITTED,
        folderName = FOLDER,
        oldRecord = record("old"),
        newRecord = null,
        stagingName = null,
        backupName = null,
        trashName = trashName()
    )

    private fun record(commit: String) = ExtensionRecord(
        folderName = FOLDER,
        repositoryUrl = "https://github.com/owner/$FOLDER",
        owner = "owner",
        repository = FOLDER,
        branch = "main",
        commitSha = commit,
        installedAt = 1L,
        updatedAt = 2L
    )

    private fun stagingName() = ".stapk-txn-$TRANSACTION_ID.installing"
    private fun backupName() = ".stapk-txn-$TRANSACTION_ID.backup"
    private fun trashName() = ".stapk-txn-$TRANSACTION_ID.trash"

    private companion object {
        const val TRANSACTION_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val FOLDER = "ST-Prompt-Template"
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
