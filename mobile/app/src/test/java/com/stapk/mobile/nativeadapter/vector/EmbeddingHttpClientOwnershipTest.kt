package com.stapk.mobile.nativeadapter.vector

import java.util.concurrent.Executors
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingHttpClientOwnershipTest {
    @Test
    fun `closing an injected client owner leaves caller dispatcher running`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).build()

            EmbeddingHttpClientOwnership(client, ownedBySubsystem = false).close()

            assertFalse(executor.isShutdown)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `closing an internally owned client owner evicts connections and shuts down dispatcher`() {
        val executor = Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).build()

        EmbeddingHttpClientOwnership(client, ownedBySubsystem = true).close()

        assertTrue(executor.isShutdown)
    }
}
