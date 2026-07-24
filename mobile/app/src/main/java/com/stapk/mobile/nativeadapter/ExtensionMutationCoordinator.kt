package com.stapk.mobile.nativeadapter

import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ExtensionAlreadyInstalledException : IllegalStateException("Extension is already installed")

class ExtensionOperationConflictException : IllegalStateException("Extension operation is stale or conflicts")

class ExtensionRegistryWriteException(cause: Throwable) :
    IllegalStateException("Unable to write extension registry", cause)

class ExtensionTransactionException(cause: Throwable) :
    IllegalStateException("Unable to complete extension transaction", cause)

class ExtensionRecoveryRequiredException(cause: Throwable? = null) :
    IllegalStateException("Extension transaction recovery is required", cause)

internal interface ExtensionMutationGateProbe {
    fun beforeLockAcquire()

    fun afterLockAcquire()
}

private object NoOpExtensionMutationGateProbe : ExtensionMutationGateProbe {
    override fun beforeLockAcquire() = Unit

    override fun afterLockAcquire() = Unit
}

private class ExtensionMutationGate {
    val lock = ReentrantLock()

    @Volatile
    var ready = true
}

private object ExtensionMutationGates {
    private val gates = ConcurrentHashMap<Path, ExtensionMutationGate>()

    fun forPaths(paths: NativeAdapterPaths): ExtensionMutationGate {
        val stateDir = requireNotNull(paths.extensionTransactionFile.parentFile) {
            "Extension transaction journal must have a state directory"
        }
        val filesDir = requireNotNull(stateDir.parentFile) {
            "Extension transaction state directory must have a files directory"
        }
        val key = try {
            filesDir.canonicalFile.toPath().normalize()
        } catch (_: IOException) {
            filesDir.toPath().toAbsolutePath().normalize()
        }
        return gates.computeIfAbsent(key) { ExtensionMutationGate() }
    }
}

