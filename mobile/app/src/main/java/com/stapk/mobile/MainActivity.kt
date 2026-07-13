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
import androidx.core.content.ContextCompat
import com.stapk.mobile.nativeadapter.NativeAdapterState
import com.stapk.mobile.nativeadapter.NativeAdapterStatus
import com.stapk.mobile.nativeadapter.NativeHttpService

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

class MainActivity : Activity() {
    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1004
        private const val STATE_POLL_INTERVAL_MS = 250L
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private var nativeService: NativeHttpService? = null
    private var isBound = false
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val statePoll = Runnable { renderServiceState() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? NativeHttpService.LocalBinder
            nativeService = localBinder?.service()
            if (nativeService == null) {
                showError(getString(R.string.service_bind_failed))
                return
            }
            renderServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nativeService = null
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
        webView.webViewClient = TavernWebViewClient(this)
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
        val model = nativeService?.currentState()?.let(::toMainUiModel)
            ?: MainUiModel(MainScreen.LOADING)

        when (model.screen) {
            MainScreen.LOADING -> {
                showLoading()
                webView.postDelayed(statePoll, STATE_POLL_INTERVAL_MS)
            }

            MainScreen.WEB -> {
                loadingView.visibility = View.GONE
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                if (webView.url != model.url) {
                    webView.loadUrl(requireNotNull(model.url))
                }
            }

            MainScreen.ERROR -> showError(model.message ?: getString(R.string.start_failed))
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
        super.onActivityResult(requestCode, resultCode, data)
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
        super.onDestroy()
    }
}
