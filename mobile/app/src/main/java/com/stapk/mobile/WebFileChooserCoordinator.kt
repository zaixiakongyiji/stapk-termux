package com.stapk.mobile

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable

internal fun prepareWebFileChooserIntent(
    baseIntent: Intent,
    acceptTypes: Array<String>,
    allowMultiple: Boolean
): Intent = baseIntent.apply {
    action = Intent.ACTION_OPEN_DOCUMENT
    addCategory(Intent.CATEGORY_OPENABLE)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
    expandedFileChooserMimeTypes(acceptTypes)?.let { mimeTypes ->
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
    }
}

internal fun parseWebFileChooserResult(
    resultCode: Int,
    data: Intent?,
    standardResult: Array<Uri>?
): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK) return null
    val uris = LinkedHashSet<Uri>()
    standardResult.orEmpty().forEach(uris::add)
    data?.data?.let(uris::add)
    data?.clipData?.uris().orEmpty().forEach(uris::add)
    data?.streamUris().orEmpty().forEach(uris::add)
    return uris.filter { it.scheme == "content" || it.scheme == "file" }
        .toTypedArray()
        .takeIf { it.isNotEmpty() }
}

internal fun filterReadableFileChooserUris(
    uris: Array<Uri>?,
    canRead: (Uri) -> Boolean
): Array<Uri>? = uris.orEmpty().filter(canRead).toTypedArray().takeIf { it.isNotEmpty() }

private fun ClipData.uris(): List<Uri> = buildList {
    repeat(itemCount) { index -> getItemAt(index).uri?.let(::add) }
}

private fun Intent.streamUris(): List<Uri> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        runCatching { getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) }
            .getOrNull()?.filterIsInstance<Uri>()?.let(::addAll)
        runCatching { getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }
            .getOrNull()?.let(::add)
    } else {
        @Suppress("DEPRECATION")
        runCatching { getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM) }
            .getOrNull()?.filterIsInstance<Uri>()?.let(::addAll)
        @Suppress("DEPRECATION")
        runCatching { getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) }
            .getOrNull()?.let { value ->
                if (value is Uri) add(value)
            }
    }
}
