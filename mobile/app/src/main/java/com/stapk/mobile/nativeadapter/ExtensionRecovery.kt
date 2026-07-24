package com.stapk.mobile.nativeadapter

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

data class ExtensionRecoveryResult(
    val ready: Boolean,
    val recoveredOperations: Int,
    val quarantinedDirectories: Int
)

class ExtensionRecovery(
    private val paths: NativeAdapterPaths,
    private val registry: ExtensionRegistry,
    private val journal: ExtensionTransactionJournal,
    private val quarantine: ExtensionDirectoryQuarantine,
    private val coordinator: ExtensionMutationCoordinator,
    private val diagnosticLogger: DiagnosticLogger? = null,
    private val directoryMover: (File, File) -> Unit = ::moveDirectoryAtomically,
    private val directoryRemover: (File) -> Boolean = ::deleteDirectoryTreeNoFollow,
    private val symbolicLinkChecker: (File) -> Boolean = { Files.isSymbolicLink(it.toPath()) },
    private val directoryLister: (File) -> Array<File>? = File::listFiles,
    private val conflictDiagnosticWriter: ((File, String) -> Unit)? = null
) {
    private val sidecarStore = AtomicFileStore(paths.quarantineDir)
    private val extensionsRoot: Path = paths.extensionsDir.canonicalFile.toPath().toAbsolutePath().normalize()

    fun recover(): ExtensionRecoveryResult = coordinator.underLock {
        val counts = RecoveryCounts()
        val result = try {
            recoverLocked(counts)
            ExtensionRecoveryResult(true, counts.recovered, counts.quarantined)
        } catch (_: Exception) {
            ExtensionRecoveryResult(false, counts.recovered, counts.quarantined)
        }
        coordinator.setRecoveryReady(result.ready)
        runCatching {
            diagnosticLogger?.event(
                DiagnosticArea.STORAGE,
                "extension_recovery",
                mapOf(
                    "result" to if (result.ready) "ready" else "not_ready",
                    "recoveredCount" to result.recoveredOperations.toString(),
                    "quarantinedCount" to result.quarantinedDirectories.toString()
                )
            )
        }
        result
    }

    private fun recoverLocked(counts: RecoveryCounts) {
        val journalEntryExisted = entryExists(paths.extensionTransactionFile)
        if (journalEntryExisted && !isRegularFile(paths.extensionTransactionFile)) {
            throw RecoveryConflictException()
        }
        val transaction = journal.read()
        if (entryExists(paths.extensionTransactionFile) && transaction == null) {
            throw RecoveryConflictException()
        }

        if (transaction != null) {
            val registryEntryExisted = entryExists(paths.extensionRegistryFile)
            if (registryEntryExisted && !isRegularFile(paths.extensionRegistryFile)) {
                throw RecoveryConflictException()
            }
            val records = registry.list()
            val registryWasQuarantined = registryEntryExisted && !entryExists(paths.extensionRegistryFile)
            if (registryWasQuarantined) throw RecoveryConflictException()
            recoverTransaction(transaction, records, counts)
            journal.clear()
            counts.recovered += 1
            return
        }
        if (journalEntryExisted && entryExists(paths.extensionTransactionFile)) {
            throw RecoveryConflictException()
        }

        val entries = extensionDirectorySnapshot().toMutableList()
        val registryEntryExisted = entryExists(paths.extensionRegistryFile)
        if (registryEntryExisted && !isRegularFile(paths.extensionRegistryFile)) {
            throw RecoveryConflictException()
        }
        val records = registry.list()
        val registryWasQuarantined = registryEntryExisted && !entryExists(paths.extensionRegistryFile)
        reconcileWithoutJournal(records, registryWasQuarantined, entries, counts)
    }

    private fun recoverTransaction(
        transaction: ExtensionTransaction,
        records: List<ExtensionRecord>,
        counts: RecoveryCounts
    ) {
        val current = records.firstOrNull {
            it.folderName.equals(transaction.folderName, ignoreCase = true)
        }
        when (transaction.operation) {
            ExtensionOperation.INSTALL -> recoverInstall(transaction, current, counts)
            ExtensionOperation.UPDATE -> recoverUpdate(transaction, current, counts)
            ExtensionOperation.DELETE -> recoverDelete(transaction, current)
        }
    }

    private fun recoverInstall(
        transaction: ExtensionTransaction,
        current: ExtensionRecord?,
        counts: RecoveryCounts
    ) {
        val newRecord = requireNotNull(transaction.newRecord)
        val target = extensionChild(transaction.folderName)
        val staging = extensionChild(requireNotNull(transaction.stagingName))
        when {
            current == null && !entryExists(target) && stagingMatches(staging, newRecord) &&
                transaction.phase == ExtensionTransactionPhase.PREPARED -> removeDirectory(staging)

            current == null && !entryExists(target) && !entryExists(staging) &&
                transaction.phase != ExtensionTransactionPhase.REGISTRY_COMMITTED -> Unit

            current == null && targetMatches(target, newRecord) && !entryExists(staging) &&
                transaction.phase != ExtensionTransactionPhase.REGISTRY_COMMITTED -> {
                quarantineDirectory(target, "install_rollback", counts)
            }

            current == newRecord && targetMatches(target, newRecord) && !entryExists(staging) &&
                transaction.phase != ExtensionTransactionPhase.PREPARED -> {
                ensureAuthoritativeSidecar(target, newRecord, counts)
            }

            else -> throw RecoveryConflictException()
        }
    }

    private fun recoverUpdate(
        transaction: ExtensionTransaction,
        current: ExtensionRecord?,
        counts: RecoveryCounts
    ) {
        val oldRecord = requireNotNull(transaction.oldRecord)
        val newRecord = requireNotNull(transaction.newRecord)
        val target = extensionChild(transaction.folderName)
        val staging = extensionChild(requireNotNull(transaction.stagingName))
        val backup = extensionChild(requireNotNull(transaction.backupName))
        when {
            current == oldRecord && targetMatches(target, oldRecord) && !entryExists(backup) &&
                (stagingMatches(staging, newRecord) || !entryExists(staging)) &&
                transaction.phase == ExtensionTransactionPhase.PREPARED -> {
                if (entryExists(staging)) removeDirectory(staging)
            }

            current == oldRecord && targetMatches(target, oldRecord) && !entryExists(backup) &&
                !entryExists(staging) && transaction.phase == ExtensionTransactionPhase.FILES_ACTIVATED -> Unit

            current == oldRecord && !entryExists(target) && transactionDirectoryMatches(backup, oldRecord) &&
                (
                    transaction.phase == ExtensionTransactionPhase.PREPARED &&
                        (stagingMatches(staging, newRecord) || !entryExists(staging)) ||
                        transaction.phase == ExtensionTransactionPhase.FILES_ACTIVATED && !entryExists(staging)
                    ) -> {
                directoryMover(backup, target)
                if (entryExists(staging)) removeDirectory(staging)
            }

            current == oldRecord && targetMatches(target, newRecord) &&
                transactionDirectoryMatches(backup, oldRecord) && !entryExists(staging) &&
                transaction.phase != ExtensionTransactionPhase.REGISTRY_COMMITTED -> {
                quarantineDirectory(target, "update_rollback", counts)
                directoryMover(backup, target)
            }

            current == newRecord && targetMatches(target, newRecord) && transactionDirectoryMatches(backup, oldRecord) &&
                !entryExists(staging) && transaction.phase != ExtensionTransactionPhase.PREPARED -> {
                ensureAuthoritativeSidecar(target, newRecord, counts)
                removeDirectory(backup)
            }

            current == newRecord && targetMatches(target, newRecord) && !entryExists(backup) && !entryExists(staging) &&
                transaction.phase != ExtensionTransactionPhase.PREPARED -> {
                ensureAuthoritativeSidecar(target, newRecord, counts)
            }

            else -> throw RecoveryConflictException()
        }
    }

    private fun recoverDelete(transaction: ExtensionTransaction, current: ExtensionRecord?) {
        val oldRecord = requireNotNull(transaction.oldRecord)
        val target = extensionChild(transaction.folderName)
        val trash = extensionChild(requireNotNull(transaction.trashName))
        when {
            current == oldRecord && targetMatches(target, oldRecord) && !entryExists(trash) &&
                transaction.phase != ExtensionTransactionPhase.REGISTRY_COMMITTED -> Unit

            current == oldRecord && !entryExists(target) && transactionDirectoryMatches(trash, oldRecord) &&
                transaction.phase != ExtensionTransactionPhase.REGISTRY_COMMITTED -> {
                directoryMover(trash, target)
            }

            current == null && !entryExists(target) && transactionDirectoryMatches(trash, oldRecord) &&
                transaction.phase != ExtensionTransactionPhase.PREPARED -> removeDirectory(trash)

            current == null && !entryExists(target) && !entryExists(trash) &&
                transaction.phase != ExtensionTransactionPhase.PREPARED -> Unit

            else -> throw RecoveryConflictException()
        }
    }

    private fun reconcileWithoutJournal(
        initialRecords: List<ExtensionRecord>,
        registryWasQuarantined: Boolean,
        entries: MutableList<File>,
        counts: RecoveryCounts
    ) {
        val desiredRecords = initialRecords.toMutableList()
        var registryChanged = registryWasQuarantined

        reconcileLegacyPrevious(initialRecords, entries, counts)

        initialRecords.forEach { record ->
            val target = exactExtensionChild(record.folderName, entries)
            when {
                target == null -> {
                    desiredRecords.remove(record)
                    registryChanged = true
                    counts.recovered += 1
                }
                isDirectory(target) -> ensureAuthoritativeSidecar(target, record, counts)
                else -> {
                    throw RecoveryConflictException()
                }
            }
        }

        reconcileTransactionOrphans(entries, counts)

        val registeredBasenames = desiredRecords.map(ExtensionRecord::folderName).toSet()
        val candidates = mutableListOf<SidecarCandidate>()
        regularTargetDirectories(entries).forEach { target ->
            if (isExactRegisteredExtensionTargetBasename(target.name, registeredBasenames)) return@forEach
            val sidecarRecord = readBoundSidecar(target)
            if (sidecarRecord == null) {
                quarantineDirectory(target, "invalid_unregistered_extension", counts)
                entries.remove(target)
            } else {
                candidates += SidecarCandidate(target, sidecarRecord)
            }
        }

        recoverPendingConflictGroups(candidates, entries, counts)

        val candidateConflictGroups = candidateConflictGroups(candidates)
        val candidateConflicts = candidateConflictGroups.flatten().toSet()
        candidateConflictGroups.forEach { group ->
            quarantineConflictGroup(group, entries, counts)
        }
        val registeredConflicts = candidates
            .filterNot(candidateConflicts::contains)
            .filter { candidate -> initialRecords.any { recordsConflict(it, candidate.record) } }
        registeredConflicts.forEach {
            quarantineDirectory(it.directory, REGISTERED_CONFLICT_REASON, counts)
            entries.remove(it.directory)
        }
        val conflicting = candidateConflicts + registeredConflicts
        val survivors = candidates.filterNot(conflicting::contains)
        if (survivors.isNotEmpty()) {
            desiredRecords += survivors.map(SidecarCandidate::record)
            registryChanged = true
        }

        if (registryChanged) registry.replaceAll(desiredRecords)
    }

    private fun reconcileLegacyPrevious(
        records: List<ExtensionRecord>,
        entries: MutableList<File>,
        counts: RecoveryCounts
    ) {
        val recordsByFolder = records.associateBy { it.folderName }
        entries.toList().forEach { entry ->
            val match = LEGACY_PREVIOUS.matchEntire(entry.name) ?: return@forEach
            if (!isDirectory(entry)) throw RecoveryConflictException()
            val record = recordsByFolder[match.groupValues[1]]
            val target = record?.let { extensionChild(it.folderName) }
            val targetInSnapshot = record?.let { exactExtensionChild(it.folderName, entries) }
            if (record != null && target != null && targetInSnapshot == null) {
                directoryMover(entry, target)
                entries.remove(entry)
                entries += target
                counts.recovered += 1
            } else {
                quarantineDirectory(entry, "orphan_legacy_previous", counts)
                entries.remove(entry)
            }
        }
    }

    private fun reconcileTransactionOrphans(entries: MutableList<File>, counts: RecoveryCounts) {
        entries.toList().forEach { entry ->
            val match = TRANSACTION_DIRECTORY.matchEntire(entry.name) ?: return@forEach
            if (!isDirectory(entry)) throw RecoveryConflictException()
            when (match.groupValues[2]) {
                "installing" -> {
                    removeDirectory(entry)
                    counts.recovered += 1
                }
                "backup", "trash" -> quarantineDirectory(entry, "orphan_extension_transaction", counts)
            }
            entries.remove(entry)
        }
    }

    private fun recoverPendingConflictGroups(
        candidates: MutableList<SidecarCandidate>,
        entries: MutableList<File>,
        counts: RecoveryCounts
    ) {
        val diagnostics = conflictDiagnostics()
        val pendingGroupIds = diagnostics
            .filter { it.status == ConflictGroupStatus.PENDING }
            .map { it.key.id }
            .distinct()
        pendingGroupIds.forEach { groupId ->
            val groupDiagnostics = diagnostics.filter { it.key.id == groupId }
            val keys = groupDiagnostics.map(ConflictDiagnostic::key).distinct()
            if (keys.size != 1) throw RecoveryConflictException()
            val key = keys.single()
            val witnesses = strictConflictWitnesses(groupDiagnostics)
            val diagnosticSources = groupDiagnostics.map(ConflictDiagnostic::source).toSet()
            val active = linkedSetOf<SidecarCandidate>()
            candidates.filterTo(active) { it.directory.name in diagnosticSources }
            var changed: Boolean
            do {
                val knownRecords = witnesses.values + active.map(SidecarCandidate::record)
                val connected = candidates.filter { candidate ->
                    candidate !in active && knownRecords.any { recordsConflict(it, candidate.record) }
                }
                changed = active.addAll(connected)
            } while (changed)

            // 部分完成写入只需将剩余 pending 诊断改写为 completed。
            if (
                active.isEmpty() &&
                groupDiagnostics.size == key.expectedCount &&
                diagnosticSources.size == key.expectedCount &&
                groupDiagnostics.filter { it.status == ConflictGroupStatus.PENDING }.all { it.witness != null } &&
                groupDiagnostics.any { it.status == ConflictGroupStatus.COMPLETED }
            ) {
                completeConflictGroup(key, groupDiagnostics)
                return@forEach
            }

            val participants = linkedMapOf<String, ExtensionRecord>()
            witnesses.forEach { (source, record) -> participants.putParticipant(source, record) }
            active.forEach { participants.putParticipant(it.directory.name, it.record) }
            if (participants.size != key.expectedCount || !recordsFormConnectedGroup(participants.values.toList())) {
                throw RecoveryConflictException()
            }
            active.forEach { candidate ->
                quarantineDirectory(candidate.directory, CONFLICT_REASON, counts, key.pendingOperation())
                entries.remove(candidate.directory)
                candidates.remove(candidate)
            }
            val refreshed = conflictDiagnostics().filter { it.key == key }
            if (strictConflictWitnesses(refreshed).size != key.expectedCount) throw RecoveryConflictException()
            completeConflictGroup(key, refreshed)
        }
    }

    private fun quarantineConflictGroup(
        group: List<SidecarCandidate>,
        entries: MutableList<File>,
        counts: RecoveryCounts
    ) {
        val key = ConflictGroupKey(UUID.randomUUID().toString(), group.size)
        group.forEach { candidate ->
            quarantineDirectory(candidate.directory, CONFLICT_REASON, counts, key.pendingOperation())
            entries.remove(candidate.directory)
        }
        val diagnostics = conflictDiagnostics().filter { it.key == key }
        if (strictConflictWitnesses(diagnostics).size != key.expectedCount) throw RecoveryConflictException()
        completeConflictGroup(key, diagnostics)
    }

    private fun candidateConflictGroups(candidates: List<SidecarCandidate>): List<List<SidecarCandidate>> {
        val remaining = candidates.filter { candidate ->
            candidates.any { other -> other != candidate && recordsConflict(candidate.record, other.record) }
        }.toMutableSet()
        return buildList {
            while (remaining.isNotEmpty()) {
                val group = linkedSetOf(remaining.first())
                var changed: Boolean
                do {
                    val connected = remaining.filter { candidate ->
                        group.any { recordsConflict(it.record, candidate.record) }
                    }
                    changed = group.addAll(connected)
                } while (changed)
                remaining.removeAll(group)
                add(group.toList())
            }
        }
    }

    private fun conflictDiagnostics(): List<ConflictDiagnostic> {
        val root = paths.quarantineDir.resolve("extensions")
        if (!entryExists(root)) return emptyList()
        if (!isDirectory(root)) throw RecoveryConflictException()
        val batches = root.listFiles() ?: throw IOException("Unable to enumerate extension quarantine directory")
        return batches.mapNotNull { batch -> readConflictDiagnostic(root, batch) }
    }

    private fun readConflictDiagnostic(root: File, batch: File): ConflictDiagnostic? {
        val batchPath = batch.toPath().toAbsolutePath().normalize()
        if (batchPath.parent != root.toPath().toAbsolutePath().normalize()) throw RecoveryConflictException()
        val batchMatch = QUARANTINE_BATCH.matchEntire(batch.name) ?: return null
        if (!isDirectory(batch)) throw RecoveryConflictException()
        val hasStrictWitness = hasStrictExtensionSidecarWitness(batch)
        val diagnosticFile = batch.resolve("diagnostic.json")
        if (!isRegularFile(diagnosticFile)) {
            if (hasStrictWitness) throw RecoveryConflictException()
            return null
        }
        val diagnostic = runCatching {
            parseStrictJson(diagnosticFile.readText(Charsets.UTF_8)).asJsonObject
        }.getOrElse {
            if (hasStrictWitness) throw RecoveryConflictException()
            return null
        }
        val protocol = runCatching {
            if (diagnostic.keySet() != DIAGNOSTIC_FIELDS) throw RecoveryConflictException()
            ConflictDiagnosticProtocol(
                reason = diagnostic.strictString("reason"),
                source = diagnostic.strictString("source"),
                operation = diagnostic.strictString("operation"),
                timestamp = diagnostic.strictLong("timestamp")
            )
        }.getOrElse {
            if (hasStrictWitness) throw RecoveryConflictException()
            return null
        }
        val operationMatch = CONFLICT_OPERATION.matchEntire(protocol.operation)
        val conflictReason = protocol.reason == CONFLICT_REASON
        if (conflictReason != (operationMatch != null)) throw RecoveryConflictException()
        if (!conflictReason) return null

        val source = protocol.source
        if (!EXTENSION_FOLDER.matches(source)) throw RecoveryConflictException()
        operationMatch ?: throw RecoveryConflictException()
        val groupId = operationMatch.groupValues[1]
        if (runCatching { UUID.fromString(groupId).toString() }.getOrNull() != groupId) {
            throw RecoveryConflictException()
        }
        val expectedCount = operationMatch.groupValues[2].toIntOrNull()?.takeIf { it >= 2 }
            ?: throw RecoveryConflictException()
        val status = when (operationMatch.groupValues[3]) {
            "pending" -> ConflictGroupStatus.PENDING
            "completed" -> ConflictGroupStatus.COMPLETED
            else -> throw RecoveryConflictException()
        }
        val timestamp = protocol.timestamp.takeIf { it >= 0L } ?: throw RecoveryConflictException()
        if (batchMatch.groupValues[1].toLongOrNull() != timestamp) throw RecoveryConflictException()
        val witness = if (status == ConflictGroupStatus.PENDING) {
            val witnessDirectory = batch.resolve(source)
            when {
                !entryExists(witnessDirectory) -> null
                isDirectory(witnessDirectory) -> readBoundSidecar(witnessDirectory)?.let {
                    SidecarCandidate(witnessDirectory, it)
                } ?: throw RecoveryConflictException()
                else -> throw RecoveryConflictException()
            }
        } else {
            null
        }
        return ConflictDiagnostic(
            diagnosticFile,
            source,
            ConflictGroupKey(groupId, expectedCount),
            status,
            timestamp,
            witness
        )
    }

    private fun hasStrictExtensionSidecarWitness(batch: File): Boolean {
        val entries = batch.listFiles() ?: throw IOException("Unable to enumerate extension quarantine batch")
        return entries.any { entry ->
            EXTENSION_FOLDER.matches(entry.name) && isDirectory(entry) && readBoundSidecar(entry) != null
        }
    }

    private fun parseStrictJson(text: String): JsonElement = JsonReader(StringReader(text)).use { reader ->
        reader.setStrictness(Strictness.STRICT)
        val element = readStrictJsonElement(reader)
        if (reader.peek() != JsonToken.END_DOCUMENT) throw RecoveryConflictException()
        element
    }

    private fun readStrictJsonElement(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JsonObject().also { result ->
            val names = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (!names.add(name)) throw RecoveryConflictException()
                result.add(name, readStrictJsonElement(reader))
            }
            reader.endObject()
        }

        JsonToken.BEGIN_ARRAY -> JsonArray().also { result ->
            reader.beginArray()
            while (reader.hasNext()) result.add(readStrictJsonElement(reader))
            reader.endArray()
        }

        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> JsonNull.INSTANCE.also { reader.nextNull() }
        else -> throw RecoveryConflictException()
    }

    private fun strictConflictWitnesses(diagnostics: List<ConflictDiagnostic>): Map<String, ExtensionRecord> =
        linkedMapOf<String, ExtensionRecord>().apply {
            diagnostics.mapNotNull(ConflictDiagnostic::witness).forEach { witness ->
                putParticipant(witness.directory.name, witness.record)
            }
        }

    private fun MutableMap<String, ExtensionRecord>.putParticipant(source: String, record: ExtensionRecord) {
        val previous = putIfAbsent(source, record)
        if (previous != null && previous != record) throw RecoveryConflictException()
    }

    private fun recordsFormConnectedGroup(records: List<ExtensionRecord>): Boolean {
        if (records.size < 2) return false
        val connected = linkedSetOf(records.first())
        var changed: Boolean
        do {
            val additions = records.filter { candidate ->
                candidate !in connected && connected.any { recordsConflict(it, candidate) }
            }
            changed = connected.addAll(additions)
        } while (changed)
        return connected.size == records.size
    }

    private fun completeConflictGroup(key: ConflictGroupKey, diagnostics: List<ConflictDiagnostic>) {
        diagnostics
            .filter { it.status == ConflictGroupStatus.PENDING }
            .sortedBy { it.file.path }
            .forEach { diagnostic ->
                val text = JsonObject().apply {
                        addProperty("reason", CONFLICT_REASON)
                        addProperty("source", diagnostic.source)
                        addProperty("operation", key.completedOperation())
                        addProperty("timestamp", diagnostic.timestamp)
                    }.toString()
                conflictDiagnosticWriter?.invoke(diagnostic.file, text)
                    ?: sidecarStore.writeText(diagnostic.file, text)
            }
    }

    private fun JsonObject.strictString(name: String): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?: throw RecoveryConflictException()

    private fun JsonObject.strictLong(name: String): Long = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString
        ?.toLongOrNull()
        ?: throw RecoveryConflictException()

    private fun regularTargetDirectories(entries: List<File>): List<File> = entries
        .filter { EXTENSION_FOLDER.matches(it.name) }
        .onEach { if (!isDirectory(it)) throw RecoveryConflictException() }

    private fun extensionDirectorySnapshot(): List<File> {
        if (!entryExists(paths.extensionsDir)) {
            check(paths.extensionsDir.mkdirs()) { "Unable to create extensions directory" }
            return emptyList()
        }
        if (!isDirectory(paths.extensionsDir)) throw RecoveryConflictException()
        val listedRoot = paths.extensionsDir.toPath().toAbsolutePath().normalize()
        val entries = directoryLister(paths.extensionsDir)
            ?: throw IOException("Unable to enumerate extension directory")
        return entries.map { entry ->
            val listedEntry = entry.toPath().toAbsolutePath().normalize()
            if (listedEntry.parent != listedRoot) throw RecoveryConflictException()
            extensionChild(entry.name)
        }.also { snapshot ->
            if (snapshot.map(File::getName).toSet().size != snapshot.size) throw RecoveryConflictException()
        }
    }

    private fun readBoundSidecar(target: File): ExtensionRecord? {
        val sidecar = target.resolve(SIDECAR)
        if (!isRegularFile(sidecar)) return null
        val record = runCatching {
            ExtensionRecordCodec.decode(sidecar.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return record.takeIf { it.folderName == target.name }
    }

    private fun ensureAuthoritativeSidecar(
        target: File,
        record: ExtensionRecord,
        counts: RecoveryCounts
    ) {
        val sidecar = target.resolve(SIDECAR)
        val existing = if (isRegularFile(sidecar)) {
            runCatching { ExtensionRecordCodec.decode(sidecar.readText(Charsets.UTF_8)) }.getOrNull()
        } else {
            null
        }
        if (existing == record && existing.folderName == target.name) return
        if (entryExists(sidecar)) {
            if (isSymbolicLink(sidecar)) {
                quarantineSidecarEntry(target, sidecar)
            } else if (isRegularFile(sidecar)) {
                sidecarStore.quarantine(sidecar, "invalid_extension_sidecar")
            } else if (isDirectory(sidecar)) {
                quarantineSidecarEntry(target, sidecar)
                counts.quarantined += 1
            } else {
                throw RecoveryConflictException()
            }
        }
        sidecarStore.writeText(sidecar, ExtensionRecordCodec.encode(record))
        counts.recovered += 1
    }

    private fun quarantineSidecarEntry(target: File, sidecar: File) {
        val batch = paths.quarantineDir.resolve("extension-sidecars/${System.currentTimeMillis()}-${UUID.randomUUID()}")
        val destination = batch.resolve("${target.name}/$SIDECAR")
        destination.parentFile?.mkdirs()
        sidecarStore.writeText(
            batch.resolve("diagnostic.json"),
            JsonObject().apply {
                addProperty("reason", "invalid_extension_sidecar")
                addProperty("source", "${target.name}/$SIDECAR")
            }.toString()
        )
        directoryMover(sidecar, destination)
    }

    private fun quarantineDirectory(
        source: File,
        reason: String,
        counts: RecoveryCounts,
        operation: String = "recovery"
    ) {
        quarantine.move(source, reason, operation)
        counts.quarantined += 1
    }

    private fun removeDirectory(directory: File) {
        if (!entryExists(directory)) return
        if (!isDirectory(directory)) throw RecoveryConflictException()
        if (!isSafeDirectoryTree(directory)) throw RecoveryConflictException()
        if (!directoryRemover(directory)) {
            throw IOException("Unable to remove extension recovery directory")
        }
    }

    private fun isSafeDirectoryTree(directory: File): Boolean {
        val entries = directory.listFiles() ?: return false
        return entries.all { entry ->
            when {
                isSymbolicLink(entry) -> false
                isDirectory(entry) -> isSafeDirectoryTree(entry)
                isRegularFile(entry) -> true
                else -> false
            }
        }
    }

    private fun targetMatches(directory: File, record: ExtensionRecord): Boolean =
        isDirectory(directory) && readBoundSidecar(directory) == record

    private fun stagingMatches(directory: File, record: ExtensionRecord): Boolean =
        transactionDirectoryMatches(directory, record)

    private fun transactionDirectoryMatches(directory: File, record: ExtensionRecord): Boolean {
        if (!isDirectory(directory)) return false
        val sidecar = directory.resolve(SIDECAR)
        if (!isRegularFile(sidecar)) return false
        return runCatching {
            ExtensionRecordCodec.decode(sidecar.readText(Charsets.UTF_8)) == record
        }.getOrDefault(false)
    }

    private fun recordsConflict(first: ExtensionRecord, second: ExtensionRecord): Boolean =
        first.folderName.equals(second.folderName, ignoreCase = true) ||
            first.repositoryUrl.equals(second.repositoryUrl, ignoreCase = true)

    private fun extensionChild(name: String): File {
        val segments = SafePath.segments(name)
        require(segments.size == 1 && segments.single() == name) { "Extension entry must be a safe basename" }
        val candidate = extensionsRoot.resolve(name).normalize()
        require(candidate.parent == extensionsRoot && candidate.fileName.toString() == name) {
            "Extension entry must be a direct child"
        }
        return candidate.toFile()
    }

    private fun exactExtensionChild(name: String, entries: List<File>): File? =
        entries.firstOrNull { it.name == name }

    private fun entryExists(entry: File): Boolean = Files.exists(entry.toPath(), NOFOLLOW_LINKS)

    private fun isSymbolicLink(entry: File): Boolean = symbolicLinkChecker(entry)

    private fun isRegularFile(entry: File): Boolean =
        !isSymbolicLink(entry) && Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS)

    private fun isDirectory(entry: File): Boolean =
        !isSymbolicLink(entry) && Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS)

    private data class SidecarCandidate(val directory: File, val record: ExtensionRecord)

    private data class ConflictGroupKey(val id: String, val expectedCount: Int) {
        fun pendingOperation(): String = "recovery_conflict:$id:$expectedCount:pending"

        fun completedOperation(): String = "recovery_conflict:$id:$expectedCount:completed"
    }

    private enum class ConflictGroupStatus { PENDING, COMPLETED }

    private data class ConflictDiagnostic(
        val file: File,
        val source: String,
        val key: ConflictGroupKey,
        val status: ConflictGroupStatus,
        val timestamp: Long,
        val witness: SidecarCandidate?
    )

    private data class ConflictDiagnosticProtocol(
        val reason: String,
        val source: String,
        val operation: String,
        val timestamp: Long
    )

    private data class RecoveryCounts(var recovered: Int = 0, var quarantined: Int = 0)

    private class RecoveryConflictException : IllegalStateException("Extension recovery state is ambiguous")

    private companion object {
        const val SIDECAR = ".stapk-extension.json"
        const val CONFLICT_REASON = "conflicting_extension_record"
        const val REGISTERED_CONFLICT_REASON = "conflict_with_registered_extension"
        val DIAGNOSTIC_FIELDS = setOf("reason", "source", "operation", "timestamp")
        val EXTENSION_FOLDER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
        val LEGACY_PREVIOUS = Regex("\\.([A-Za-z0-9][A-Za-z0-9._-]{0,119})\\.previous")
        val TRANSACTION_DIRECTORY = Regex(
            "\\.stapk-txn-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(installing|backup|trash)"
        )
        val QUARANTINE_BATCH = Regex("([0-9]+)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val CONFLICT_OPERATION = Regex(
            "recovery_conflict:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):([1-9][0-9]*):(pending|completed)"
        )
    }
}

internal fun isExactRegisteredExtensionTargetBasename(
    targetBasename: String,
    registeredBasenames: Set<String>
): Boolean = targetBasename in registeredBasenames

private fun deleteDirectoryTreeNoFollow(directory: File): Boolean = try {
    Files.walkFileTree(
        directory.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
                if (exception != null) throw exception
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        }
    )
    true
} catch (_: IOException) {
    false
}
