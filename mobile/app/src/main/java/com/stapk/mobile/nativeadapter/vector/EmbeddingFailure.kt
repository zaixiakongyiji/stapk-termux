package com.stapk.mobile.nativeadapter.vector

class EmbeddingFailure(
    val httpStatus: Int,
    val errorCode: String
) : RuntimeException(errorCode)
