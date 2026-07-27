package com.stapk.mobile.nativeadapter

internal interface ExtensionRoutes {
    fun discover(): HttpResponse
    fun install(body: String): HttpResponse
    fun version(body: String): HttpResponse
    fun update(body: String): HttpResponse
    fun delete(body: String): HttpResponse
}

internal data class ExtensionSubsystem(
    val routes: ExtensionRoutes,
    val recoveryResult: ExtensionRecoveryResult
)

internal fun createExtensionSubsystem(
    paths: NativeAdapterPaths,
    store: AtomicFileStore,
    diagnosticLogger: DiagnosticLogger
): ExtensionSubsystem {
    val registry = ExtensionRegistry(paths, store)
    val journal = ExtensionTransactionJournal(paths, store)
    val quarantine = ExtensionDirectoryQuarantine(paths)
    val coordinator = ExtensionMutationCoordinator(paths, registry, journal, quarantine, diagnosticLogger)
    val recoveryResult = ExtensionRecovery(
        paths,
        registry,
        journal,
        quarantine,
        coordinator,
        diagnosticLogger
    ).recover()
    val controller = ExtensionController(
        paths,
        registry,
        GitHubExtensionClient(),
        ExtensionArchiveInstaller(paths),
        coordinator,
        diagnosticLogger
    )
    return ExtensionSubsystem(controller, recoveryResult)
}
