package com.stapk.mobile.nativeadapter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.AssetManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.stapk.mobile.MainActivity
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal fun startNativeAdapterSafely(
    startForeground: () -> Unit,
    startServer: () -> NativeAdapterState,
    cleanup: () -> Unit
): NativeAdapterState = try {
    startForeground()
    startServer()
} catch (_: Exception) {
    runCatching { cleanup() }
    NativeAdapterState(
        NativeAdapterStatus.FAILED,
        message = "Unable to start local server"
    )
}

internal fun startNativeAdapterAsync(
    execute: ((() -> Unit) -> Unit),
    startServer: () -> NativeAdapterState,
    cleanup: () -> Unit,
    publish: (NativeAdapterState) -> Unit
) {
    publish(NativeAdapterState(NativeAdapterStatus.STARTING))
    execute {
        publish(
            startNativeAdapterSafely(
                startForeground = {},
                startServer = startServer,
                cleanup = cleanup
            )
        )
    }
}

internal fun ensureWebDirectory(webDir: File) {
    if (webDir.exists()) {
        check(webDir.isDirectory) { "Unable to create web directory" }
        return
    }
    check(webDir.mkdirs()) { "Unable to create web directory" }
}

internal interface WebAssetSource {
    fun list(path: String): List<String>
    fun open(path: String): InputStream
}

internal class AndroidWebAssetSource(private val assets: AssetManager) : WebAssetSource {
    override fun list(path: String): List<String> = assets.list(path).orEmpty().sorted()

    override fun open(path: String): InputStream = assets.open(path)
}

internal fun installWebAssetsIfNeeded(
    paths: NativeAdapterPaths,
    assetSource: WebAssetSource
): Boolean {
    recoverInterruptedWebInstall(paths)
    val bundledManifest = assetSource.open("stapk-web-manifest.json").use { it.readBytes() }
    val installedIndex = File(paths.webDir, "index.html")
    if (
        installedIndex.isFile &&
        paths.webManifestFile.isFile &&
        paths.webManifestFile.readBytes().contentEquals(bundledManifest)
    ) {
        return false
    }

    val webParent = requireNotNull(paths.webDir.parentFile)
    val manifestParent = requireNotNull(paths.webManifestFile.parentFile)
    val stagingDir = File(webParent, "${paths.webDir.name}.installing")
    val previousDir = File(webParent, "${paths.webDir.name}.previous")
    val stagedManifest = File(manifestParent, "${paths.webManifestFile.name}.installing")
    val previousManifest = File(manifestParent, "${paths.webManifestFile.name}.previous")
    check(!stagingDir.exists() || stagingDir.deleteRecursively()) {
        "Unable to clear staged web assets"
    }
    copyAssetDirectory(assetSource, "sillytavern-web", stagingDir)
    check(File(stagingDir, "index.html").isFile) { "Bundled web assets do not contain index.html" }

    ensureWebDirectory(manifestParent)
    stagedManifest.writeBytes(bundledManifest)
    check(!previousDir.exists() || previousDir.deleteRecursively()) {
        "Unable to clear previous web assets"
    }
    check(!previousManifest.exists() || previousManifest.delete()) {
        "Unable to clear previous web manifest"
    }

    if (paths.webDir.exists()) {
        check(paths.webDir.renameTo(previousDir)) { "Unable to preserve installed web assets" }
    }
    if (paths.webManifestFile.exists()) {
        check(paths.webManifestFile.renameTo(previousManifest)) {
            rollbackWebDirectory(paths.webDir, previousDir)
            "Unable to preserve installed web manifest"
        }
    }

    try {
        check(stagingDir.renameTo(paths.webDir)) { "Unable to activate installed web assets" }
        check(stagedManifest.renameTo(paths.webManifestFile)) { "Unable to activate installed web manifest" }
    } catch (exception: Exception) {
        rollbackWebDirectory(paths.webDir, previousDir)
        rollbackWebManifest(paths.webManifestFile, previousManifest)
        throw exception
    }

    check(!previousDir.exists() || previousDir.deleteRecursively()) {
        "Unable to clear previous web assets"
    }
    check(!previousManifest.exists() || previousManifest.delete()) {
        "Unable to clear previous web manifest"
    }
    return true
}

private fun recoverInterruptedWebInstall(paths: NativeAdapterPaths) {
    val webParent = requireNotNull(paths.webDir.parentFile)
    val manifestParent = requireNotNull(paths.webManifestFile.parentFile)
    val stagingDir = File(webParent, "${paths.webDir.name}.installing")
    val previousDir = File(webParent, "${paths.webDir.name}.previous")
    val stagedManifest = File(manifestParent, "${paths.webManifestFile.name}.installing")
    val previousManifest = File(manifestParent, "${paths.webManifestFile.name}.previous")

    if (previousDir.exists()) {
        rollbackWebDirectory(paths.webDir, previousDir)
        if (previousManifest.exists()) {
            rollbackWebManifest(paths.webManifestFile, previousManifest)
        }
    } else if (previousManifest.exists()) {
        rollbackWebManifest(paths.webManifestFile, previousManifest)
    }

    check(!stagingDir.exists() || stagingDir.deleteRecursively()) {
        "Unable to clear interrupted web assets"
    }
    check(!stagedManifest.exists() || stagedManifest.delete()) {
        "Unable to clear interrupted web manifest"
    }
}

