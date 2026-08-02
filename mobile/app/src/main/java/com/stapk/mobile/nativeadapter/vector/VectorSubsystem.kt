package com.stapk.mobile.nativeadapter.vector

import android.content.Context
import com.stapk.mobile.nativeadapter.AtomicFileStore
import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import com.stapk.mobile.nativeadapter.SecretStore
import java.io.Closeable
import okhttp3.OkHttpClient

internal class EmbeddingHttpClientOwnership(
    private val client: OkHttpClient,
    private val ownedBySubsystem: Boolean
) : Closeable {
    override fun close() {
        if (!ownedBySubsystem) return
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

private data class OwnedEmbeddingHttpClient(
    val client: OkHttpClient,
    val ownership: EmbeddingHttpClientOwnership
)

private fun ownedEmbeddingHttpClient(): OwnedEmbeddingHttpClient {
    val client = embeddingHttpClient()
    return OwnedEmbeddingHttpClient(client, EmbeddingHttpClientOwnership(client, ownedBySubsystem = true))
}

class VectorSubsystem private constructor(
    paths: NativeAdapterPaths,
    diagnosticLogger: DiagnosticLogger,
    httpClient: OkHttpClient,
    clientOwnership: EmbeddingHttpClientOwnership,
    vectorRepository: VectorRepository,
    usableSpace: () -> Long
) : Closeable {
    private constructor(
        paths: NativeAdapterPaths,
        diagnosticLogger: DiagnosticLogger,
        ownedClient: OwnedEmbeddingHttpClient,
        vectorRepository: VectorRepository,
        usableSpace: () -> Long
    ) : this(
        paths,
        diagnosticLogger,
        ownedClient.client,
        ownedClient.ownership,
        vectorRepository,
        usableSpace
    )

    constructor(
        context: Context,
        paths: NativeAdapterPaths,
        diagnosticLogger: DiagnosticLogger
    ) : this(
        paths,
        diagnosticLogger,
        ownedEmbeddingHttpClient(),
        VectorRepository(VectorDatabaseHelper(context.applicationContext, paths, diagnosticLogger)),
        { context.filesDir.usableSpace }
    )

    constructor(
        context: Context,
        paths: NativeAdapterPaths,
        diagnosticLogger: DiagnosticLogger,
        httpClient: OkHttpClient
    ) : this(
        paths,
        diagnosticLogger,
        httpClient,
        EmbeddingHttpClientOwnership(httpClient, ownedBySubsystem = false),
        VectorRepository(VectorDatabaseHelper(context.applicationContext, paths, diagnosticLogger)),
        { context.filesDir.usableSpace }
    )

    internal constructor(
        context: Context,
        paths: NativeAdapterPaths,
        diagnosticLogger: DiagnosticLogger,
        httpClient: OkHttpClient,
        databaseName: String
    ) : this(
        paths,
        diagnosticLogger,
        httpClient,
        EmbeddingHttpClientOwnership(httpClient, ownedBySubsystem = false),
        VectorRepository(
            VectorDatabaseHelper(context.applicationContext, paths, diagnosticLogger, System::currentTimeMillis, databaseName)
        ),
        { context.filesDir.usableSpace }
    )

    private val repository = vectorRepository
    private val httpClientOwnership = clientOwnership
    private val configStore = EmbeddingProviderConfigStore(
        paths,
        SecretStore(paths),
        AtomicFileStore(paths.quarantineDir, diagnosticLogger)
    )

    val controller: VectorController = VectorController(
        configStore,
        EmbeddingProviderClient(httpClient, diagnosticLogger),
        repository,
        usableSpace,
        diagnosticLogger
    )

    override fun close() {
        try {
            repository.close()
        } finally {
            httpClientOwnership.close()
        }
    }
}
