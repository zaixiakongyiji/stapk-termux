package com.stapk.mobile

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stapk.mobile.nativeadapter.NativeAdapterState
import com.stapk.mobile.nativeadapter.NativeAdapterStatus
import com.stapk.mobile.nativeadapter.NativeHttpService
import com.stapk.mobile.nativeadapter.ExportTicket
import java.util.concurrent.Executors

internal enum class MainScreen {
    LOADING,
    WEB,
    ERROR
}

internal data class MainUiModel(
    val screen: MainScreen,
    val url: String? = null,
    val message: String? = null
)

internal fun expandedFileChooserMimeTypes(acceptTypes: Array<String>): Array<String>? {
    val acceptsJsonl = acceptTypes
        .flatMap { it.split(',') }
        .map { it.trim().lowercase() }
        .any { it == ".jsonl" || it == "application/x-ndjson" }
    return if (acceptsJsonl) {
        arrayOf("application/json", "application/x-ndjson", "application/octet-stream")
    } else {
        null
    }
}

internal fun toMainUiModel(state: NativeAdapterState): MainUiModel = when (state.status) {
    NativeAdapterStatus.RUNNING -> state.port?.let { port ->
        MainUiModel(MainScreen.WEB, url = "http://127.0.0.1:$port/")
    } ?: MainUiModel(MainScreen.ERROR)

    NativeAdapterStatus.FAILED,
    NativeAdapterStatus.MIGRATION_FAILED -> MainUiModel(
        MainScreen.ERROR,
        message = state.message.takeIf { it.isNotBlank() }
    )

    else -> MainUiModel(MainScreen.LOADING)
}

internal fun matchesExportTicket(request: PendingSafExport, ticket: ExportTicket): Boolean =
    request.token == ticket.token &&
        request.fileName == ticket.fileName &&
        request.mimeType == ticket.mimeType

private data class PendingSafWrite(
    val request: PendingSafExport,
    val destination: Uri
)

class MainActivity : Activity() {
    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val SAF_EXPORT_REQUEST_CODE = 1002
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1004
        private const val STATE_POLL_INTERVAL_MS = 250L
        private const val STATE_EXPORT_TOKEN = "stapk.export.token"
        private const val STATE_EXPORT_FILE_NAME = "stapk.export.fileName"
        private const val STATE_EXPORT_MIME_TYPE = "stapk.export.mimeType"
        private const val STATE_EXPORT_DESTINATION = "stapk.export.destination"
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private var nativeService: NativeHttpService? = null
    private var isBound = false
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val bridgeSessionNonce = createBridgeSessionNonce()
    private val safExportCoordinator = SafExportCoordinator()
    private val exportExecutor = Executors.newSingleThreadExecutor()
    private var pendingSafExport: PendingSafExport? = null
    private val pendingSafWrites = PendingSafWriteQueue<PendingSafWrite>()
    private var trustedLoopbackPort: Int? = null

