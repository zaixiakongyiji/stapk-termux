package com.stapk.mobile.nativeadapter

enum class NativeAdapterStatus {
    STARTING,
    RUNNING,
    FAILED,
    STOPPED,
    MIGRATING,
    MIGRATION_FAILED
}

data class NativeAdapterState(
    val status: NativeAdapterStatus,
    val port: Int? = null,
    val message: String = ""
)
