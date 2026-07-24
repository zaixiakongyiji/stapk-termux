package com.stapk.mobile.nativeadapter

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

internal fun moveDirectoryAtomically(source: File, target: File) {
    target.parentFile?.mkdirs()
    try {
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath())
    }
}