    private val statePoll = Runnable { renderServiceState() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? NativeHttpService.LocalBinder
            nativeService = localBinder?.service()
            if (nativeService == null) {
                showError(getString(R.string.service_bind_failed))
                return
            }
            nativeService?.setExportBridgeNonce(bridgeSessionNonce)
            resumePendingSafWrite()
            renderServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nativeService = null
            trustedLoopbackPort = null
            showError(getString(R.string.service_disconnected))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadingView = findViewById(R.id.loadingView)
        webView = findViewById(R.id.webView)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        val restoredExport = savedInstanceState?.getString(STATE_EXPORT_TOKEN)?.let { token ->
            val fileName = savedInstanceState.getString(STATE_EXPORT_FILE_NAME) ?: return@let null
            val mimeType = savedInstanceState.getString(STATE_EXPORT_MIME_TYPE) ?: return@let null
            PendingSafExport(token, fileName, mimeType)
        }
        val restoredDestination = savedInstanceState?.getString(STATE_EXPORT_DESTINATION)
        if (restoredExport != null && restoredDestination != null) {
            pendingSafWrites.enqueue(PendingSafWrite(restoredExport, Uri.parse(restoredDestination)))
        } else {
            pendingSafExport = restoredExport
        }

        requestNotificationPermissionIfNeeded()
        configureWebView()
        findViewById<View>(R.id.retryButton).setOnClickListener { startNativeAdapter() }
        startNativeAdapter()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.addJavascriptInterface(
            StapkFileBridge(bridgeSessionNonce) { token, fileName, mimeType ->
                runOnUiThread { beginSafExport(PendingSafExport(token, fileName, mimeType)) }
            },
            "StapkFiles"
        )
        webView.webViewClient = TavernWebViewClient(
            context = this,
            trustedLoopbackPort = { trustedLoopbackPort },
            onLoopbackPageFinished = { loadedView ->
                loadedView.evaluateJavascript(bridgeNonceScript(bridgeSessionNonce), null)
            }
        )
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                if (intent == null) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                expandedFileChooserMimeTypes(fileChooserParams.acceptTypes)?.let { mimeTypes ->
                    intent.type = "*/*"
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }
                return runCatching {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                    true
                }.getOrElse {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    false
                }
            }
        }
    }

    private fun beginSafExport(request: PendingSafExport) {
        if (pendingSafExport != null) return
        val ticket = nativeService?.findExport(request.token) ?: return
        if (!matchesExportTicket(request, ticket)) return
        pendingSafExport = request
        runCatching {
            startActivityForResult(
                safExportCoordinator.createDocumentIntent(ticket.fileName, ticket.mimeType),
                SAF_EXPORT_REQUEST_CODE
            )
        }.onFailure {
            pendingSafExport = null
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startNativeAdapter() {
        showLoading()
        val intent = Intent(this, NativeHttpService::class.java)
            .setAction(NativeHttpService.ACTION_START)

        val startError = runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.exceptionOrNull()
        if (startError != null) {
            showError(getString(R.string.start_failed_detail, startError.message.orEmpty()))
            return
        }

        if (!isBound) {
            isBound = bindService(intent, connection, BIND_AUTO_CREATE)
            if (!isBound) {
                showError(getString(R.string.service_bind_failed))
            }
        } else {
            webView.removeCallbacks(statePoll)
            webView.postDelayed(statePoll, STATE_POLL_INTERVAL_MS)
        }
    }

    private fun renderServiceState() {
        webView.removeCallbacks(statePoll)
        val adapterState = nativeService?.currentState()
        val model = adapterState?.let(::toMainUiModel)
            ?: MainUiModel(MainScreen.LOADING)

        when (model.screen) {
            MainScreen.LOADING -> {
                showLoading()
                webView.postDelayed(statePoll, STATE_POLL_INTERVAL_MS)
            }

            MainScreen.WEB -> {
                trustedLoopbackPort = adapterState?.port
                loadingView.visibility = View.GONE
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                if (webView.url != model.url) {
                    webView.loadUrl(requireNotNull(model.url))
                }
            }

            MainScreen.ERROR -> {
                trustedLoopbackPort = null
                showError(model.message ?: getString(R.string.start_failed))
            }
        }
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        webView.visibility = View.GONE
        errorView.visibility = View.GONE
    }

    private fun showError(message: String) {
        webView.removeCallbacks(statePoll)
        loadingView.visibility = View.GONE
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorText.text = message
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val uris = if (resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
            fileUploadCallback?.onReceiveValue(uris)
            fileUploadCallback = null
            return
        }
        if (requestCode == SAF_EXPORT_REQUEST_CODE) {
            val pending = pendingSafExport
            pendingSafExport = null
            val destination = data?.data
            if (resultCode == RESULT_OK && pending != null && destination != null) {
                pendingSafWrites.enqueue(PendingSafWrite(pending, destination))
                resumePendingSafWrite()
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun resumePendingSafWrite() {
        val service = nativeService ?: return
        val pending = pendingSafWrites.takeIfReady(serviceAvailable = true) ?: return
        writeSafExport(service, pending.request, pending.destination)
    }

    private fun writeSafExport(
        service: NativeHttpService,
        request: PendingSafExport,
        destination: Uri
    ) {
        val ticket = service.consumeExport(request.token)
        if (ticket == null || !matchesExportTicket(request, ticket)) {
            ticket?.let { service.releaseExport(it.token) }
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        exportExecutor.execute {
            val succeeded = runCatching {
                val output = checkNotNull(contentResolver.openOutputStream(destination, "w"))
                output.use { safExportCoordinator.copy(ticket, it) }
            }.isSuccess
            service.releaseExport(ticket.token)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (succeeded) R.string.export_saved else R.string.export_failed,
                    Toast.LENGTH_SHORT
                ).show()
                webView.evaluateJavascript(exportResultScript(ticket.token, succeeded), null)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val pendingWrite = pendingSafWrites.peek()
        val pending = pendingWrite?.request ?: pendingSafExport
        pending?.let {
            outState.putString(STATE_EXPORT_TOKEN, it.token)
            outState.putString(STATE_EXPORT_FILE_NAME, it.fileName)
            outState.putString(STATE_EXPORT_MIME_TYPE, it.mimeType)
        }
        pendingWrite?.let { outState.putString(STATE_EXPORT_DESTINATION, it.destination.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        webView.removeCallbacks(statePoll)
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        nativeService = null
        exportExecutor.shutdown()
        super.onDestroy()
    }
}

internal fun exportResultScript(token: String, succeeded: Boolean): String {
    require(com.stapk.mobile.nativeadapter.ExportMetadata.isToken(token)) { "Invalid export token" }
    return "window.dispatchEvent(new CustomEvent('stapk-export-result', {" +
        "detail: {token: '$token', success: $succeeded}}));"
}
