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

class TavernWebViewClient(private val context: Context) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString()
        if (request?.isForMainFrame != true || !shouldOpenExternally(url)) {
            return false
        }

        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        }.getOrDefault(false)
    }
}
