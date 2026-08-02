package com.stapk.mobile.nativeadapter

import android.content.Intent
import com.stapk.mobile.nativeadapter.vector.VectorRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeHttpServiceTest {
    @Test
    fun `runtime creates one vector subsystem and stops server before closing it`() {
        val events = mutableListOf<String>()
        val vectors = RecordingVectors(events)
        val server = RecordingServer(events)
        val runtime = NativeAdapterRuntime(RecordingFactory(vectors, server))

        val first = runtime.start()
        val second = runtime.start()

        assertEquals(NativeAdapterStatus.RUNNING, first.status)
        assertEquals(first, second)
        assertEquals(listOf("vectors.create", "server.create", "server.server.start"), events)
        assertSame(server, runtime.serverForTesting())

        runtime.stop()
        runtime.stop()

        assertEquals(
            listOf("vectors.create", "server.create", "server.server.start", "server.server.stop", "vectors.vectors.close"),
            events
        )
        assertNull(runtime.serverForTesting())
    }

    @Test
    fun `runtime closes vector subsystem when server start fails`() {
        val events = mutableListOf<String>()
        val vectors = RecordingVectors(events)
        val server = RecordingServer(events, failOnStart = true)
        val runtime = NativeAdapterRuntime(RecordingFactory(vectors, server))

        runCatching { runtime.start() }
            .onSuccess { throw AssertionError("Expected server start failure") }

        assertEquals(
            listOf("vectors.create", "server.create", "server.server.start", "server.server.stop", "vectors.vectors.close"),
            events
        )
        assertNull(runtime.serverForTesting())
    }

    @Test
    fun `start stop start stop closes each owner pair exactly once in server then subsystem order`() {
        val events = mutableListOf<String>()
        val firstVectors = RecordingVectors(events, "first")
        val firstServer = RecordingServer(events, name = "first")
        val secondVectors = RecordingVectors(events, "second")
        val secondServer = RecordingServer(events, name = "second")
        val runtime = NativeAdapterRuntime(
            SequenceFactory(
                listOf(firstVectors, secondVectors),
                listOf(firstServer, secondServer)
            )
        )

        runtime.start()
        runtime.stop()
        runtime.start()
        runtime.stop()

        assertEquals(
            listOf(
                "first.vectors.create", "first.server.create", "first.server.start", "first.server.stop", "first.vectors.close",
                "second.vectors.create", "second.server.create", "second.server.start", "second.server.stop", "second.vectors.close"
            ),
            events
        )
        assertNull(runtime.serverForTesting())
    }

    @Test
    fun `concurrent start and stop leave no published half owner or unclosed resources`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val vectors = RecordingVectors(events, "concurrent")
        val server = BlockingServer(events, startEntered, releaseStart)
        val runtime = NativeAdapterRuntime(SequenceFactory(listOf(vectors), listOf(server)))
        val failure = AtomicReference<Throwable?>()

        val startThread = Thread {
            runCatching { runtime.start() }.onFailure(failure::set)
        }
        val stopThread = Thread {
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            runtime.stop()
        }
        startThread.start()
        stopThread.start()
        assertTrue(startEntered.await(5, TimeUnit.SECONDS))
        releaseStart.countDown()
        startThread.join(5_000)
        stopThread.join(5_000)

        assertTrue(!startThread.isAlive)
        assertTrue(!stopThread.isAlive)
        assertNull(failure.get())
        assertEquals(
            listOf(
                "concurrent.vectors.create", "concurrent.server.create", "concurrent.server.start",
                "concurrent.server.stop", "concurrent.vectors.close"
            ),
            events
        )
        assertNull(runtime.serverForTesting())
    }

    @Test
    fun `actual service does not publish stale runtime after stop enters the post start gate`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val startEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val vectors = RecordingVectors(events, "stale", closed)
        val server = RecordingServer(events, name = "stale")
        val published = AtomicReference<NativeAdapterRuntime?>()
        setServiceTestingHook(
            "runtimeFactory",
            { _: android.content.Context, _: NativeAdapterPaths, _: DiagnosticLogger ->
                NativeAdapterRuntime(RecordingFactory(vectors, server))
            }
        )
        setServiceTestingHook("afterRuntimeStartBeforePublish") {
            startEntered.countDown()
            check(releasePublish.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release publish gate" }
        }
        setServiceTestingHook("runtimePublished", published::set)
        val controller = Robolectric.buildService(NativeHttpService::class.java).create()
        val service = controller.get()
        val binder = service.onBind(null) as NativeHttpService.LocalBinder
        try {
            service.onStartCommand(Intent().setAction(NativeHttpService.ACTION_START), 0, 1)
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            service.onStartCommand(Intent().setAction(NativeHttpService.ACTION_STOP), 0, 2)
            releasePublish.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))

            assertNull(serviceRuntimeForTesting(service))
            assertNull(binder.service().findExport("stale-token"))
            assertNull(binder.service().consumeExport("stale-token"))
            assertNull(published.get())
            assertEquals(
                listOf(
                    "vectors.create", "server.create", "stale.server.start",
                    "stale.server.stop", "stale.vectors.close"
                ),
                events
            )
        } finally {
            releasePublish.countDown()
            resetServiceTestingHooks()
            controller.destroy()
        }
    }

    private class RecordingFactory(
        private val vectors: RecordingVectors,
        private val server: RecordingServer
    ) : NativeAdapterRuntimeFactory {
        override fun createVectorSubsystem(): NativeVectorSubsystem {
            vectors.events += "vectors.create"
            return vectors
        }

        override fun createServer(vectorRoutes: VectorRoutes): NativeAdapterServer {
            assertSame(vectors.controller, vectorRoutes)
            server.events += "server.create"
            return server
        }
    }

    private fun setServiceTestingHook(name: String, value: Any?) {
        val hooksClass = Class.forName("com.stapk.mobile.nativeadapter.NativeHttpServiceTestingHooks")
        val instance = hooksClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        hooksClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun resetServiceTestingHooks() {
        listOf("runtimeFactory", "afterRuntimeStartBeforePublish", "runtimePublished")
            .forEach { setServiceTestingHook(it, null) }
    }

    private fun serviceRuntimeForTesting(service: NativeHttpService): NativeAdapterRuntime? {
        val method = NativeHttpService::class.java.getDeclaredMethod("runtimeForTesting")
        method.isAccessible = true
        return method.invoke(service) as? NativeAdapterRuntime
    }

    private class SequenceFactory(
        private val vectors: List<RecordingVectors>,
        private val servers: List<RecordingServer>
    ) : NativeAdapterRuntimeFactory {
        private var index = 0

        override fun createVectorSubsystem(): NativeVectorSubsystem = vectors[index].also {
            it.events += "${it.name}.vectors.create"
        }

        override fun createServer(vectorRoutes: VectorRoutes): NativeAdapterServer {
            val vector = vectors[index]
            val server = servers[index++]
            assertSame(vector.controller, vectorRoutes)
            server.events += "${server.name}.server.create"
            return server
        }
    }

    private class RecordingVectors(
        val events: MutableList<String>,
        val name: String = "vectors",
        private val closed: CountDownLatch? = null
    ) : NativeVectorSubsystem {
        override val controller: VectorRoutes = object : VectorRoutes {
            override fun list(body: String): HttpResponse = HttpResponse.json(200, "{}")
            override fun insert(body: String): HttpResponse = HttpResponse.json(200, "{}")
            override fun delete(body: String): HttpResponse = HttpResponse.json(200, "{}")
            override fun query(body: String): HttpResponse = HttpResponse.json(200, "{}")
            override fun queryMulti(body: String): HttpResponse = HttpResponse.json(200, "{}")
            override fun purge(body: String): HttpResponse = HttpResponse.json(200, "{}")
            override fun purgeAll(): HttpResponse = HttpResponse.json(200, "{}")
            override fun getConfig(): HttpResponse = HttpResponse.json(200, "{}")
            override fun saveConfig(body: String): HttpResponse = HttpResponse.json(200, "{}")
            override fun testConfig(): HttpResponse = HttpResponse.json(200, "{}")
        }

        override fun close() {
            events += "$name.vectors.close"
            closed?.countDown()
        }
    }

    private open class RecordingServer(
        val events: MutableList<String>,
        private val failOnStart: Boolean = false,
        val name: String = "server"
    ) : NativeAdapterServer {
        override fun serverPort(): Int = 19876

        override fun start() {
            events += "$name.server.start"
            if (failOnStart) throw IllegalStateException("start failed")
        }

        override fun stop() {
            events += "$name.server.stop"
        }

        override fun setExportBridgeNonce(nonce: String) = Unit
        override fun findExport(token: String): ExportTicket? = null
        override fun consumeExport(token: String): ExportTicket? = null
        override fun releaseExport(token: String) = Unit
    }

    private class BlockingServer(
        events: MutableList<String>,
        private val startEntered: CountDownLatch,
        private val releaseStart: CountDownLatch,
        name: String = "concurrent"
    ) : RecordingServer(events, name = name) {
        override fun start() {
            super.start()
            startEntered.countDown()
            check(releaseStart.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release server start" }
        }
    }
}
