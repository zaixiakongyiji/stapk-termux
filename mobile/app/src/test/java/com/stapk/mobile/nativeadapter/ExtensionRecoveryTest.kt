package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtensionRecoveryTest {
    @Test
    fun `journal operation registry disk matrix recovers every unique crash window idempotently`() {
        val scenarios = listOf(
            Scenario("install-prepared", ExtensionOperation.INSTALL, ExtensionTransactionPhase.PREPARED, RegistryState.NONE, DiskState.INSTALL_PREPARED, FinalState.NONE, 0),
            Scenario("install-moved-before-phase", ExtensionOperation.INSTALL, ExtensionTransactionPhase.PREPARED, RegistryState.NONE, DiskState.INSTALL_ACTIVATED, FinalState.NONE, 1),
            Scenario("install-files-activated", ExtensionOperation.INSTALL, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.NONE, DiskState.INSTALL_ACTIVATED, FinalState.NONE, 1),
            Scenario("install-commit-before-phase", ExtensionOperation.INSTALL, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.NEW, DiskState.INSTALL_ACTIVATED, FinalState.NEW, 0),
            Scenario("install-post-commit", ExtensionOperation.INSTALL, ExtensionTransactionPhase.REGISTRY_COMMITTED, RegistryState.NEW, DiskState.INSTALL_ACTIVATED, FinalState.NEW, 0),
            Scenario("update-prepared", ExtensionOperation.UPDATE, ExtensionTransactionPhase.PREPARED, RegistryState.OLD, DiskState.UPDATE_PREPARED, FinalState.OLD, 0),
            Scenario("update-backup-only", ExtensionOperation.UPDATE, ExtensionTransactionPhase.PREPARED, RegistryState.OLD, DiskState.UPDATE_BACKUP_ONLY, FinalState.OLD, 0),
            Scenario("update-moved-before-phase", ExtensionOperation.UPDATE, ExtensionTransactionPhase.PREPARED, RegistryState.OLD, DiskState.UPDATE_ACTIVATED, FinalState.OLD, 1),
            Scenario("update-commit-before-phase", ExtensionOperation.UPDATE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.NEW, DiskState.UPDATE_ACTIVATED, FinalState.NEW, 0),
            Scenario("update-post-commit", ExtensionOperation.UPDATE, ExtensionTransactionPhase.REGISTRY_COMMITTED, RegistryState.NEW, DiskState.UPDATE_ACTIVATED, FinalState.NEW, 0),
            Scenario("delete-prepared", ExtensionOperation.DELETE, ExtensionTransactionPhase.PREPARED, RegistryState.OLD, DiskState.DELETE_PREPARED, FinalState.OLD, 0),
            Scenario("delete-moved-before-phase", ExtensionOperation.DELETE, ExtensionTransactionPhase.PREPARED, RegistryState.OLD, DiskState.DELETE_ACTIVATED, FinalState.OLD, 0),
            Scenario("delete-files-activated", ExtensionOperation.DELETE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.OLD, DiskState.DELETE_ACTIVATED, FinalState.OLD, 0),
            Scenario("delete-commit-before-phase", ExtensionOperation.DELETE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.NONE, DiskState.DELETE_ACTIVATED, FinalState.NONE, 0),
            Scenario("delete-post-commit", ExtensionOperation.DELETE, ExtensionTransactionPhase.REGISTRY_COMMITTED, RegistryState.NONE, DiskState.DELETE_ACTIVATED, FinalState.NONE, 0)
        )

        scenarios.forEach { scenario ->
            val fixture = fixture(scenario.name)
            fixture.seed(scenario)

            val first = fixture.recovery().recover()
            assertTrue("${scenario.name}: first recovery", first.ready)
            assertEquals("${scenario.name}: recovered count", 1, first.recoveredOperations)
            assertEquals("${scenario.name}: quarantine count", scenario.quarantines, first.quarantinedDirectories)
            assertFalse("${scenario.name}: journal cleared", fixture.paths.extensionTransactionFile.exists())
            fixture.assertFinal(scenario.finalState)
            val quarantineEntries = fixture.quarantinedDirectoryNames()

            val second = fixture.recovery().recover()
            assertEquals("${scenario.name}: idempotent result", ExtensionRecoveryResult(true, 0, 0), second)
            assertEquals("${scenario.name}: no duplicate quarantine", quarantineEntries, fixture.quarantinedDirectoryNames())
            fixture.assertFinal(scenario.finalState)
        }
    }

    @Test
    fun `contradictory journal triples fail closed and preserve every evidence entry`() {
        val scenarios = listOf(
            Scenario("install-phase-ahead", ExtensionOperation.INSTALL, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.NONE, DiskState.INSTALL_PREPARED, FinalState.NONE, 0),
            Scenario("install-wrong-registry", ExtensionOperation.INSTALL, ExtensionTransactionPhase.PREPARED, RegistryState.OTHER, DiskState.INSTALL_ACTIVATED, FinalState.NONE, 0),
            Scenario("update-missing-backup", ExtensionOperation.UPDATE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.OLD, DiskState.UPDATE_TARGET_NEW_ONLY, FinalState.NONE, 0),
            Scenario("update-phase-ahead-prepared-disk", ExtensionOperation.UPDATE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.OLD, DiskState.UPDATE_PREPARED, FinalState.NONE, 0),
            Scenario("update-wrong-registry", ExtensionOperation.UPDATE, ExtensionTransactionPhase.PREPARED, RegistryState.OTHER, DiskState.UPDATE_ACTIVATED, FinalState.NONE, 0),
            Scenario("delete-target-and-trash", ExtensionOperation.DELETE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.OLD, DiskState.DELETE_BOTH, FinalState.NONE, 0),
            Scenario("delete-phase-ahead", ExtensionOperation.DELETE, ExtensionTransactionPhase.REGISTRY_COMMITTED, RegistryState.OLD, DiskState.DELETE_PREPARED, FinalState.NONE, 0)
        )

        scenarios.forEach { scenario ->
            val fixture = fixture(scenario.name)
            fixture.seed(scenario)
            val before = fixture.evidenceSnapshot()

            val result = fixture.recovery().recover()

            assertEquals("${scenario.name}: not ready", ExtensionRecoveryResult(false, 0, 0), result)
            assertTrue("${scenario.name}: journal retained", fixture.paths.extensionTransactionFile.isFile)
            assertEquals("${scenario.name}: evidence preserved", before, fixture.evidenceSnapshot())
            assertRecoveryRequired(fixture.coordinator)
        }
    }

    @Test
    fun `post commit install and update require target sidecar to prove the new record`() {
        val operations = listOf(ExtensionOperation.INSTALL, ExtensionOperation.UPDATE)
        val invalidSidecars = listOf(
            "old" to { fixture: Fixture -> fixture.oldRecord },
            "other" to { fixture: Fixture -> fixture.otherRecord },
            "missing" to { _: Fixture -> null }
        )

        operations.forEach { operation ->
            invalidSidecars.forEach { (identity, sidecarRecord) ->
                val fixture = fixture("post-commit-${operation.name.lowercase()}-$identity")
                val scenario = when (operation) {
                    ExtensionOperation.INSTALL -> Scenario(
                        "install-post-commit-$identity",
                        operation,
                        ExtensionTransactionPhase.REGISTRY_COMMITTED,
                        RegistryState.NEW,
                        DiskState.INSTALL_ACTIVATED,
                        FinalState.NEW,
                        0
                    )
                    ExtensionOperation.UPDATE -> Scenario(
                        "update-post-commit-$identity",
                        operation,
                        ExtensionTransactionPhase.REGISTRY_COMMITTED,
                        RegistryState.NEW,
                        DiskState.UPDATE_ACTIVATED,
                        FinalState.NEW,
                        0
                    )
                    ExtensionOperation.DELETE -> error("delete is not a post-commit target identity case")
                }
                fixture.seed(scenario)
                val sidecar = fixture.target(fixture.newRecord).resolve(SIDECAR)
                sidecarRecord(fixture)?.let {
                    sidecar.writeText(ExtensionRecordCodec.encode(it))
                } ?: assertTrue(sidecar.delete())
                val before = fixture.fullEvidenceSnapshot()

                val result = fixture.recovery().recover()

                assertEquals(
                    "${operation.name.lowercase()}-$identity: ambiguous target is not ready",
                    ExtensionRecoveryResult(false, 0, 0),
                    result
                )
                assertTrue("${operation.name.lowercase()}-$identity: journal retained", fixture.paths.extensionTransactionFile.isFile)
                assertEquals(
                    "${operation.name.lowercase()}-$identity: every evidence entry is retained",
                    before,
                    fixture.fullEvidenceSnapshot()
                )
                if (operation == ExtensionOperation.UPDATE) {
                    assertTrue("update-$identity: old backup retained", fixture.backup.isDirectory)
                }
                assertRecoveryRequired(fixture.coordinator)
            }
        }
    }

    @Test
    fun `recovery executes and publishes readiness through the canonical filesDir shared gate`() {
        val filesDir = Files.createTempDirectory("stapk-recovery-shared-gate").toFile()
        val first = fixture(filesDir, "first")
        val aliasPaths = NativeAdapterPaths(filesDir.resolve(".").absoluteFile)
        val aliasRegistry = ExtensionRegistry(aliasPaths)
        val aliasJournal = ExtensionTransactionJournal(aliasPaths)
        val secondLockAttempted = CountDownLatch(1)
        val secondLockAcquired = CountDownLatch(1)
        val aliasCoordinator = ExtensionMutationCoordinator(
            aliasPaths,
            aliasRegistry,
            aliasJournal,
            ExtensionDirectoryQuarantine(aliasPaths),
            mutationGateProbe = object : ExtensionMutationGateProbe {
                override fun beforeLockAcquire() {
                    secondLockAttempted.countDown()
                }

                override fun afterLockAcquire() {
                    secondLockAcquired.countDown()
                }
            }
        )
        first.coordinator.setRecoveryReady(false)
        assertRecoveryRequired(aliasCoordinator)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val holder = executor.submit {
                first.coordinator.underLock {
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val recovery = executor.submit {
                val result = ExtensionRecovery(
                    aliasPaths,
                    aliasRegistry,
                    aliasJournal,
                    ExtensionDirectoryQuarantine(aliasPaths),
                    aliasCoordinator
                ).recover()
                assertTrue(result.ready)
                finished.set(true)
            }
            assertTrue(secondLockAttempted.await(10, TimeUnit.SECONDS))
            assertEquals("recovery 尚未获得共享 gate", 1L, secondLockAcquired.count)
            assertFalse("recovery must wait on the shared gate", finished.get())
            release.countDown()
            assertTrue("释放后 recovery 应获得共享 gate", secondLockAcquired.await(10, TimeUnit.SECONDS))
            holder.get(10, TimeUnit.SECONDS)
            recovery.get(10, TimeUnit.SECONDS)
            assertTrue(finished.get())
            first.coordinator.requireRecoveryReady()
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue("recovery 共享 gate 测试线程池应清理", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `registered targets keep authority while invalid sidecars are preserved then rewritten`() {
        val fixture = fixture("registered-sidecars")
        val records = listOf(record("Missing", "missing"), record("Corrupt", "corrupt"), record("Mismatch", "mismatch"))
        fixture.registry.replaceAll(records)
        records.forEach { fixture.target(it).mkdirs() }
        fixture.target(records[1]).resolve(SIDECAR).writeText("{broken")
        fixture.target(records[2]).resolve(SIDECAR).writeText(ExtensionRecordCodec.encode(record("Other", "other")))

        val result = fixture.recovery().recover()

        assertTrue(result.ready)
        assertEquals(records.sortedBy { it.folderName.lowercase() }, fixture.registry.list())
        records.forEach {
            assertTrue(fixture.target(it).isDirectory)
            assertEquals(it, fixture.sidecar(fixture.target(it)))
        }
        assertTrue(fixture.paths.quarantineDir.walkTopDown().any { it.isFile && it.readText() == "{broken" })
        assertTrue(fixture.paths.quarantineDir.walkTopDown().any {
            it.isFile && runCatching { ExtensionRecordCodec.decode(it.readText()).folderName == "Other" }.getOrDefault(false)
        })
    }

    @Test
    fun `sidecar symlinks are never followed or handed to canonicalizing quarantine`() {
        val unregisteredFixture = fixture("unregistered-sidecar-symlink")
        val unregisteredRecord = record("LinkedCandidate", "linked")
        val unregisteredTarget = unregisteredFixture.directory(unregisteredRecord.folderName)
        val unregisteredSidecar = unregisteredTarget.resolve(SIDECAR).apply {
            writeText(ExtensionRecordCodec.encode(unregisteredRecord))
        }
        val unregisteredExternal = unregisteredFixture.paths.stateDir.resolve("unregistered-external.json").apply {
            parentFile?.mkdirs()
            writeText("unregistered-external-evidence")
        }

        val unregisteredResult = unregisteredFixture.recovery(
            symlinkChecker = { samePath(it, unregisteredSidecar) }
        ).recover()

        assertEquals(ExtensionRecoveryResult(true, 0, 1), unregisteredResult)
        assertFalse(unregisteredTarget.exists())
        assertEquals(emptyList<ExtensionRecord>(), unregisteredFixture.registry.list())
        assertEquals("unregistered-external-evidence", unregisteredExternal.readText())

        val registeredFixture = fixture("registered-sidecar-symlink")
        val registeredRecord = record("RegisteredLinked", "new")
        val mismatchingRecord = record(registeredRecord.folderName, "other")
        registeredFixture.registry.install(registeredRecord)
        val registeredTarget = registeredFixture.directory(registeredRecord.folderName).apply {
            resolve("content.txt").writeText("registered-content")
        }
        val registeredExternal = registeredFixture.paths.stateDir.resolve("registered-external.json").apply {
            parentFile?.mkdirs()
            writeText("registered-external-evidence")
        }
        val registeredSidecar = registeredTarget.resolve(SIDECAR).apply {
            writeText(ExtensionRecordCodec.encode(mismatchingRecord))
        }
        val sidecarMovedWithoutCanonicalization = AtomicBoolean(false)

        val registeredResult = registeredFixture.recovery(
            mover = { source, target ->
                if (samePath(source, registeredSidecar)) sidecarMovedWithoutCanonicalization.set(true)
                move(source, target)
            },
            symlinkChecker = { samePath(it, registeredSidecar) }
        ).recover()

        assertTrue(registeredResult.ready)
        assertEquals("registered-content", registeredTarget.resolve("content.txt").readText())
        assertEquals(registeredRecord, registeredFixture.sidecar(registeredTarget))
        assertTrue(sidecarMovedWithoutCanonicalization.get())
        assertTrue(registeredExternal.isFile)
        assertEquals("registered-external-evidence", registeredExternal.readText())
    }

    @Test
    fun `unverifiable nested symlink entry fails closed before recursive removal`() {
        val fixture = fixture("nested-symlink-removal")
        val scenario = Scenario(
            "nested-symlink-removal",
            ExtensionOperation.INSTALL,
            ExtensionTransactionPhase.PREPARED,
            RegistryState.NONE,
            DiskState.INSTALL_PREPARED,
            FinalState.NONE,
            0
        )
        fixture.seed(scenario)
        val nestedEntry = fixture.staging.resolve("linked-directory").apply {
            mkdirs()
            resolve("preserved.txt").writeText("must-remain")
        }
        val before = fixture.fullEvidenceSnapshot()

        val result = fixture.recovery(
            symlinkChecker = { samePath(it, nestedEntry) }
        ).recover()

        assertEquals(ExtensionRecoveryResult(false, 0, 0), result)
        assertTrue(fixture.paths.extensionTransactionFile.isFile)
        assertEquals(before, fixture.fullEvidenceSnapshot())
        assertRecoveryRequired(fixture.coordinator)
    }

    @Test
    fun `outer transaction entry symlinks are checked by lexical basename before any mutation`() {
        data class OuterEntryCase(
            val name: String,
            val scenario: Scenario,
            val selectEntry: (Fixture) -> File
        )

        val cases = listOf(
            OuterEntryCase(
                "installing",
                Scenario(
                    "outer-installing-symlink",
                    ExtensionOperation.INSTALL,
                    ExtensionTransactionPhase.PREPARED,
                    RegistryState.NONE,
                    DiskState.INSTALL_PREPARED,
                    FinalState.NONE,
                    0
                ),
                Fixture::staging
            ),
            OuterEntryCase(
                "backup",
                Scenario(
                    "outer-backup-symlink",
                    ExtensionOperation.UPDATE,
                    ExtensionTransactionPhase.FILES_ACTIVATED,
                    RegistryState.OLD,
                    DiskState.UPDATE_ACTIVATED,
                    FinalState.OLD,
                    0
                ),
                Fixture::backup
            )
        )

        cases.forEach { case ->
            val fixture = fixture("outer-${case.name}-symlink")
            fixture.seed(case.scenario)
            if (case.scenario.operation == ExtensionOperation.INSTALL) {
                val unrelated = record("Unrelated", "unrelated")
                fixture.registry.install(unrelated)
                fixture.writeTarget(unrelated)
            }
            val lexicalEntry = case.selectEntry(fixture)
            val referent = fixture.paths.extensionsDir.resolve(".${case.name}-referent")
            move(lexicalEntry, referent)
            var physicalEvidenceDirectory = referent
            val journalBytes = fixture.paths.extensionTransactionFile.readBytes()
            val registryBytes = fixture.paths.extensionRegistryFile.readBytes()
            val targetEvidence = fixture.paths.extensionsDir.resolve(FOLDER).let { target ->
                target.resolve("content.txt").takeIf(File::isFile)?.readBytes()
            }
            val referentEvidence = referent.walkTopDown()
                .filter(File::isFile)
                .associate { it.relativeTo(referent).path to it.readBytes().toList() }
            val removerCalled = AtomicBoolean(false)
            val recoveryMoverCalled = AtomicBoolean(false)
            val quarantineMoverCalled = AtomicBoolean(false)
            val linkCreated = AtomicBoolean(false)
            val guardedQuarantine = ExtensionDirectoryQuarantine(
                fixture.paths,
                directoryMover = { _, _ ->
                    quarantineMoverCalled.set(true)
                    throw IOException("quarantine must not be called for an outer symlink")
                }
            )

            val result = fixture.recovery(
                quarantine = guardedQuarantine,
                mover = { _, _ ->
                    recoveryMoverCalled.set(true)
                    throw IOException("mover must not be called for an outer symlink")
                },
                remover = {
                    removerCalled.set(true)
                    false
                },
                symlinkChecker = { entry ->
                    if (entry.name == fixture.paths.extensionRegistryFile.name && linkCreated.compareAndSet(false, true)) {
                        physicalEvidenceDirectory = createDirectoryAlias(lexicalEntry, referent)
                        assertFalse(
                            "test setup must expose a different canonical final basename",
                            lexicalEntry.name == lexicalEntry.canonicalFile.name
                        )
                    }
                    entry.name == lexicalEntry.name
                }
            ).recover()

            assertEquals("${case.name}: outer symlink fails closed", ExtensionRecoveryResult(false, 0, 0), result)
            assertTrue("${case.name}: alias was installed after journal parsing", linkCreated.get())
            assertTrue("${case.name}: journal remains", fixture.paths.extensionTransactionFile.isFile)
            assertTrue("${case.name}: lexical outer entry remains", Files.exists(lexicalEntry.toPath(), NOFOLLOW_LINKS))
            assertEquals("${case.name}: journal bytes remain", journalBytes.toList(), fixture.paths.extensionTransactionFile.readBytes().toList())
            assertEquals("${case.name}: registry bytes remain", registryBytes.toList(), fixture.paths.extensionRegistryFile.readBytes().toList())
            assertEquals(
                "${case.name}: target evidence remains",
                targetEvidence?.toList(),
                fixture.paths.extensionsDir.resolve(FOLDER).resolve("content.txt").takeIf(File::isFile)?.readBytes()?.toList()
            )
            assertEquals(
                "${case.name}: referent evidence remains",
                referentEvidence,
                physicalEvidenceDirectory.walkTopDown().filter(File::isFile).associate {
                    it.relativeTo(physicalEvidenceDirectory).path to it.readBytes().toList()
                }
            )
            assertFalse("${case.name}: remover is not called", removerCalled.get())
            assertFalse("${case.name}: recovery mover is not called", recoveryMoverCalled.get())
            assertFalse("${case.name}: quarantine is not called", quarantineMoverCalled.get())
            assertFalse("${case.name}: quarantine evidence is not created", fixture.paths.quarantineDir.exists())
            assertRecoveryRequired(fixture.coordinator)
        }
    }

    @Test
    fun `failed extension directory enumeration is not treated as an empty trusted snapshot`() {
        val fixture = fixture("enumeration-failure")
        val registered = record("RegisteredEnumeration", "registered")
        val candidate = record("CandidateEnumeration", "candidate")
        fixture.registry.install(registered)
        fixture.writeTarget(registered)
        fixture.writeTarget(candidate)
        val orphan = fixture.directory(".stapk-txn-$TRANSACTION_ID.backup").apply {
            resolve("evidence.txt").writeText("orphan-evidence")
        }
        val registryBytes = fixture.paths.extensionRegistryFile.readBytes()
        val evidence = fixture.fullEvidenceSnapshot()
        val listCalls = AtomicInteger()
        val replaceWrites = AtomicInteger()
        val quarantineMoves = AtomicInteger()
        val observingRegistry = ExtensionRegistry(
            fixture.paths,
            AtomicFileStore.forTesting(fixture.paths.quarantineDir) { file, bytes ->
                replaceWrites.incrementAndGet()
                AtomicFileStore.writeAndSync(file, bytes)
            }
        )
        val observingQuarantine = ExtensionDirectoryQuarantine(
            fixture.paths,
            directoryMover = { source, target ->
                quarantineMoves.incrementAndGet()
                move(source, target)
            }
        )

        val result = fixture.recovery(
            registry = observingRegistry,
            quarantine = observingQuarantine,
            directoryLister = {
                listCalls.incrementAndGet()
                null
            }
        ).recover()

        assertEquals(ExtensionRecoveryResult(false, 0, 0), result)
        assertEquals("enumeration is attempted exactly once", 1, listCalls.get())
        assertEquals("registry replaceAll is not attempted", 0, replaceWrites.get())
        assertEquals("quarantine is not attempted", 0, quarantineMoves.get())
        assertEquals(registryBytes.toList(), fixture.paths.extensionRegistryFile.readBytes().toList())
        assertEquals(evidence, fixture.fullEvidenceSnapshot())
        assertTrue(orphan.isDirectory)
        assertEquals("orphan-evidence", orphan.resolve("evidence.txt").readText())
        assertRecoveryRequired(fixture.coordinator)
    }

    @Test
    fun `journal aware recovery of known paths does not enumerate the extension directory`() {
        val fixture = fixture("journal-without-enumeration")
        fixture.seed(
            Scenario(
                "journal-without-enumeration",
                ExtensionOperation.INSTALL,
                ExtensionTransactionPhase.PREPARED,
                RegistryState.NONE,
                DiskState.INSTALL_PREPARED,
                FinalState.NONE,
                0
            )
        )
        val listCalls = AtomicInteger()

        val result = fixture.recovery(
            directoryLister = {
                listCalls.incrementAndGet()
                null
            }
        ).recover()

        assertEquals(ExtensionRecoveryResult(true, 1, 0), result)
        assertEquals(0, listCalls.get())
        assertFalse(fixture.paths.extensionTransactionFile.exists())
        assertFalse(Files.exists(fixture.staging.toPath(), NOFOLLOW_LINKS))
        fixture.coordinator.requireRecoveryReady()
    }

    @Test
    fun `unregistered targets with missing corrupt nonfile or basename mismatched sidecars are quarantined`() {
        val fixture = fixture("invalid-unregistered-sidecars")
        val missing = fixture.directory("Missing")
        val corrupt = fixture.directory("Corrupt").apply { resolve(SIDECAR).writeText("bad") }
        val nonFile = fixture.directory("NonFile").apply { resolve(SIDECAR).mkdirs() }
        val mismatch = fixture.directory("Mismatch").apply {
            resolve(SIDECAR).writeText(ExtensionRecordCodec.encode(record("Other", "other")))
        }

        val result = fixture.recovery().recover()

        assertEquals(ExtensionRecoveryResult(true, 0, 4), result)
        listOf(missing, corrupt, nonFile, mismatch).forEach { assertFalse(it.exists()) }
        assertEquals(emptyList<ExtensionRecord>(), fixture.registry.list())
        assertEquals(setOf("Missing", "Corrupt", "NonFile", "Mismatch"), fixture.quarantinedDirectoryNames())
    }

    @Test
    fun `unregistered sidecar conflicts quarantine every loser and rebuild all remaining records once`() {
        val fixture = fixture("sidecar-conflicts")
        val registered = record("Registered", "shared")
        fixture.registry.install(registered)
        fixture.writeTarget(registered)
        val againstRegistered = record("AgainstRegistered", "shared").copy(repositoryUrl = registered.repositoryUrl.uppercase())
        val pairOne = record("PairOne", "pair")
        val pairTwo = record("PairTwo", "pair").copy(repositoryUrl = pairOne.repositoryUrl.uppercase())
        val survivor = record("Survivor", "survivor")
        listOf(againstRegistered, pairOne, pairTwo, survivor).forEach(fixture::writeTarget)

        val result = fixture.recovery().recover()

        assertEquals(ExtensionRecoveryResult(true, 0, 3), result)
        assertEquals(listOf(registered, survivor).sortedBy { it.folderName.lowercase() }, fixture.registry.list())
        assertEquals(setOf("AgainstRegistered", "PairOne", "PairTwo"), fixture.quarantinedDirectoryNames())
        assertTrue(fixture.target(survivor).isDirectory)
        val recordsAfterFirstRecovery = fixture.registry.list()
        val evidenceAfterFirstRecovery = fixture.fullEvidenceSnapshot()

        val second = fixture.recovery().recover()

        assertEquals(ExtensionRecoveryResult(true, 0, 0), second)
        assertEquals(recordsAfterFirstRecovery, fixture.registry.list())
        assertTrue(fixture.target(registered).isDirectory)
        assertTrue(fixture.target(survivor).isDirectory)
        assertEquals(evidenceAfterFirstRecovery, fixture.fullEvidenceSnapshot())
    }

    @Test
    fun `candidate conflict group survives partial quarantine without selecting a retry winner`() {
        val fixture = fixture("partial-candidate-conflict")
        val pairOne = record("PartialPairOne", "partial")
        val pairTwo = record("PartialPairTwo", "partial").copy(repositoryUrl = pairOne.repositoryUrl.uppercase())
        listOf(pairOne, pairTwo).forEach(fixture::writeTarget)
        val moveAttempts = AtomicInteger()
        val failingQuarantine = ExtensionDirectoryQuarantine(
            fixture.paths,
            directoryMover = { source, target ->
                if (moveAttempts.incrementAndGet() == 2) throw IOException("injected second conflict move failure")
                move(source, target)
            }
        )

        val first = fixture.recovery(quarantine = failingQuarantine).recover()

        assertEquals(ExtensionRecoveryResult(false, 0, 1), first)
        assertEquals(2, moveAttempts.get())
        assertEquals(emptyList<ExtensionRecord>(), fixture.registry.list())
        assertEquals(1, listOf(pairOne, pairTwo).count { fixture.target(it).isDirectory })
        assertRecoveryRequired(fixture.coordinator)

        val second = fixture.recovery().recover()

        assertEquals(ExtensionRecoveryResult(true, 0, 1), second)
        assertEquals(emptyList<ExtensionRecord>(), fixture.registry.list())
        listOf(pairOne, pairTwo).forEach { assertFalse(fixture.target(it).exists()) }
        assertEquals(setOf(pairOne.folderName, pairTwo.folderName), fixture.quarantinedDirectoryNames())
        val conflictDiagnostics = fixture.paths.quarantineDir.resolve("extensions")
            .walkTopDown()
            .filter { it.isFile && it.name == "diagnostic.json" }
            .map { JsonParser.parseString(it.readText()).asJsonObject }
            .filter { it.get("reason")?.asString == "conflicting_extension_record" }
            .toList()
        assertTrue(conflictDiagnostics.isNotEmpty())
        conflictDiagnostics.forEach { diagnostic ->
            assertEquals(setOf("reason", "source", "operation", "timestamp"), diagnostic.keySet())
            assertTrue(
                diagnostic.get("operation").asString.matches(
                    Regex("recovery_conflict:[0-9a-f-]{36}:2:completed")
                )
            )
        }
        val completedHistoricalSidecar = fixture.paths.quarantineDir.resolve("extensions")
            .walkTopDown()
            .first { it.isFile && it.name == SIDECAR && requireNotNull(it.parentFile).name == pairOne.folderName }
        completedHistoricalSidecar.writeText("{completed-history-corruption")

        val laterCandidate = record("LaterCandidate", "later").copy(repositoryUrl = pairOne.repositoryUrl)
        fixture.writeTarget(laterCandidate)

        assertEquals(ExtensionRecoveryResult(true, 0, 0), fixture.recovery().recover())
        assertEquals(listOf(laterCandidate), fixture.registry.list())
        assertTrue(fixture.target(laterCandidate).isDirectory)
    }

    @Test
    fun `pending conflict diagnostic tampering fails closed without selecting the active retry candidate`() {
        val mutations = listOf<Pair<String, (File) -> Unit>>(
            "missing" to { it.delete() },
            "malformed" to { it.writeText("{malformed") },
            "single-quotes" to { file -> file.writeText(file.readText().replace('"', '\'')) },
            "unquoted-name" to { file ->
                file.writeText(file.readText().replaceFirst("\"reason\"", "reason"))
            },
            "comment" to { file -> file.writeText(file.readText().replaceFirst("{", "{/*comment*/")) },
            "trailing-content" to { file -> file.appendText(" true") },
            "duplicate-key" to { file ->
                file.writeText(
                    file.readText().replaceFirst(
                        "\"reason\":\"conflicting_extension_record\"",
                        "\"reason\":\"conflicting_extension_record\",\"reason\":\"conflicting_extension_record\""
                    )
                )
            },
            "reason-mismatch" to { file ->
                file.writeText(file.readText().replace(CONFLICT_REASON, "invalid_extension_sidecar"))
            },
            "operation-mismatch" to { file ->
                file.writeText(file.readText().replace("recovery_conflict:", "invalid_recovery_conflict:"))
            }
        )

        mutations.forEach { (name, mutate) ->
            val fixture = fixture("pending-diagnostic-$name")
            val partial = fixture.seedPartialConflict()
            mutate(partial.pendingDiagnostic)
            val evidence = fixture.fullEvidenceSnapshot()

            val result = fixture.recovery().recover()

            assertEquals(name, ExtensionRecoveryResult(false, 0, 0), result)
            assertEquals(name, emptyList<ExtensionRecord>(), fixture.registry.list())
            assertTrue("$name: active candidate remains", fixture.target(partial.activeRecord).isDirectory)
            assertEquals("$name: all evidence remains", evidence, fixture.fullEvidenceSnapshot())
            assertRecoveryRequired(fixture.coordinator)
        }
    }

    @Test
    fun `ordinary non conflict quarantine history does not block a later valid candidate`() {
        val fixture = fixture("ordinary-quarantine-history")
        val historical = record("HistoricalInvalid", "history")
        fixture.writeTarget(historical)
        fixture.quarantine.move(fixture.target(historical), "invalid_extension_sidecar", "recovery")
        val laterCandidate = record("LaterOrdinaryCandidate", "later")
        fixture.writeTarget(laterCandidate)

        val result = fixture.recovery().recover()

        assertEquals(ExtensionRecoveryResult(true, 0, 0), result)
        assertEquals(listOf(laterCandidate), fixture.registry.list())
        assertTrue(fixture.target(laterCandidate).isDirectory)
        assertEquals(setOf(historical.folderName), fixture.quarantinedDirectoryNames())
    }

    @Test
    fun `completed conflict moves resume after a partial diagnostic completion write`() {
        val fixture = fixture("partial-conflict-completion")
        val pairOne = record("CompletionPairOne", "completion")
        val pairTwo = record("CompletionPairTwo", "completion").copy(
            repositoryUrl = pairOne.repositoryUrl.uppercase()
        )
        listOf(pairOne, pairTwo).forEach(fixture::writeTarget)
        val completionWrites = AtomicInteger()
        val writer = AtomicFileStore(fixture.paths.quarantineDir)

        val first = fixture.recovery(
            conflictDiagnosticWriter = { file, text ->
                if (completionWrites.incrementAndGet() == 2) {
                    throw IOException("injected conflict completion write failure")
                }
                writer.writeText(file, text)
            }
        ).recover()

        assertEquals(ExtensionRecoveryResult(false, 0, 2), first)
        assertEquals(2, completionWrites.get())
        assertEquals(emptyList<ExtensionRecord>(), fixture.registry.list())
        listOf(pairOne, pairTwo).forEach { assertFalse(fixture.target(it).exists()) }
        val operationsAfterFailure = fixture.conflictDiagnosticOperations()
        assertEquals(1, operationsAfterFailure.count { it.endsWith(":pending") })
        assertEquals(1, operationsAfterFailure.count { it.endsWith(":completed") })
        assertRecoveryRequired(fixture.coordinator)

        val second = fixture.recovery().recover()

        assertEquals(ExtensionRecoveryResult(true, 0, 0), second)
        assertEquals(emptyList<ExtensionRecord>(), fixture.registry.list())
        assertTrue(fixture.conflictDiagnosticOperations().all { it.endsWith(":completed") })
        fixture.coordinator.requireRecoveryReady()
    }

    @Test
    fun `candidate repository conflicts with the original registry snapshot after missing target removal`() {
        val fixture = fixture("missing-registered-repository-conflict")
        val missingRegistered = record("MissingRegistered", "missing")
        fixture.registry.install(missingRegistered)
        val candidate = record("RepositoryCandidate", "candidate").copy(
            repositoryUrl = missingRegistered.repositoryUrl.uppercase()
        )
        fixture.writeTarget(candidate)

        val result = fixture.recovery().recover()

        assertEquals(ExtensionRecoveryResult(true, 1, 1), result)
        assertEquals(emptyList<ExtensionRecord>(), fixture.registry.list())
        assertFalse(fixture.target(candidate).exists())
        assertEquals(setOf(candidate.folderName), fixture.quarantinedDirectoryNames())
    }

    @Test
    fun `candidate folder conflicts case insensitively with missing original registry target`() {
        val fixture = fixture("missing-registered-folder-conflict")
        val missingRegistered = record("MissingFolder", "missing")
        fixture.registry.install(missingRegistered)
        val candidate = record(missingRegistered.folderName.lowercase(), "candidate")
        fixture.writeTarget(candidate)

        val result = fixture.recovery().recover()

        assertEquals(ExtensionRecoveryResult(true, 1, 1), result)
        assertEquals(emptyList<ExtensionRecord>(), fixture.registry.list())
        assertFalse(fixture.target(candidate).exists())
        assertEquals(setOf(candidate.folderName), fixture.quarantinedDirectoryNames())
    }

    @Test
    fun `registered target classification skips only the exact basename`() {
        val registeredBasenames = setOf("Foo")
        assertTrue(isExactRegisteredExtensionTargetBasename("Foo", registeredBasenames))
        assertFalse(isExactRegisteredExtensionTargetBasename("foo", registeredBasenames))

        val fixture = fixture("case-distinct-registered-target")
        val registered = record("Foo", "registered")
        fixture.registry.install(registered)
        fixture.writeTarget(registered)
        val caseVariant = fixture.paths.extensionsDir.resolve("foo")
        if (Files.exists(caseVariant.toPath(), NOFOLLOW_LINKS)) {
            assertTrue(
                "case-insensitive hosts must not create or move a second spelling of the registered target",
                Files.isSameFile(fixture.target(registered).toPath(), caseVariant.toPath())
            )
            return
        }

        val candidate = record("foo", "candidate").copy(repositoryUrl = registered.repositoryUrl.uppercase())
        fixture.writeTarget(candidate)

        val result = fixture.recovery().recover()

        assertEquals(ExtensionRecoveryResult(true, 0, 1), result)
        assertEquals(listOf(registered), fixture.registry.list())
        assertTrue(fixture.target(registered).isDirectory)
        assertFalse(Files.exists(caseVariant.toPath(), NOFOLLOW_LINKS))
        assertEquals(setOf(candidate.folderName), fixture.quarantinedDirectoryNames())
    }

    @Test
    fun `no journal reconciliation restores previous removes stale staging and quarantines only strict orphans`() {
        val fixture = fixture("legacy-and-stale")
        val restored = record("RestoreMe", "restore")
        val missing = record("MissingTarget", "missing")
        fixture.registry.replaceAll(listOf(restored, missing))
        fixture.writeDirectory(fixture.paths.extensionsDir.resolve(".${restored.folderName}.previous"), restored, "old")
        val transactionId = "123e4567-e89b-12d3-a456-426614174000"
        fixture.directory(".stapk-txn-$transactionId.installing")
        fixture.directory(".stapk-txn-$transactionId.backup")
        fixture.directory(".stapk-txn-$transactionId.trash")
        fixture.directory(".Orphan.previous")
        val unknown = fixture.directory(".unknown-hidden")
        val almostTxn = fixture.directory(".stapk-txn-NOT-A-UUID.backup")

        val result = fixture.recovery().recover()

        assertTrue(result.ready)
        assertEquals(listOf(restored), fixture.registry.list())
        assertEquals(restored, fixture.sidecar(fixture.target(restored)))
        assertFalse(fixture.paths.extensionsDir.resolve(".stapk-txn-$transactionId.installing").exists())
        assertEquals(
            setOf(".stapk-txn-$transactionId.backup", ".stapk-txn-$transactionId.trash", ".Orphan.previous"),
            fixture.quarantinedDirectoryNames()
        )
        assertTrue(unknown.isDirectory)
        assertTrue(almostTxn.isDirectory)
    }

    @Test
    fun `legacy previous recovery plans from one mutable directory snapshot`() {
        val successFixture = fixture("legacy-snapshot-success")
        val successRecord = record("SnapshotRestore", "success")
        successFixture.registry.install(successRecord)
        val successPrevious = successFixture.writeDirectory(
            successFixture.paths.extensionsDir.resolve(".${successRecord.folderName}.previous"),
            successRecord,
            "preserved-success"
        )
        val successListCalls = AtomicInteger()

        val success = successFixture.recovery(
            directoryLister = { directory ->
                successListCalls.incrementAndGet()
                directory.listFiles()
            }
        ).recover()

        assertEquals(ExtensionRecoveryResult(true, 1, 0), success)
        assertEquals(1, successListCalls.get())
        assertFalse(successPrevious.exists())
        assertEquals(successRecord, successFixture.registry.find(successRecord.folderName))
        assertEquals("preserved-success", successFixture.target(successRecord).resolve("content.txt").readText())

        val failureFixture = fixture("legacy-snapshot-move-failure")
        val failureRecord = record("SnapshotMoveFailure", "failure")
        failureFixture.registry.install(failureRecord)
        val failurePrevious = failureFixture.writeDirectory(
            failureFixture.paths.extensionsDir.resolve(".${failureRecord.folderName}.previous"),
            failureRecord,
            "preserved-failure"
        )
        val failureListCalls = AtomicInteger()

        val failed = failureFixture.recovery(
            mover = { _, _ -> throw IOException("injected previous move failure") },
            directoryLister = { directory ->
                failureListCalls.incrementAndGet()
                directory.listFiles()
            }
        ).recover()

        assertEquals(ExtensionRecoveryResult(false, 0, 0), failed)
        assertEquals(1, failureListCalls.get())
        assertTrue(failurePrevious.isDirectory)
        assertFalse(failureFixture.target(failureRecord).exists())
        assertEquals(failureRecord, failureFixture.registry.find(failureRecord.folderName))

        val retried = failureFixture.recovery(
            directoryLister = { directory ->
                failureListCalls.incrementAndGet()
                directory.listFiles()
            }
        ).recover()

        assertEquals(ExtensionRecoveryResult(true, 1, 0), retried)
        assertEquals(2, failureListCalls.get())
        assertEquals(failureRecord, failureFixture.registry.find(failureRecord.folderName))
        assertEquals("preserved-failure", failureFixture.target(failureRecord).resolve("content.txt").readText())

        val staleFixture = fixture("legacy-snapshot-no-realtime-exists")
        val staleRecord = record("SnapshotStaleTarget", "stale")
        staleFixture.registry.install(staleRecord)
        staleFixture.writeDirectory(
            staleFixture.paths.extensionsDir.resolve(".${staleRecord.folderName}.previous"),
            staleRecord,
            "previous-evidence"
        )
        val staleTarget = staleFixture.writeTarget(staleRecord)
        val staleListCalls = AtomicInteger()
        val staleMoverCalled = AtomicBoolean(false)

        val stale = staleFixture.recovery(
            mover = { source, target ->
                staleMoverCalled.set(true)
                move(source, target)
            },
            directoryLister = { directory ->
                staleListCalls.incrementAndGet()
                val snapshot = directory.listFiles()
                assertTrue(staleTarget.deleteRecursively())
                snapshot
            }
        ).recover()

        assertEquals(ExtensionRecoveryResult(false, 0, 1), stale)
        assertEquals(1, staleListCalls.get())
        assertFalse("snapshot target presence must prevent a realtime previous-to-target move", staleMoverCalled.get())
        assertEquals(staleRecord, staleFixture.registry.find(staleRecord.folderName))
        assertFalse(staleFixture.target(staleRecord).exists())
    }

    @Test
    fun `corrupt registry and journal are quarantined before safe sidecar rebuild`() {
        val fixture = fixture("corrupt-state")
        val record = record("Rebuild", "rebuild")
        fixture.writeTarget(record)
        fixture.paths.extensionRegistryFile.parentFile?.mkdirs()
        fixture.paths.extensionRegistryFile.writeText("[{invalid")
        fixture.paths.extensionTransactionFile.writeText("{'invalid':true}")

        val first = fixture.recovery().recover()

        assertTrue(first.ready)
        assertEquals(listOf(record), fixture.registry.list())
        assertFalse(fixture.paths.extensionTransactionFile.exists())
        assertTrue(fixture.paths.quarantineDir.walkTopDown().any { it.isFile && it.name == "extensions.json" })
        assertTrue(fixture.paths.quarantineDir.walkTopDown().any { it.isFile && it.name == "extension-transaction.json" })
        val before = fixture.paths.quarantineDir.walkTopDown().filter(File::isFile).map(File::getPath).toSet()
        assertEquals(ExtensionRecoveryResult(true, 0, 0), fixture.recovery().recover())
        assertEquals(before, fixture.paths.quarantineDir.walkTopDown().filter(File::isFile).map(File::getPath).toSet())
    }

    @Test
    fun `directory move registry write remover and journal clear failures preserve evidence and publish not ready`() {
        val moveFixture = fixture("move-failure")
        val previousRecord = record("MoveFailure", "move")
        moveFixture.registry.install(previousRecord)
        moveFixture.writeDirectory(
            moveFixture.paths.extensionsDir.resolve(".${previousRecord.folderName}.previous"),
            previousRecord,
            "old"
        )
        val moveResult = moveFixture.recovery(mover = { _, _ -> throw IOException("move failed") }).recover()
        assertFalse(moveResult.ready)
        assertTrue(moveFixture.paths.extensionsDir.resolve(".${previousRecord.folderName}.previous").isDirectory)
        assertRecoveryRequired(moveFixture.coordinator)

        val writeFixture = fixture("write-failure")
        val rebuild = record("WriteFailure", "write")
        writeFixture.writeTarget(rebuild)
        val failingStore = AtomicFileStore.forTesting(writeFixture.paths.quarantineDir) { _, _ ->
            throw IOException("write failed")
        }
        val failingRegistry = ExtensionRegistry(writeFixture.paths, failingStore)
        val writeResult = writeFixture.recovery(registry = failingRegistry).recover()
        assertFalse(writeResult.ready)
        assertTrue(writeFixture.target(rebuild).isDirectory)
        assertFalse(writeFixture.paths.extensionRegistryFile.exists())

        val removerFixture = fixture("remover-failure")
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val staging = removerFixture.directory(".stapk-txn-$id.installing")
        val removeResult = removerFixture.recovery(remover = { false }).recover()
        assertFalse(removeResult.ready)
        assertTrue(staging.isDirectory)

        val clearFixture = fixture("clear-failure")
        val scenario = Scenario("clear", ExtensionOperation.INSTALL, ExtensionTransactionPhase.PREPARED, RegistryState.NONE, DiskState.INSTALL_PREPARED, FinalState.NONE, 0)
        clearFixture.seed(scenario)
        val unclearedJournal = ExtensionTransactionJournal(clearFixture.paths, fileRemover = { false })
        val clearResult = clearFixture.recovery(journal = unclearedJournal).recover()
        assertFalse(clearResult.ready)
        assertTrue(clearFixture.paths.extensionTransactionFile.isFile)
    }

    @Test
    fun `rollback completed before journal clear failure is recognized idempotently on retry`() {
        val scenarios = listOf(
            Scenario("install-clear-retry", ExtensionOperation.INSTALL, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.NONE, DiskState.INSTALL_ACTIVATED, FinalState.NONE, 1),
            Scenario("update-clear-retry", ExtensionOperation.UPDATE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.OLD, DiskState.UPDATE_ACTIVATED, FinalState.OLD, 1),
            Scenario("delete-clear-retry", ExtensionOperation.DELETE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.OLD, DiskState.DELETE_ACTIVATED, FinalState.OLD, 0)
        )

        scenarios.forEach { scenario ->
            val fixture = fixture(scenario.name)
            fixture.seed(scenario)
            val unclearedJournal = ExtensionTransactionJournal(fixture.paths, fileRemover = { false })

            val first = fixture.recovery(journal = unclearedJournal).recover()
            assertFalse("${scenario.name}: clear failure is not ready", first.ready)
            assertTrue("${scenario.name}: journal remains", fixture.paths.extensionTransactionFile.isFile)
            fixture.assertFinal(scenario.finalState)
            val quarantineEntries = fixture.quarantinedDirectoryNames()

            val second = fixture.recovery().recover()
            assertEquals("${scenario.name}: retry finishes journal", ExtensionRecoveryResult(true, 1, 0), second)
            assertEquals("${scenario.name}: retry does not quarantine twice", quarantineEntries, fixture.quarantinedDirectoryNames())
            fixture.assertFinal(scenario.finalState)
        }
    }

    @Test
    fun `update rollback reentry restores backup after target quarantine succeeded and restore move failed`() {
        val fixture = fixture("update-rollback-reentry")
        val scenario = Scenario(
            "update-rollback-reentry",
            ExtensionOperation.UPDATE,
            ExtensionTransactionPhase.FILES_ACTIVATED,
            RegistryState.OLD,
            DiskState.UPDATE_ACTIVATED,
            FinalState.OLD,
            1
        )
        fixture.seed(scenario)
        val target = fixture.target(fixture.oldRecord)
        var restoreAttempts = 0

        val first = fixture.recovery(
            mover = { source, destination ->
                if (samePath(source, fixture.backup) && samePath(destination, target)) {
                    restoreAttempts += 1
                    throw IOException("injected backup restore failure")
                }
                move(source, destination)
            }
        ).recover()

        assertEquals(ExtensionRecoveryResult(false, 0, 1), first)
        assertEquals(1, restoreAttempts)
        assertTrue(fixture.paths.extensionTransactionFile.isFile)
        assertFalse(target.exists())
        assertTrue(fixture.backup.isDirectory)
        assertEquals(setOf(FOLDER), fixture.quarantinedDirectoryNames())
        assertRecoveryRequired(fixture.coordinator)

        val quarantinesAfterFirstAttempt = fixture.quarantinedDirectoryNames()
        val second = fixture.recovery(
            mover = { source, destination ->
                if (samePath(source, fixture.backup) && samePath(destination, target)) restoreAttempts += 1
                move(source, destination)
            }
        ).recover()

        assertEquals(ExtensionRecoveryResult(true, 1, 0), second)
        assertEquals(2, restoreAttempts)
        assertEquals(quarantinesAfterFirstAttempt, fixture.quarantinedDirectoryNames())
        fixture.assertFinal(FinalState.OLD)
        assertEquals(ExtensionRecoveryResult(true, 0, 0), fixture.recovery().recover())
        assertEquals(quarantinesAfterFirstAttempt, fixture.quarantinedDirectoryNames())
    }

    @Test
    fun `commit before phase cleanup clear retry completes install update and delete idempotently`() {
        val scenarios = listOf(
            Scenario("install-commit-clear-retry", ExtensionOperation.INSTALL, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.NEW, DiskState.INSTALL_ACTIVATED, FinalState.NEW, 0),
            Scenario("update-commit-clear-retry", ExtensionOperation.UPDATE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.NEW, DiskState.UPDATE_ACTIVATED, FinalState.NEW, 0),
            Scenario("delete-commit-clear-retry", ExtensionOperation.DELETE, ExtensionTransactionPhase.FILES_ACTIVATED, RegistryState.NONE, DiskState.DELETE_ACTIVATED, FinalState.NONE, 0)
        )

        scenarios.forEach { scenario ->
            val fixture = fixture(scenario.name)
            fixture.seed(scenario)
            val unclearedJournal = ExtensionTransactionJournal(fixture.paths, fileRemover = { false })

            val first = fixture.recovery(journal = unclearedJournal).recover()

            assertFalse("${scenario.name}: first clear failure is not ready", first.ready)
            assertTrue("${scenario.name}: journal remains after clear failure", fixture.paths.extensionTransactionFile.isFile)
            fixture.assertFinal(scenario.finalState)
            val quarantineEntries = fixture.quarantinedDirectoryNames()

            val second = fixture.recovery().recover()

            assertEquals("${scenario.name}: retry only clears journal", ExtensionRecoveryResult(true, 1, 0), second)
            assertFalse("${scenario.name}: journal cleared on retry", fixture.paths.extensionTransactionFile.exists())
            assertEquals("${scenario.name}: retry does not quarantine", quarantineEntries, fixture.quarantinedDirectoryNames())
            fixture.assertFinal(scenario.finalState)
        }
    }

    @Test
    fun `diagnostic logging failure never changes a successful recovery conclusion`() {
        val fixture = fixture("diagnostic-failure")
        val notDirectory = fixture.paths.logsDir.apply {
            parentFile?.mkdirs()
            writeText("blocks logger")
        }

        val result = fixture.recovery(logger = DiagnosticLogger(notDirectory)).recover()

        assertEquals(ExtensionRecoveryResult(true, 0, 0), result)
        fixture.coordinator.requireRecoveryReady()
    }

    @Test
    fun `subsystem completes recovery before exposing routes and records recovery counts`() {
        val fixture = fixture("subsystem-recovery")
        fixture.seed(
            Scenario(
                "subsystem-recovery",
                ExtensionOperation.INSTALL,
                ExtensionTransactionPhase.PREPARED,
                RegistryState.NONE,
                DiskState.INSTALL_PREPARED,
                FinalState.NONE,
                0
            )
        )
        val logger = DiagnosticLogger(fixture.paths.logsDir)

        val subsystem = createExtensionSubsystem(
            fixture.paths,
            AtomicFileStore(fixture.paths.quarantineDir, logger),
            logger
        )

        assertEquals(ExtensionRecoveryResult(true, 1, 0), subsystem.recoveryResult)
        assertFalse(fixture.paths.extensionTransactionFile.exists())
        val event = JsonParser.parseString(fixture.paths.logsDir.resolve("diagnostics.jsonl").readLines().last())
            .asJsonObject
        assertEquals("extension_recovery", event.get("code").asString)
        assertEquals("1", event.getAsJsonObject("fields").get("recoveredCount").asString)
        assertEquals("0", event.getAsJsonObject("fields").get("quarantinedCount").asString)
    }

    private enum class RegistryState { NONE, OLD, NEW, OTHER }

    private enum class DiskState {
        INSTALL_PREPARED,
        INSTALL_ACTIVATED,
        UPDATE_PREPARED,
        UPDATE_BACKUP_ONLY,
        UPDATE_ACTIVATED,
        UPDATE_TARGET_NEW_ONLY,
        DELETE_PREPARED,
        DELETE_ACTIVATED,
        DELETE_BOTH
    }

    private enum class FinalState { NONE, OLD, NEW }

    private data class Scenario(
        val name: String,
        val operation: ExtensionOperation,
        val phase: ExtensionTransactionPhase,
        val registryState: RegistryState,
        val diskState: DiskState,
        val finalState: FinalState,
        val quarantines: Int
    )

    private data class PartialConflict(
        val activeRecord: ExtensionRecord,
        val pendingDiagnostic: File
    )

    private data class Fixture(
        val paths: NativeAdapterPaths,
        val registry: ExtensionRegistry,
        val journal: ExtensionTransactionJournal,
        val quarantine: ExtensionDirectoryQuarantine,
        val coordinator: ExtensionMutationCoordinator
    ) {
        val oldRecord = record(FOLDER, "old")
        val newRecord = record(FOLDER, "new")
        val otherRecord = record(FOLDER, "other")
        val staging: File get() = paths.extensionsDir.resolve(".stapk-txn-$TRANSACTION_ID.installing")
        val backup: File get() = paths.extensionsDir.resolve(".stapk-txn-$TRANSACTION_ID.backup")
        val trash: File get() = paths.extensionsDir.resolve(".stapk-txn-$TRANSACTION_ID.trash")

        fun recovery(
            registry: ExtensionRegistry = this.registry,
            journal: ExtensionTransactionJournal = this.journal,
            quarantine: ExtensionDirectoryQuarantine = this.quarantine,
            logger: DiagnosticLogger? = null,
            mover: (File, File) -> Unit = ::move,
            remover: ((File) -> Boolean)? = null,
            symlinkChecker: (File) -> Boolean = { Files.isSymbolicLink(it.toPath()) },
            directoryLister: (File) -> Array<File>? = File::listFiles,
            conflictDiagnosticWriter: ((File, String) -> Unit)? = null
        ): ExtensionRecovery = if (remover == null) {
            ExtensionRecovery(
                paths,
                registry,
                journal,
                quarantine,
                coordinator,
                logger,
                directoryMover = mover,
                symbolicLinkChecker = symlinkChecker,
                directoryLister = directoryLister,
                conflictDiagnosticWriter = conflictDiagnosticWriter
            )
        } else {
            ExtensionRecovery(
                paths,
                registry,
                journal,
                quarantine,
                coordinator,
                logger,
                directoryMover = mover,
                directoryRemover = remover,
                symbolicLinkChecker = symlinkChecker,
                directoryLister = directoryLister,
                conflictDiagnosticWriter = conflictDiagnosticWriter
            )
        }

        fun seed(scenario: Scenario) {
            paths.extensionsDir.mkdirs()
            when (scenario.registryState) {
                RegistryState.NONE -> Unit
                RegistryState.OLD -> registry.install(oldRecord)
                RegistryState.NEW -> registry.install(newRecord)
                RegistryState.OTHER -> registry.install(otherRecord)
            }
            when (scenario.diskState) {
                DiskState.INSTALL_PREPARED -> writeDirectory(staging, newRecord, "new")
                DiskState.INSTALL_ACTIVATED -> writeTarget(newRecord)
                DiskState.UPDATE_PREPARED -> {
                    writeTarget(oldRecord)
                    writeDirectory(staging, newRecord, "new")
                }
                DiskState.UPDATE_BACKUP_ONLY -> {
                    writeDirectory(backup, oldRecord, "old")
                    writeDirectory(staging, newRecord, "new")
                }
                DiskState.UPDATE_ACTIVATED -> {
                    writeTarget(newRecord)
                    writeDirectory(backup, oldRecord, "old")
                }
                DiskState.UPDATE_TARGET_NEW_ONLY -> writeTarget(newRecord)
                DiskState.DELETE_PREPARED -> writeTarget(oldRecord)
                DiskState.DELETE_ACTIVATED -> writeDirectory(trash, oldRecord, "old")
                DiskState.DELETE_BOTH -> {
                    writeTarget(oldRecord)
                    writeDirectory(trash, oldRecord, "old")
                }
            }
            journal.write(
                ExtensionTransaction(
                    transactionId = TRANSACTION_ID,
                    operation = scenario.operation,
                    phase = scenario.phase,
                    folderName = FOLDER,
                    oldRecord = scenario.operation.takeUnless { it == ExtensionOperation.INSTALL }?.let { oldRecord },
                    newRecord = scenario.operation.takeUnless { it == ExtensionOperation.DELETE }?.let { newRecord },
                    stagingName = scenario.operation.takeUnless { it == ExtensionOperation.DELETE }?.let { staging.name },
                    backupName = scenario.operation.takeIf { it == ExtensionOperation.UPDATE }?.let { backup.name },
                    trashName = scenario.operation.takeIf { it == ExtensionOperation.DELETE }?.let { trash.name }
                )
            )
        }

        fun assertFinal(state: FinalState) {
            when (state) {
                FinalState.NONE -> {
                    assertNull(registry.find(FOLDER))
                    assertFalse(paths.extensionsDir.resolve(FOLDER).exists())
                }
                FinalState.OLD -> {
                    assertEquals(oldRecord, registry.find(FOLDER))
                    assertEquals(oldRecord, sidecar(paths.extensionsDir.resolve(FOLDER)))
                    assertEquals("old", paths.extensionsDir.resolve(FOLDER).resolve("content.txt").readText())
                }
                FinalState.NEW -> {
                    assertEquals(newRecord, registry.find(FOLDER))
                    assertEquals(newRecord, sidecar(paths.extensionsDir.resolve(FOLDER)))
                    assertEquals("new", paths.extensionsDir.resolve(FOLDER).resolve("content.txt").readText())
                }
            }
            assertFalse(staging.exists())
            assertFalse(backup.exists())
            assertFalse(trash.exists())
        }

        fun target(record: ExtensionRecord): File = paths.extensionsDir.resolve(record.folderName)

        fun directory(name: String): File = paths.extensionsDir.resolve(name).apply { mkdirs() }

        fun writeTarget(record: ExtensionRecord) = writeDirectory(target(record), record, record.commitSha)

        fun writeDirectory(directory: File, record: ExtensionRecord, content: String): File = directory.apply {
            mkdirs()
            resolve("content.txt").writeText(content)
            resolve(SIDECAR).writeText(ExtensionRecordCodec.encode(record))
        }

        fun seedPartialConflict(): PartialConflict {
            val pairOne = record("PendingPairOne", "pending")
            val pairTwo = record("PendingPairTwo", "pending").copy(
                repositoryUrl = pairOne.repositoryUrl.uppercase()
            )
            listOf(pairOne, pairTwo).forEach(::writeTarget)
            val moveAttempts = AtomicInteger()
            val failingQuarantine = ExtensionDirectoryQuarantine(
                paths,
                directoryMover = { source, target ->
                    if (moveAttempts.incrementAndGet() == 2) throw IOException("injected second conflict move failure")
                    move(source, target)
                }
            )

            assertEquals(
                ExtensionRecoveryResult(false, 0, 1),
                recovery(quarantine = failingQuarantine).recover()
            )
            val active = listOf(pairOne, pairTwo).single { target(it).isDirectory }
            val pendingDiagnostic = paths.quarantineDir.resolve("extensions")
                .walkTopDown()
                .filter { it.isFile && it.name == "diagnostic.json" }
                .single {
                    val diagnostic = JsonParser.parseString(it.readText()).asJsonObject
                    diagnostic.get("operation").asString.endsWith(":pending") &&
                        requireNotNull(it.parentFile)
                            .resolve(diagnostic.get("source").asString)
                            .resolve(SIDECAR)
                            .isFile
                }
            val pendingBatch = requireNotNull(pendingDiagnostic.parentFile)
            requireNotNull(pendingBatch.parentFile)
                .listFiles().orEmpty()
                .filter { it != pendingBatch }
                .forEach { assertTrue(it.deleteRecursively()) }
            return PartialConflict(active, pendingDiagnostic)
        }

        fun sidecar(directory: File): ExtensionRecord = ExtensionRecordCodec.decode(directory.resolve(SIDECAR).readText())

        fun evidenceSnapshot(): Map<String, String> = buildMap {
            listOf(paths.extensionTransactionFile, paths.extensionRegistryFile).forEach { file ->
                if (file.isFile) put(file.relativeTo(requireNotNull(paths.stateDir.parentFile)).path, file.readText())
            }
            if (paths.extensionsDir.exists()) {
                paths.extensionsDir.walkTopDown().forEach { file ->
                    val relative = file.relativeTo(paths.extensionsDir).path
                    put("extensions/$relative", if (file.isFile) file.readText() else "<dir>")
                }
            }
        }

        fun fullEvidenceSnapshot(): Map<String, String> = buildMap {
            putAll(evidenceSnapshot())
            if (paths.quarantineDir.exists()) {
                paths.quarantineDir.walkTopDown().forEach { file ->
                    val relative = file.relativeTo(paths.quarantineDir).path
                    put("quarantine/$relative", if (file.isFile) file.readText() else "<dir>")
                }
            }
        }

        fun quarantinedDirectoryNames(): Set<String> = paths.quarantineDir.resolve("extensions")
            .listFiles().orEmpty()
            .flatMap { batch -> batch.listFiles().orEmpty().filter(File::isDirectory) }
            .map(File::getName)
            .toSet()

        fun conflictDiagnosticOperations(): List<String> = paths.quarantineDir.resolve("extensions")
            .walkTopDown()
            .filter { it.isFile && it.name == "diagnostic.json" }
            .map { JsonParser.parseString(it.readText()).asJsonObject }
            .filter { it.get("reason")?.asString == "conflicting_extension_record" }
            .map { it.get("operation").asString }
            .toList()
    }

    private fun fixture(name: String): Fixture = fixture(Files.createTempDirectory("stapk-recovery-$name").toFile(), name)

    private fun fixture(filesDir: File, @Suppress("UNUSED_PARAMETER") name: String): Fixture {
        val paths = NativeAdapterPaths(filesDir)
        val registry = ExtensionRegistry(paths)
        val journal = ExtensionTransactionJournal(paths)
        val quarantine = ExtensionDirectoryQuarantine(paths)
        val coordinator = ExtensionMutationCoordinator(paths, registry, journal, quarantine)
        return Fixture(paths, registry, journal, quarantine, coordinator)
    }

    private fun assertRecoveryRequired(coordinator: ExtensionMutationCoordinator) {
        try {
            coordinator.requireRecoveryReady()
            fail("Expected ExtensionRecoveryRequiredException")
        } catch (_: ExtensionRecoveryRequiredException) {
            Unit
        }
    }

    private fun createDirectoryAlias(alias: File, target: File): File {
        val parent = requireNotNull(alias.parentFile)
        parent.mkdirs()
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            val caseVariant = parent.resolve(alias.name.uppercase())
            Files.move(target.toPath(), caseVariant.toPath())
            return caseVariant
        } else {
            Files.createSymbolicLink(alias.toPath(), target.toPath())
            return target
        }
    }

    private companion object {
        const val FOLDER = "Test-Extension"
        const val SIDECAR = ".stapk-extension.json"
        const val TRANSACTION_ID = "123e4567-e89b-12d3-a456-426614174000"
        const val CONFLICT_REASON = "conflicting_extension_record"

        fun record(folder: String, commit: String) = ExtensionRecord(
            folderName = folder,
            repositoryUrl = "https://github.com/owner/${if (commit == "shared") "shared" else folder}",
            owner = "owner",
            repository = if (commit == "shared") "shared" else folder,
            branch = "main",
            commitSha = commit,
            installedAt = 1L,
            updatedAt = if (commit == "old") 2L else 3L
        )

        fun move(source: File, target: File) {
            target.parentFile?.mkdirs()
            Files.move(source.toPath(), target.toPath())
        }

        fun samePath(first: File, second: File): Boolean =
            first.toPath().toAbsolutePath().normalize() == second.toPath().toAbsolutePath().normalize()
    }
}
