package com.stapk.mobile.nativeadapter.vector

import com.stapk.mobile.nativeadapter.HttpResponse

interface VectorRoutes {
    fun list(body: String): HttpResponse
    fun insert(body: String): HttpResponse
    fun delete(body: String): HttpResponse
    fun query(body: String): HttpResponse
    fun queryMulti(body: String): HttpResponse
    fun purge(body: String): HttpResponse
    fun purgeAll(): HttpResponse
    fun getConfig(): HttpResponse
    fun saveConfig(body: String): HttpResponse
    fun testConfig(): HttpResponse
}

interface EmbeddingGateway {
    fun embed(snapshot: EmbeddingProviderSnapshot, inputs: List<String>): EmbeddingBatch
}

interface VectorStore {
    fun listHashes(namespace: VectorNamespace): List<Long>
    fun upsertBatch(namespace: VectorNamespace, items: List<VectorItemInput>, vectors: List<EncodedVector>)
    fun deleteHashes(namespace: VectorNamespace, hashes: List<Long>)
    fun purgeCollection(collectionKey: String)
    fun purgeAll()
    fun query(namespace: VectorNamespace, queryVector: FloatArray, topK: Int, threshold: Float): VectorQueryResult
    fun queryMulti(
        namespaces: List<VectorNamespace>,
        queryVector: FloatArray,
        topK: Int,
        threshold: Float
    ): Map<String, VectorQueryResult>
}