class ExtensionMutationCoordinator(
    private val paths: NativeAdapterPaths,
    private val registry: ExtensionRegistry,
    private val journal: ExtensionTransactionJournal,
    private val quarantine: ExtensionDirectoryQuarantine,
    private val diagnosticLogger: DiagnosticLogger? = null,
    private val uuid: () -> UUID = UUID::randomUUID,
    private val directoryMover: (File, File) -> Unit = ::moveDirectoryAtomically,
    private val directoryRemover: (File) -> Boolean = File::deleteRecursively
) {
    private val mutationGate = ExtensionMutationGates.forPaths(paths)

    @Volatile
    private var mutationGateProbe: ExtensionMutationGateProbe = NoOpExtensionMutationGateProbe

    internal constructor(
        paths: NativeAdapterPaths,
        registry: ExtensionRegistry,
        journal: ExtensionTransactionJournal,
        quarantine: ExtensionDirectoryQuarantine,
        diagnosticLogger: DiagnosticLogger? = null,
        uuid: () -> UUID = UUID::randomUUID,
        directoryMover: (File, File) -> Unit = ::moveDirectoryAtomically,
        directoryRemover: (File) -> Boolean = File::deleteRecursively,
        mutationGateProbe: ExtensionMutationGateProbe
    ) : this(
        paths = paths,
        registry = registry,
        journal = journal,
        quarantine = quarantine,
        diagnosticLogger = diagnosticLogger,
        uuid = uuid,
        directoryMover = directoryMover,
        directoryRemover = directoryRemover
    ) {
        this.mutationGateProbe = mutationGateProbe
    }

    fun install(prepared: PreparedExtension): ExtensionRecord = mutate {
        val record = prepared.record
        val records = registry.list()
        if (records.any {
                it.folderName.equals(record.folderName, ignoreCase = true) ||
                    it.repositoryUrl.equals(record.repositoryUrl, ignoreCase = true)
            }
        ) {
            throw ExtensionAlreadyInstalledException()
        }
        val target = SafePath.child(paths.extensionsDir, record.folderName)
        if (target.exists()) {
            try {
                quarantine.move(target, "unregistered_extension", "install")
            } catch (exception: Exception) {
                throw ExtensionTransactionException(exception)
            }
        }

        val transactionId = preparedTransactionId(prepared)
        val transaction = ExtensionTransaction(
            transactionId = transactionId,
            operation = ExtensionOperation.INSTALL,
            phase = ExtensionTransactionPhase.PREPARED,
            folderName = record.folderName,
            oldRecord = null,
            newRecord = record,
            stagingName = prepared.stagingDirectory.name,
            backupName = null,
            trashName = null
        )
        try {
            journal.write(transaction)
            directoryMover(prepared.stagingDirectory, target)
            journal.write(transaction.copy(phase = ExtensionTransactionPhase.FILES_ACTIVATED))
        } catch (exception: Exception) {
            rollbackOrThrow(transaction, exception, registryFailure = false) {
                removeNewTarget(target, "install_rollback")
            }
        }

        try {
            registry.install(record)
        } catch (exception: Exception) {
            rollbackOrThrow(transaction, exception, registryFailure = true) {
                removeNewTarget(target, "install_registry_rollback")
            }
        }

        finishCommitted(transaction) { true }
        record
    }

    fun update(expected: ExtensionRecord, prepared: PreparedExtension): ExtensionRecord = mutate {
        val current = registry.find(expected.folderName)
            ?: throw ExtensionOperationConflictException()
        if (current != expected) throw ExtensionOperationConflictException()
        val newRecord = prepared.record
        if (!newRecord.folderName.equals(expected.folderName, ignoreCase = true) ||
            !newRecord.repositoryUrl.equals(expected.repositoryUrl, ignoreCase = true)
        ) {
            throw ExtensionOperationConflictException()
        }
        val target = SafePath.child(paths.extensionsDir, expected.folderName)
        requireRecoverableTarget(target)
        if (newRecord.commitSha == current.commitSha) return@mutate current

        val transactionId = preparedTransactionId(prepared)
        val backup = SafePath.child(paths.extensionsDir, ".stapk-txn-$transactionId.backup")
        val transaction = ExtensionTransaction(
            transactionId = transactionId,
            operation = ExtensionOperation.UPDATE,
            phase = ExtensionTransactionPhase.PREPARED,
            folderName = expected.folderName,
            oldRecord = current,
            newRecord = newRecord,
            stagingName = prepared.stagingDirectory.name,
            backupName = backup.name,
            trashName = null
        )
        try {
            journal.write(transaction)
            directoryMover(target, backup)
            directoryMover(prepared.stagingDirectory, target)
            journal.write(transaction.copy(phase = ExtensionTransactionPhase.FILES_ACTIVATED))
        } catch (exception: Exception) {
            rollbackOrThrow(transaction, exception, registryFailure = false) {
                restorePreviousTarget(target, backup, "update_rollback")
            }
        }

        try {
            registry.update(newRecord)
        } catch (exception: Exception) {
            rollbackOrThrow(transaction, exception, registryFailure = true) {
                restorePreviousTarget(target, backup, "update_registry_rollback")
            }
        }

        finishCommitted(transaction) {
            !backup.exists() || directoryRemover(backup)
        }
        newRecord
    }

    fun delete(expected: ExtensionRecord): Boolean = mutate {
        val current = registry.find(expected.folderName)
            ?: throw ExtensionOperationConflictException()
        if (current != expected) throw ExtensionOperationConflictException()
        val target = SafePath.child(paths.extensionsDir, expected.folderName)
        requireRecoverableTarget(target)
        val transactionId = uuid().toString()
        val trash = SafePath.child(paths.extensionsDir, ".stapk-txn-$transactionId.trash")
        val transaction = ExtensionTransaction(
            transactionId = transactionId,
            operation = ExtensionOperation.DELETE,
            phase = ExtensionTransactionPhase.PREPARED,
            folderName = expected.folderName,
            oldRecord = current,
            newRecord = null,
            stagingName = null,
            backupName = null,
            trashName = trash.name
        )
        try {
            journal.write(transaction)
            directoryMover(target, trash)
            journal.write(transaction.copy(phase = ExtensionTransactionPhase.FILES_ACTIVATED))
        } catch (exception: Exception) {
            rollbackOrThrow(transaction, exception, registryFailure = false) {
                restoreDeletedTarget(target, trash)
            }
        }

        try {
            check(registry.remove(expected.folderName)) { "Extension disappeared during delete" }
        } catch (exception: Exception) {
            rollbackOrThrow(transaction, exception, registryFailure = true) {
                restoreDeletedTarget(target, trash)
            }
        }

        finishCommitted(transaction) {
            !trash.exists() || directoryRemover(trash)
        }
        true
    }

    fun <T> underLock(block: () -> T): T = withSharedMutationGate(block)

    fun setRecoveryReady(ready: Boolean) {
        mutationGate.ready = ready
    }

    internal fun requireRecoveryReady() {
        if (!mutationGate.ready) throw ExtensionRecoveryRequiredException()
    }

    internal fun findRecordForMutation(folderName: String): ExtensionRecord? =
        withMutationGate { registry.find(folderName) }

    private inline fun <T> withMutationGate(block: () -> T): T {
        requireRecoveryReady()
        return withSharedMutationGate {
            requireRecoveryReady()
            val journalExisted = paths.extensionTransactionFile.exists()
            val activeTransaction = journal.read()
            if (journalExisted || activeTransaction != null) {
                markRecoveryRequired(activeTransaction, null)
                throw ExtensionRecoveryRequiredException()
            }
            block()
        }
    }

    private inline fun <T> withSharedMutationGate(block: () -> T): T {
        notifyGateProbe { it.beforeLockAcquire() }
        return mutationGate.lock.withLock {
            notifyGateProbe { it.afterLockAcquire() }
            block()
        }
    }

    private fun notifyGateProbe(notify: (ExtensionMutationGateProbe) -> Unit) {
        runCatching { notify(mutationGateProbe) }
    }

    private inline fun <T> mutate(block: () -> T): T = withMutationGate(block)

    private fun preparedTransactionId(prepared: PreparedExtension): String {
        val match = STAGING_NAME.matchEntire(prepared.stagingDirectory.name)
            ?: throw ExtensionTransactionException(
                IllegalArgumentException("Prepared extension staging name is invalid")
            )
        val transactionId = try {
            UUID.fromString(match.groupValues[1]).toString()
        } catch (exception: IllegalArgumentException) {
            throw ExtensionTransactionException(exception)
        }
        if (transactionId != match.groupValues[1]) {
            throw ExtensionTransactionException(
                IllegalArgumentException("Prepared extension staging UUID is not canonical")
            )
        }
        val expectedParent = paths.extensionsDir.toPath().toAbsolutePath().normalize()
        val actualParent = prepared.stagingDirectory.toPath().toAbsolutePath().normalize().parent
        if (actualParent != expectedParent || !prepared.stagingDirectory.isDirectory) {
            throw ExtensionTransactionException(
                IllegalArgumentException("Prepared extension staging directory is invalid")
            )
        }
        return transactionId
    }

    private fun requireRecoverableTarget(target: File) {
        if (!target.isDirectory) {
            markRecoveryRequired(null, null)
            throw ExtensionRecoveryRequiredException()
        }
    }

    private fun restorePreviousTarget(target: File, backup: File, reason: String) {
        if (backup.exists()) {
            if (target.exists()) removeNewTarget(target, reason)
            directoryMover(backup, target)
        }
        check(target.isDirectory) { "Unable to restore previous extension directory" }
    }

    private fun restoreDeletedTarget(target: File, trash: File) {
        if (trash.exists()) directoryMover(trash, target)
        check(target.isDirectory) { "Unable to restore deleted extension directory" }
    }

    private fun removeNewTarget(target: File, reason: String) {
        if (!target.exists()) return
        if (directoryRemover(target)) return
        quarantine.move(target, reason, "rollback")
        check(!target.exists()) { "Unable to remove activated extension directory" }
    }

    private fun rollbackOrThrow(
        transaction: ExtensionTransaction,
        cause: Exception,
        registryFailure: Boolean,
        rollback: () -> Unit
    ): Nothing {
        var rollbackFailure: Exception? = null
        try {
            rollback()
        } catch (exception: Exception) {
            rollbackFailure = exception
        }
        if (rollbackFailure == null) {
            try {
                journal.clear()
            } catch (exception: Exception) {
                rollbackFailure = exception
            }
        }
        if (rollbackFailure != null) {
            markRecoveryRequired(transaction, rollbackFailure)
            throw ExtensionRecoveryRequiredException(rollbackFailure)
        }
        if (registryFailure) throw ExtensionRegistryWriteException(cause)
        throw ExtensionTransactionException(cause)
    }

    private fun finishCommitted(transaction: ExtensionTransaction, cleanup: () -> Boolean) {
        try {
            journal.write(transaction.copy(phase = ExtensionTransactionPhase.REGISTRY_COMMITTED))
        } catch (exception: Exception) {
            markRecoveryRequired(
                transaction.copy(phase = ExtensionTransactionPhase.FILES_ACTIVATED),
                exception
            )
            return
        }
        try {
            if (!cleanup()) throw IOException("Unable to clean committed extension transaction directory")
            journal.clear()
        } catch (exception: Exception) {
            markRecoveryRequired(
                transaction.copy(phase = ExtensionTransactionPhase.REGISTRY_COMMITTED),
                exception
            )
        }
    }

    private fun markRecoveryRequired(transaction: ExtensionTransaction?, cause: Throwable?) {
        mutationGate.ready = false
        runCatching {
            diagnosticLogger?.event(
                DiagnosticArea.STORAGE,
                "extension_recovery_required",
                buildMap {
                    transaction?.let {
                        put("operation", it.operation.name.lowercase())
                        put("phase", it.phase.name.lowercase())
                        put("folder", it.folderName)
                    }
                    cause?.let { put("errorClass", it.javaClass.name) }
                }
            )
        }
    }

    private companion object {
        val STAGING_NAME = Regex("\\.stapk-txn-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.installing")
    }
}
