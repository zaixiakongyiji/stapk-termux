package com.stapk.mobile.nativeadapter.vector

import com.stapk.mobile.nativeadapter.AtomicFileStore
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import com.stapk.mobile.nativeadapter.SecretStore
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EmbeddingProviderConfigStoreTest {
    @Test
    fun `missing configuration defaults to OpenAI`() {
        val config = newStore().load()

        assertEquals(EmbeddingProviderType.OPENAI, config.type)
        assertEquals("https://api.openai.com/v1", config.baseUrl)
        assertEquals("text-embedding-3-small", config.model)
    }

    @Test
    fun `provider types expose stable wire contracts and secret slots`() {
        assertEquals("openai", EmbeddingProviderType.OPENAI.wireName)
        assertEquals("openai", EmbeddingProviderType.OPENAI.sourceId)
        assertEquals("api_key_openai", EmbeddingProviderType.OPENAI.secretKey)
        assertEquals("stapk_openai_compatible", EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE.wireName)
        assertEquals("stapk_openai_compatible", EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE.sourceId)
        assertEquals("api_key_embedding", EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE.secretKey)
    }

    @Test
    fun `default snapshot uses the OpenAI secret slot`() {
        val fixture = newFixture()
        fixture.secrets.write("api_key_openai", "openai-secret", "OpenAI")

        val snapshot = fixture.store.snapshot()

        assertEquals(EmbeddingProviderType.OPENAI, snapshot.config.type)
        assertEquals("openai-secret", snapshot.apiKey)
    }

    @Test
    fun `custom key does not replace OpenAI key`() {
        val fixture = newFixture()
        fixture.secrets.write("api_key_openai", "openai-secret", "OpenAI")

        fixture.store.save(
            EmbeddingProviderConfig(
                EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                "https://embedding.example.com/v1",
                "embed-model"
            ),
            "custom-secret"
        )

        assertEquals("openai-secret", fixture.secrets.load("api_key_openai")?.value)
        assertEquals("custom-secret", fixture.secrets.load("api_key_embedding")?.value)
    }

    @Test
    fun `saving OpenAI forces default endpoint without changing custom key`() {
        val fixture = newFixture()
        fixture.secrets.write("api_key_embedding", "custom-secret", "Embedding")

        val saved = fixture.store.save(
            EmbeddingProviderConfig(EmbeddingProviderType.OPENAI, "https://ignored.example/v1", "embed-model"),
            "openai-secret"
        )

        assertEquals("https://api.openai.com/v1", saved.baseUrl)
        assertEquals("openai-secret", fixture.secrets.load("api_key_openai")?.value)
        assertEquals("custom-secret", fixture.secrets.load("api_key_embedding")?.value)
    }

    @Test
    fun `custom HTTPS provider is persisted with its embedding key`() {
        val fixture = newFixture()
        val store = fixture.store

        val saved = store.save(
            EmbeddingProviderConfig(
                EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                "https://embedding.example.com/v1/",
                "embed-model"
            ),
            "top-secret"
        )

        assertEquals("https://embedding.example.com/v1", saved.baseUrl)
        assertEquals(saved, store.load())
        assertTrue(store.keyConfigured(EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE))
        val stored = JsonParser.parseString(fixture.paths.embeddingProviderConfigFile.readText()).asJsonObject
        assertEquals("stapk_openai_compatible", stored.get("type").asString)
    }

    @Test
    fun `secret write failure leaves the persisted endpoint unchanged`() {
        val fixture = newFixture()
        fixture.store.save(
            EmbeddingProviderConfig(EmbeddingProviderType.OPENAI, "https://ignored.example/v1", "old-model"),
            "old-key"
        )
        fixture.secrets.failNextWrite = true

        assertThrows(IllegalStateException::class.java) {
            fixture.store.save(
                EmbeddingProviderConfig(
                    EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                    "https://embedding.example.com/v1",
                    "new-model"
                ),
                "new-key"
            )
        }

        assertEquals(EmbeddingProviderType.OPENAI, fixture.store.load().type)
        assertEquals("old-model", fixture.store.load().model)
        assertEquals("old-key", fixture.store.snapshot().apiKey)
    }

    @Test
    fun `snapshot observes only complete old or new provider state`() {
        val fixture = newFixture()
        fixture.store.save(
            EmbeddingProviderConfig(EmbeddingProviderType.OPENAI, "https://ignored.example/v1", "old-model"),
            "old-key"
        )
        val secretWriteStarted = CountDownLatch(1)
        val releaseSecretWrite = CountDownLatch(1)
        fixture.secrets.beforeWrite = {
            secretWriteStarted.countDown()
            assertTrue(releaseSecretWrite.await(5, TimeUnit.SECONDS))
        }
        val saveThread = Thread {
            fixture.store.save(
                EmbeddingProviderConfig(
                    EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                    "https://embedding.example.com/v1",
                    "new-model"
                ),
                "new-key"
            )
        }
        saveThread.start()
        assertTrue(secretWriteStarted.await(5, TimeUnit.SECONDS))

        val snapshotResult = AtomicReference<EmbeddingProviderSnapshot>()
        val snapshotFailure = AtomicReference<Throwable>()
        val snapshotThread = Thread {
            runCatching { fixture.store.snapshot() }
                .onSuccess(snapshotResult::set)
                .onFailure(snapshotFailure::set)
        }
        snapshotThread.start()
        try {
            Thread.sleep(100)
            assertTrue("snapshot must wait for the in-flight save", snapshotThread.isAlive)
        } finally {
            releaseSecretWrite.countDown()
            saveThread.join(5_000)
            snapshotThread.join(5_000)
        }

        assertFalse(saveThread.isAlive)
        assertFalse(snapshotThread.isAlive)
        assertEquals(null, snapshotFailure.get())
        val snapshot = snapshotResult.get()
        assertEquals(EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE, snapshot.config.type)
        assertEquals("new-key", snapshot.apiKey)
    }

    @Test
    fun `custom provider rejects insecure remote http`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            newStore().save(
                EmbeddingProviderConfig(
                    EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                    "http://example.com/v1",
                    "embed-model"
                ),
                "secret"
            )
        }

        assertEquals("embedding_base_url_invalid", error.message)
    }

    @Test
    fun `loopback http is normalized and accepted`() {
        val saved = newStore().save(
            EmbeddingProviderConfig(
                EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                "http://127.0.0.1:8080/v1/",
                "embed-model"
            ),
            "secret"
        )

        assertEquals("http://127.0.0.1:8080/v1", saved.baseUrl)
    }

    @Test
    fun `provider rejects unsafe URL components`() {
        listOf(
            "https://user:password@example.com/v1",
            "https://example.com/v1?token=secret",
            "https://example.com/v1#fragment"
        ).forEach { baseUrl ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                newStore().save(
                    EmbeddingProviderConfig(EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE, baseUrl, "embed-model"),
                    "secret"
                )
            }
            assertEquals("embedding_base_url_invalid", error.message)
        }
    }

    @Test
    fun `provider rejects oversized base URL and model`() {
        val baseUrlError = assertThrows(IllegalArgumentException::class.java) {
            newStore().save(
                EmbeddingProviderConfig(
                    EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                    "https://example.com/${"a".repeat(2048)}",
                    "embed-model"
                ),
                "secret"
            )
        }
        val modelError = assertThrows(IllegalArgumentException::class.java) {
            newStore().save(
                EmbeddingProviderConfig(
                    EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                    "https://example.com/v1",
                    "m".repeat(257)
                ),
                "secret"
            )
        }

        assertEquals("embedding_base_url_invalid", baseUrlError.message)
        assertEquals("embedding_model_invalid", modelError.message)
    }

    @Test
    fun `custom snapshot uses embedding secret and fingerprints normalized endpoint and model`() {
        val fixture = newFixture()
        fixture.secrets.write("api_key_openai", "openai-secret", "OpenAI")
        fixture.store.save(
            EmbeddingProviderConfig(
                EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE,
                "https://embedding.example.com/v1/",
                "embed-model"
            ),
            "custom-secret"
        )

        val snapshot = fixture.store.snapshot()

        assertEquals(EmbeddingProviderType.STAPK_OPENAI_COMPATIBLE, snapshot.config.type)
        assertEquals("custom-secret", snapshot.apiKey)
        assertEquals("https://embedding.example.com/v1", snapshot.normalizedBaseUrl)
        assertEquals("3087871a441073e1c60c6740d8a17f91a1ab5596816de07998a8a894e0f77554", snapshot.endpointFingerprint)
        assertEquals("640f2506ed674aaf9d39754dc63537af3772a8810fcb228caeab2cffbb32b138", snapshot.modelFingerprint)
    }

    @Test
    fun `snapshot rejects a missing API key`() {
        val failure = assertThrows(EmbeddingFailure::class.java) { newStore().snapshot() }

        assertEquals(401, failure.httpStatus)
        assertEquals("embedding_key_missing", failure.errorCode)
    }

    @Test
    fun `embedding failure exposes cross-task status and code fields`() {
        val failure = EmbeddingFailure(401, "embedding_key_missing")

        assertEquals(401, failure.httpStatus)
        assertEquals("embedding_key_missing", failure.errorCode)
    }

    @Test
    fun `malformed configuration is quarantined and fails closed`() {
        val root = Files.createTempDirectory("stapk-embedding-invalid").toFile()
        val paths = NativeAdapterPaths(root)
        paths.embeddingProviderConfigFile.parentFile?.mkdirs()
        paths.embeddingProviderConfigFile.writeText("{invalid")
        val store = EmbeddingProviderConfigStore(paths, SecretStore(paths), AtomicFileStore(paths.quarantineDir))

        val failure = assertThrows(EmbeddingFailure::class.java) { store.load() }

        assertEquals(400, failure.httpStatus)
        assertEquals("vector_invalid_request", failure.errorCode)
        assertFalse(paths.embeddingProviderConfigFile.exists())
        assertTrue(paths.quarantineDir.listFiles().orEmpty().single().isDirectory)
    }

    private fun newStore(): EmbeddingProviderConfigStore {
        return newFixture().store
    }

    private fun newFixture(): Fixture {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-embedding").toFile())
        val secrets = TestSecretStore(paths)
        return Fixture(paths, secrets, EmbeddingProviderConfigStore(paths, secrets, AtomicFileStore(paths.quarantineDir)))
    }

    private data class Fixture(
        val paths: NativeAdapterPaths,
        val secrets: TestSecretStore,
        val store: EmbeddingProviderConfigStore
    )

    private class TestSecretStore(paths: NativeAdapterPaths) : SecretStore(paths) {
        @Volatile var failNextWrite = false
        @Volatile var beforeWrite: (() -> Unit)? = null

        override fun write(key: String, value: String, label: String): String? {
            beforeWrite?.invoke()
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("simulated secret write failure")
            }
            return super.write(key, value, label)
        }
    }
}
