package com.stapk.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI

internal fun shouldOpenExternally(url: String?): Boolean {
    val uri = runCatching { URI(url.orEmpty()) }.getOrNull() ?: return false
    return uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)
}

internal fun isAllowedLoopbackUrl(url: String?, expectedPort: Int? = null): Boolean {
    if (expectedPort == null) return false
    val uri = runCatching { URI(url.orEmpty()) }.getOrNull() ?: return false
    return uri.isAbsolute &&
        uri.scheme.equals("http", ignoreCase = true) &&
        uri.host == "127.0.0.1" &&
        uri.port == expectedPort
}

internal fun shouldBlockMainFrameNavigation(url: String?, expectedPort: Int? = null): Boolean {
    val uri = runCatching { URI(url.orEmpty()) }.getOrNull() ?: return false
    return uri.isAbsolute && !isAllowedLoopbackUrl(url, expectedPort)
}

class TavernWebViewClient(
    private val context: Context,
    private val trustedLoopbackPort: () -> Int? = { null },
    private val onLoopbackPageFinished: (WebView) -> Unit = {}
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString()
        if (
            request?.isForMainFrame != true ||
            !shouldBlockMainFrameNavigation(url, trustedLoopbackPort())
        ) {
            return false
        }

        if (shouldOpenExternally(url)) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        return true
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (view != null && isAllowedLoopbackUrl(url, trustedLoopbackPort())) {
            onLoopbackPageFinished(view)
        }
    }
}