private fun rollbackWebDirectory(webDir: File, previousDir: File) {
    check(!webDir.exists() || webDir.deleteRecursively()) { "Unable to roll back web assets" }
    check(!previousDir.exists() || previousDir.renameTo(webDir)) { "Unable to restore web assets" }
}

private fun rollbackWebManifest(webManifest: File, previousManifest: File) {
    check(!webManifest.exists() || webManifest.delete()) { "Unable to roll back web manifest" }
    check(!previousManifest.exists() || previousManifest.renameTo(webManifest)) {
        "Unable to restore web manifest"
    }
}

private fun copyAssetDirectory(source: WebAssetSource, assetPath: String, target: File) {
    val children = source.list(assetPath)
    if (children.isEmpty()) {
        target.parentFile?.let(::ensureWebDirectory)
        source.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return
    }

    ensureWebDirectory(target)
    for (child in children) {
        copyAssetDirectory(source, "$assetPath/$child", File(target, child))
    }
}

class NativeHttpService : Service() {
    companion object {
        const val ACTION_START = "com.stapk.mobile.nativeadapter.START"
        const val ACTION_STOP = "com.stapk.mobile.nativeadapter.STOP"

        private const val CHANNEL_ID = "native_http_server"
        private const val NOTIFICATION_ID = 2
    }

    private val binder = LocalBinder()
    private var server: NativeHttpServer? = null
    private val startupExecutor = Executors.newSingleThreadExecutor()
    private val startGeneration = AtomicInteger()

    @Volatile
    private var bridgeNonce: String? = null

    @Volatile
    private var state = NativeAdapterState(NativeAdapterStatus.STOPPED)

    inner class LocalBinder : Binder() {
        fun service(): NativeHttpService = this@NativeHttpService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            startGeneration.incrementAndGet()
            state = NativeAdapterState(NativeAdapterStatus.STOPPED)
            startupExecutor.execute { stopServer() }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (state.status == NativeAdapterStatus.STARTING || state.status == NativeAdapterStatus.RUNNING) {
            return START_STICKY
        }

        val foregroundError = runCatching {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, createNotification())
        }.exceptionOrNull()
        if (foregroundError != null) {
            state = NativeAdapterState(NativeAdapterStatus.FAILED, message = "Unable to start local server")
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val generation = startGeneration.incrementAndGet()
        startNativeAdapterAsync(
            execute = { work -> startupExecutor.execute(work) },
            startServer = { startServer() },
            cleanup = { stopServer() },
            publish = { nextState ->
                if (startGeneration.get() == generation) {
                    state = nextState
                    if (nextState.status == NativeAdapterStatus.FAILED) {
                        stopSelfResult(startId)
                    }
                } else if (nextState.status == NativeAdapterStatus.RUNNING) {
                    stopServer()
                }
            }
        )
        return START_STICKY
    }

    fun currentState(): NativeAdapterState = state

    fun findExport(token: String): ExportTicket? = server?.findExport(token)

    fun consumeExport(token: String): ExportTicket? = server?.consumeExport(token)

    fun releaseExport(token: String) = server?.releaseExport(token)

    fun setExportBridgeNonce(nonce: String) {
        require(ExportMetadata.isToken(nonce)) { "Invalid bridge nonce" }
        bridgeNonce = nonce
        server?.setExportBridgeNonce(nonce)
    }

    private fun startServer(): NativeAdapterState {
        if (server != null) return state

        val paths = NativeAdapterPaths(filesDir)
        installWebAssetsIfNeeded(paths, AndroidWebAssetSource(assets))
        ensureWebDirectory(paths.webDir)

        val startedServer = NativeHttpServer(paths)
        return try {
            bridgeNonce?.let(startedServer::setExportBridgeNonce)
            startedServer.start()
            server = startedServer
            NativeAdapterState(NativeAdapterStatus.RUNNING, port = startedServer.listeningPort)
        } catch (exception: Exception) {
            runCatching { startedServer.stop() }
            server = null
            throw exception
        }
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        startGeneration.incrementAndGet()
        state = NativeAdapterState(NativeAdapterStatus.STOPPED)
        runCatching { startupExecutor.execute { stopServer() } }
            .onFailure { stopServer() }
        startupExecutor.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "stAPK 本地 HTTP 服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("stAPK 本地服务")
            .setContentText("本地 Web 服务正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .build()
    }
}
