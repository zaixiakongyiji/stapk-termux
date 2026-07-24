package com.stapk.mobile.nativeadapter

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionMutationCoordinatorTest {
    @Test
    fun `install quarantines empty and populated unregistered targets before activation`() {
        listOf(false, true).forEach { populated ->
            val fixture = fixture("orphan-$populated")
            val target = fixture.paths.extensionsDir.resolve(FOLDER).apply { mkdirs() }
            if (populated) target.resolve("old.txt").writeText("preserve me")

            fixture.prepared("new-$populated").use { prepared ->
                assertEquals(prepared.record, fixture.coordinator.install(prepared))
            }

            assertEquals("new-$populated", sidecar(target).commitSha)
            assertEquals("new-$populated", fixture.registry.find(FOLDER)?.commitSha)
            val quarantined = fixture.paths.quarantineDir.walkTopDown()
                .firstOrNull { it.isDirectory && it.name == FOLDER }
            assertNotNull("旧 target 应保留在 quarantine", quarantined)
            if (populated) assertEquals("preserve me", quarantined!!.resolve("old.txt").readText())
        }
    }

    @Test
    fun `concurrent installs serialize and second request observes committed registry`() {
        val fixture = fixture("concurrent")
        val firstPrepared = fixture.prepared("first")
        val secondPrepared = fixture.prepared("second")
        val firstEnteredActivation = CountDownLatch(1)
        val allowFirstActivation = CountDownLatch(1)
        val calls = AtomicInteger()
        val coordinator = fixture.coordinator(
            mover = { source, target ->
                if (target.name == FOLDER && calls.incrementAndGet() == 1) {
                    firstEnteredActivation.countDown()
                    assertTrue(allowFirstActivation.await(5, TimeUnit.SECONDS))
                }
                move(source, target)
            }
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Result<ExtensionRecord>> {
                runCatching { firstPrepared.use(coordinator::install) }
            }
            assertTrue(firstEnteredActivation.await(5, TimeUnit.SECONDS))
            val secondStarted = CountDownLatch(1)
            val second = executor.submit<Result<ExtensionRecord>> {
                secondStarted.countDown()
                runCatching { secondPrepared.use(coordinator::install) }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            allowFirstActivation.countDown()

            val results = listOf(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is ExtensionAlreadyInstalledException })
            assertFalse(results.any { it.exceptionOrNull() is ExtensionRecoveryRequiredException })
            assertEquals(fixture.registry.find(FOLDER), sidecar(fixture.target))
            assertTrue(fixture.target.isDirectory)
            assertFalse(firstPrepared.stagingDirectory.exists())
            assertFalse(secondPrepared.stagingDirectory.exists())
        } finally {
            allowFirstActivation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `independent coordinators for same files directory share mutation gate and recovery readiness`() {
        val fixture = fixture("shared-gate")
        val firstPrepared = fixture.prepared("first")
        val secondPrepared = fixture.prepared("second")
        val firstEnteredActivation = CountDownLatch(1)
        val releaseFirstActivation = CountDownLatch(1)
        val secondLockAttempted = CountDownLatch(1)
        val secondLockAcquired = CountDownLatch(1)
        val secondEnteredActivation = AtomicBoolean(false)
        val calls = AtomicInteger()
        val mover: (File, File) -> Unit = { source, target ->
            if (target.name == FOLDER) {
                if (calls.incrementAndGet() == 1) {
                    firstEnteredActivation.countDown()
                    assertTrue(releaseFirstActivation.await(5, TimeUnit.SECONDS))
                } else {
                    secondEnteredActivation.set(true)
                }
                move(source, target)
            } else {
                move(source, target)
            }
        }
        val firstCoordinator = fixture.coordinator(mover = mover)
        val secondCoordinator = fixture.coordinator(
            mover = mover,
            gateProbe = object : ExtensionMutationGateProbe {
                override fun beforeLockAcquire() {
                    secondLockAttempted.countDown()
                }

                override fun afterLockAcquire() {
                    secondLockAcquired.countDown()
                }
            }
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Result<ExtensionRecord>> {
                runCatching { firstPrepared.use(firstCoordinator::install) }
            }
            assertTrue(firstEnteredActivation.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Result<ExtensionRecord>> {
                runCatching { secondPrepared.use(secondCoordinator::install) }
            }
            assertTrue(secondLockAttempted.await(5, TimeUnit.SECONDS))
            assertEquals("第二线程尚未获得共享 gate", 1L, secondLockAcquired.count)
            assertFalse("第一持有者释放前第二线程不能进入临界区", secondEnteredActivation.get())
            releaseFirstActivation.countDown()
            assertTrue("释放后第二线程应获得共享 gate", secondLockAcquired.await(5, TimeUnit.SECONDS))

            val results = listOf(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is ExtensionAlreadyInstalledException })
            assertEquals(fixture.registry.find(FOLDER), sidecar(fixture.target))
            assertTrue(fixture.target.isDirectory)
            assertFalse(fixture.paths.extensionTransactionFile.exists())
            assertFalse(firstPrepared.stagingDirectory.exists())
            assertFalse(secondPrepared.stagingDirectory.exists())

            firstCoordinator.setRecoveryReady(false)
            assertThrows(ExtensionRecoveryRequiredException::class.java) {
                secondCoordinator.requireRecoveryReady()
            }
        } finally {
            releaseFirstActivation.countDown()
            executor.shutdownNow()
            assertTrue("共享 gate 测试线程池应清理", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `gate observer failures do not change mutation or underLock behavior`() {
        val fixture = fixture("gate-probe-failure")
        val attempts = AtomicInteger()
        val acquisitions = AtomicInteger()
        val coordinator = fixture.coordinator(
            gateProbe = object : ExtensionMutationGateProbe {
                override fun beforeLockAcquire() {
                    attempts.incrementAndGet()
                    throw IllegalStateException("injected before-lock observer failure")
                }

                override fun afterLockAcquire() {
                    acquisitions.incrementAndGet()
                    throw IllegalStateException("injected after-lock observer failure")
                }
            }
        )

        coordinator.underLock { Unit }
        fixture.prepared("probe-safe").use { prepared ->
            assertEquals(prepared.record, coordinator.install(prepared))
        }

        assertEquals(2, attempts.get())
        assertEquals(2, acquisitions.get())
        assertEquals("probe-safe", fixture.registry.find(FOLDER)?.commitSha)
    }

    @Test
    fun `coordinators for different files directories do not share mutation gate or readiness`() {
        val firstFixture = fixture("isolated-gate-first")
        val secondFixture = fixture("isolated-gate-second")
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                firstFixture.coordinator.underLock {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

            val second = executor.submit<String> {
                secondFixture.coordinator.underLock { "independent" }
            }
            assertEquals("independent", second.get(1, TimeUnit.SECONDS))

            firstFixture.coordinator.setRecoveryReady(false)
            secondFixture.coordinator.requireRecoveryReady()
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `registry write failures roll back install update and delete and allow direct retry`() {
        Operation.entries.forEach { operation ->
            val fixture = fixture("registry-${operation.name.lowercase()}")
            fixture.seed(operation)
            var fail = true
            val failingStore = AtomicFileStore.forTesting(fixture.paths.quarantineDir) { file, bytes ->
                if (fail) throw IOException("registry write failed")
                AtomicFileStore.writeAndSync(file, bytes)
            }
            val registry = ExtensionRegistry(fixture.paths, failingStore)
            val coordinator = fixture.coordinator(registry = registry)

            assertThrows(ExtensionRegistryWriteException::class.java) {
                fixture.invoke(operation, coordinator, "new")
            }
            fixture.assertOldState(operation, registry)
            assertFalse(fixture.paths.extensionTransactionFile.exists())

            fail = false
            fixture.invoke(operation, coordinator, "retry")
            fixture.assertCommittedState(operation, registry, "retry")
        }
    }

    @Test
    fun `journal failures before registry commit roll back all operations and allow retry`() {
        Operation.entries.forEach { operation ->
            listOf(ExtensionTransactionPhase.PREPARED, ExtensionTransactionPhase.FILES_ACTIVATED).forEach { phase ->
                val fixture = fixture("journal-${operation.name.lowercase()}-${phase.name.lowercase()}")
                fixture.seed(operation)
                var fail = true
                val store = AtomicFileStore.forTesting(fixture.paths.quarantineDir) { file, bytes ->
                    val text = bytes.toString(Charsets.UTF_8)
                    if (fail && text.contains("\"phase\":\"${phase.name.lowercase()}\"")) {
                        throw IOException("journal write failed")
                    }
                    AtomicFileStore.writeAndSync(file, bytes)
                }
                val coordinator = fixture.coordinator(journal = ExtensionTransactionJournal(fixture.paths, store))

                assertThrows(ExtensionTransactionException::class.java) {
                    fixture.invoke(operation, coordinator, "new")
                }
                fixture.assertOldState(operation, fixture.registry)
                assertFalse(fixture.paths.extensionTransactionFile.exists())

                fail = false
                fixture.invoke(operation, coordinator, "retry")
                fixture.assertCommittedState(operation, fixture.registry, "retry")
            }
        }
    }

    @Test
    fun `registry committed journal failure returns success then blocks later mutations`() {
        Operation.entries.forEach { operation ->
            val fixture = fixture("committed-journal-${operation.name.lowercase()}")
            fixture.seed(operation)
            val store = AtomicFileStore.forTesting(fixture.paths.quarantineDir) { file, bytes ->
                val text = bytes.toString(Charsets.UTF_8)
                if (text.contains("\"phase\":\"registry_committed\"")) {
                    throw IOException("registry committed journal write failed")
                }
                AtomicFileStore.writeAndSync(file, bytes)
            }
            val coordinator = fixture.coordinator(journal = ExtensionTransactionJournal(fixture.paths, store))

            fixture.invoke(operation, coordinator, "new")

            fixture.assertCommittedState(operation, fixture.registry, "new")
            assertTrue(fixture.paths.extensionTransactionFile.isFile)
            assertThrows(ExtensionRecoveryRequiredException::class.java) {
                fixture.invokeFreshMutationAfter(operation, coordinator)
            }
        }
    }

    @Test
    fun `activation move failures roll back each move boundary and allow direct retry`() {
        val cases = listOf(
            Operation.INSTALL to 1,
            Operation.UPDATE to 1,
            Operation.UPDATE to 2,
            Operation.DELETE to 1
        )
        cases.forEach { (operation, failingCall) ->
            val fixture = fixture("move-${operation.name.lowercase()}-$failingCall")
            fixture.seed(operation)
            var fail = true
            val calls = AtomicInteger()
            val coordinator = fixture.coordinator(mover = { source, target ->
                val call = calls.incrementAndGet()
                if (fail && call == failingCall) throw IOException("activation move failed")
                move(source, target)
            })

            assertThrows(ExtensionTransactionException::class.java) {
                fixture.invoke(operation, coordinator, "new")
            }
            fixture.assertOldState(operation, fixture.registry)
            assertFalse(fixture.paths.extensionTransactionFile.exists())

            fail = false
            calls.set(0)
            fixture.invoke(operation, coordinator, "retry")
            fixture.assertCommittedState(operation, fixture.registry, "retry")
        }
    }

    @Test
    fun `incomplete rollback preserves journal marks not ready and returns recovery required`() {
        val updateFixture = fixture("rollback-update")
        updateFixture.seed(Operation.UPDATE)
        val updateCalls = AtomicInteger()
        val updateCoordinator = updateFixture.coordinator(mover = { source, target ->
            when (updateCalls.incrementAndGet()) {
                2 -> throw IOException("new target activation failed")
                3 -> throw IOException("backup restore failed")
                else -> move(source, target)
            }
        })
        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            updateFixture.invoke(Operation.UPDATE, updateCoordinator, "new")
        }
        assertTrue(updateFixture.paths.extensionTransactionFile.isFile)
        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            updateFixture.invoke(Operation.UPDATE, updateCoordinator, "retry")
        }

        val deleteFixture = fixture("rollback-delete")
        deleteFixture.seed(Operation.DELETE)
        var registryFail = true
        val registryStore = AtomicFileStore.forTesting(deleteFixture.paths.quarantineDir) { file, bytes ->
            if (registryFail) throw IOException("registry failed")
            AtomicFileStore.writeAndSync(file, bytes)
        }
        val registry = ExtensionRegistry(deleteFixture.paths, registryStore)
        val deleteCalls = AtomicInteger()
        val deleteCoordinator = deleteFixture.coordinator(
            registry = registry,
            mover = { source, target ->
                if (deleteCalls.incrementAndGet() == 2) throw IOException("trash restore failed")
                move(source, target)
            }
        )
        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            deleteFixture.invoke(Operation.DELETE, deleteCoordinator, "ignored")
        }
        registryFail = false
        assertTrue(deleteFixture.paths.extensionTransactionFile.isFile)
        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            deleteFixture.invoke(Operation.DELETE, deleteCoordinator, "ignored")
        }

        val installFixture = fixture("rollback-install")
        val journalStore = AtomicFileStore.forTesting(installFixture.paths.quarantineDir) { file, bytes ->
            val text = bytes.toString(Charsets.UTF_8)
            if (text.contains("\"phase\":\"files_activated\"")) {
                throw IOException("phase write failed")
            }
            AtomicFileStore.writeAndSync(file, bytes)
        }
        val installCoordinator = installFixture.coordinator(
            journal = ExtensionTransactionJournal(installFixture.paths, journalStore),
            quarantine = ExtensionDirectoryQuarantine(
                installFixture.paths,
                directoryMover = { _, _ -> throw IOException("quarantine move failed") }
            ),
            remover = { false }
        )
        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            installFixture.invoke(Operation.INSTALL, installCoordinator, "new")
        }
        assertTrue(installFixture.target.isDirectory)
        assertTrue(installFixture.paths.extensionTransactionFile.isFile)
    }

    @Test
    fun `journal clear failure during rollback requires recovery`() {
        val fixture = fixture("rollback-clear")
        fixture.seed(Operation.UPDATE)
        val journal = ExtensionTransactionJournal(
            fixture.paths,
            fileRemover = { false }
        )
        val coordinator = fixture.coordinator(
            journal = journal,
            mover = { source, target ->
                if (source.name.endsWith(".installing")) throw IOException("activation failed")
                move(source, target)
            }
        )

        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            fixture.invoke(Operation.UPDATE, coordinator, "new")
        }
        assertEquals("old", sidecar(fixture.target).commitSha)
        assertTrue(fixture.paths.extensionTransactionFile.isFile)
    }

    @Test
    fun `post commit directory cleanup failure succeeds preserves journal and blocks mutations`() {
        listOf(Operation.UPDATE, Operation.DELETE).forEach { operation ->
            val fixture = fixture("cleanup-${operation.name.lowercase()}")
            fixture.seed(operation)
            val coordinator = fixture.coordinator(remover = { file ->
                if (file.name.endsWith(".backup") || file.name.endsWith(".trash")) false else file.deleteRecursively()
            })

            fixture.invoke(operation, coordinator, "new")

            fixture.assertCommittedState(operation, fixture.registry, "new")
            assertTrue(fixture.paths.extensionTransactionFile.isFile)
            assertTrue(fixture.paths.extensionsDir.listFiles().orEmpty().any {
                it.name.endsWith(".backup") || it.name.endsWith(".trash")
            })
            assertThrows(ExtensionRecoveryRequiredException::class.java) {
                fixture.invokeFreshMutationAfter(operation, coordinator)
            }
        }
    }

    @Test
    fun `post commit journal clear failure succeeds for all operations and blocks mutations`() {
        Operation.entries.forEach { operation ->
            val fixture = fixture("clear-${operation.name.lowercase()}")
            fixture.seed(operation)
            val coordinator = fixture.coordinator(
                journal = ExtensionTransactionJournal(fixture.paths, fileRemover = { false })
            )

            fixture.invoke(operation, coordinator, "new")

            fixture.assertCommittedState(operation, fixture.registry, "new")
            assertEquals(
                ExtensionTransactionPhase.REGISTRY_COMMITTED,
                ExtensionTransactionJournal(fixture.paths).read()?.phase
            )
            assertThrows(ExtensionRecoveryRequiredException::class.java) {
                fixture.invokeFreshMutationAfter(operation, coordinator)
            }
        }
    }

    @Test
    fun `stale update is rejected without activating prepared staging`() {
        val fixture = fixture("stale")
        fixture.seed(Operation.UPDATE)
        val expected = fixture.oldRecord
        fixture.registry.update(expected.copy(commitSha = "concurrent", updatedAt = 3L))
        val prepared = fixture.prepared("new")

        assertThrows(ExtensionOperationConflictException::class.java) {
            prepared.use { fixture.coordinator.update(expected, it) }
        }

        assertEquals("old", fixture.target.resolve("old.txt").readText())
        assertFalse(prepared.stagingDirectory.exists())
        assertFalse(fixture.paths.extensionTransactionFile.exists())
    }

    @Test
    fun `same sha update requires existing extension target directory`() {
        val fixture = fixture("same-sha-missing-target")
        fixture.registry.install(fixture.oldRecord)
        val prepared = fixture.prepared("old")

        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            prepared.use { fixture.coordinator.update(fixture.oldRecord, it) }
        }

        assertFalse(prepared.stagingDirectory.exists())
        assertFalse(fixture.paths.extensionTransactionFile.exists())
        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            fixture.coordinator.requireRecoveryReady()
        }
    }

    @Test
    fun `same sha update rejects non directory extension target`() {
        val fixture = fixture("same-sha-file-target")
        fixture.registry.install(fixture.oldRecord)
        fixture.target.apply {
            parentFile?.mkdirs()
            writeText("not a directory")
        }
        val prepared = fixture.prepared("old")

        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            prepared.use { fixture.coordinator.update(fixture.oldRecord, it) }
        }

        assertTrue(fixture.target.isFile)
        assertFalse(prepared.stagingDirectory.exists())
        assertFalse(fixture.paths.extensionTransactionFile.exists())
        assertThrows(ExtensionRecoveryRequiredException::class.java) {
            fixture.coordinator.requireRecoveryReady()
        }
    }

    private enum class Operation { INSTALL, UPDATE, DELETE }

    private data class Fixture(
        val paths: NativeAdapterPaths,
        val registry: ExtensionRegistry,
        val journal: ExtensionTransactionJournal,
        val quarantine: ExtensionDirectoryQuarantine
    ) {
        val target: File get() = paths.extensionsDir.resolve(FOLDER)
        val oldRecord = record("old")

        val coordinator: ExtensionMutationCoordinator
            get() = coordinator()

        fun coordinator(
            registry: ExtensionRegistry = this.registry,
            journal: ExtensionTransactionJournal = this.journal,
            quarantine: ExtensionDirectoryQuarantine = this.quarantine,
            mover: (File, File) -> Unit = ::move,
            remover: (File) -> Boolean = File::deleteRecursively,
            gateProbe: ExtensionMutationGateProbe? = null
        ): ExtensionMutationCoordinator = if (gateProbe == null) {
            ExtensionMutationCoordinator(
                paths = paths,
                registry = registry,
                journal = journal,
                quarantine = quarantine,
                uuid = { UUID.fromString(DELETE_TRANSACTION_ID) },
                directoryMover = mover,
                directoryRemover = remover
            )
        } else {
            ExtensionMutationCoordinator(
                paths = paths,
                registry = registry,
                journal = journal,
                quarantine = quarantine,
                uuid = { UUID.fromString(DELETE_TRANSACTION_ID) },
                directoryMover = mover,
                directoryRemover = remover,
                mutationGateProbe = gateProbe
            )
        }

        fun seed(operation: Operation) {
            if (operation == Operation.INSTALL) return
            registry.install(oldRecord)
            target.mkdirs()
            target.resolve("old.txt").writeText("old")
            target.resolve(SIDECAR).writeText(ExtensionRecordCodec.encode(oldRecord))
        }

        fun prepared(commit: String): PreparedExtension {
            paths.extensionsDir.mkdirs()
            val id = UUID.randomUUID()
            val staging = paths.extensionsDir.resolve(".stapk-txn-$id.installing").apply { mkdirs() }
            val record = record(commit)
            staging.resolve("manifest.json").writeText("""{"display_name":"Test","js":"index.js"}""")
            staging.resolve("index.js").writeText(commit)
            staging.resolve(SIDECAR).writeText(ExtensionRecordCodec.encode(record))
            return PreparedExtension(record, staging)
        }

        fun invoke(operation: Operation, coordinator: ExtensionMutationCoordinator, commit: String) {
            when (operation) {
                Operation.INSTALL -> prepared(commit).use(coordinator::install)
                Operation.UPDATE -> prepared(commit).use { coordinator.update(oldRecord, it) }
                Operation.DELETE -> coordinator.delete(oldRecord)
            }
        }

        fun invokeFreshMutationAfter(operation: Operation, coordinator: ExtensionMutationCoordinator) {
            when (operation) {
                Operation.DELETE -> prepared("later").use(coordinator::install)
                Operation.INSTALL, Operation.UPDATE -> coordinator.delete(registry.find(FOLDER)!!)
            }
        }

        fun assertOldState(operation: Operation, registry: ExtensionRegistry) {
            when (operation) {
                Operation.INSTALL -> {
                    assertNull(registry.find(FOLDER))
                    assertFalse(target.exists())
                }
                Operation.UPDATE, Operation.DELETE -> {
                    assertEquals(oldRecord, registry.find(FOLDER))
                    assertEquals("old", target.resolve("old.txt").readText())
                    assertEquals(oldRecord, sidecar(target))
                }
            }
        }

        fun assertCommittedState(operation: Operation, registry: ExtensionRegistry, commit: String) {
            when (operation) {
                Operation.INSTALL, Operation.UPDATE -> {
                    assertEquals(commit, registry.find(FOLDER)?.commitSha)
                    assertEquals(commit, sidecar(target).commitSha)
                    assertEquals(commit, target.resolve("index.js").readText())
                }
                Operation.DELETE -> {
                    assertNull(registry.find(FOLDER))
                    assertFalse(target.exists())
                }
            }
        }
    }

    private fun fixture(name: String): Fixture {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-coordinator-$name").toFile())
        return Fixture(
            paths,
            ExtensionRegistry(paths),
            ExtensionTransactionJournal(paths),
            ExtensionDirectoryQuarantine(paths)
        )
    }

    private companion object {
        const val FOLDER = "Test-Extension"
        const val SIDECAR = ".stapk-extension.json"
        const val DELETE_TRANSACTION_ID = "123e4567-e89b-12d3-a456-426614174000"

        fun record(commit: String) = ExtensionRecord(
            folderName = FOLDER,
            repositoryUrl = "https://github.com/owner/$FOLDER",
            owner = "owner",
            repository = FOLDER,
            branch = "main",
            commitSha = commit,
            installedAt = 1L,
            updatedAt = if (commit == "old") 2L else 3L
        )

        fun sidecar(directory: File): ExtensionRecord =
            ExtensionRecordCodec.decode(directory.resolve(SIDECAR).readText())

        fun move(source: File, target: File) {
            target.parentFile?.mkdirs()
            Files.move(source.toPath(), target.toPath())
        }
    }
}
