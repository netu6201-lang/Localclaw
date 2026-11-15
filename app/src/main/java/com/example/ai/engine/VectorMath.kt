package com.example.ai.engine

import com.example.data.db.FileIndexDao
import com.example.data.db.FileIndexEntity
import com.example.data.db.FtsQuerySanitizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object VectorMath {
    fun floatArrayToBytes(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) {
            buffer.putFloat(v)
        }
        return buffer.array()
    }

    fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) {
            out[i] = buffer.getFloat()
        }
        return out
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            val av = a[i]
            val bv = b[i]
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-6f) (dot / denom) else 0f
    }
}

data class HybridSearchHit(
    val file: FileIndexEntity,
    val score: Float,
    val ftsMatched: Boolean,
    val semanticSimilarity: Float
)

/**
 * Combines SQLite FTS4 keyword/prefix matching with EmbeddingGemma vector cosine similarity.
 */
class HybridSearchEngine(
    private val fileIndexDao: FileIndexDao,
    private val embeddingEngineManager: LocalEmbeddingEngineManager
) {
    suspend fun search(
        rawQuery: String,
        category: String? = null,
        minModifiedMs: Long? = null,
        maxModifiedMs: Long? = null,
        limit: Int = 40
    ): List<HybridSearchHit> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()

        val ftsExpr = FtsQuerySanitizer.toSafeFtsQuery(query)
        val ftsHits = if (ftsExpr.isNotEmpty()) {
            fileIndexDao.searchFts(
                ftsQuery = ftsExpr,
                category = category,
                minModifiedMs = minModifiedMs,
                maxModifiedMs = maxModifiedMs,
                limit = limit
            )
        } else {
            emptyList()
        }

        val fallbackHits = if (ftsHits.isEmpty()) {
            fileIndexDao.searchFallbackLike(
                likeTerm = query,
                category = category,
                minModifiedMs = minModifiedMs,
                maxModifiedMs = maxModifiedMs,
                limit = limit
            )
        } else {
            emptyList()
        }

        val keywordResults = if (ftsHits.isNotEmpty()) ftsHits else fallbackHits
        val keywordRankMap = HashMap<Long, Int>(keywordResults.size)
        keywordResults.forEachIndexed { idx, file -> keywordRankMap[file.id] = idx }

        val mergedMap = LinkedHashMap<Long, HybridSearchHit>()
        keywordResults.forEachIndexed { idx, file ->
            val rrf = 1f / (12f + idx)
            mergedMap[file.id] = HybridSearchHit(
                file = file,
                score = rrf,
                ftsMatched = true,
                semanticSimilarity = 0f
            )
        }

        // If EmbeddingGemma is loaded, fuse cosine similarity scores across embedded files
        if (embeddingEngineManager.telemetry.value.status == EmbeddingRuntimeStatus.READY) {
            val queryVector = embeddingEngineManager.embedQuery(query)
            if (queryVector != null && queryVector.isNotEmpty()) {
                val embeddedCandidates = fileIndexDao.getFilesWithEmbeddings(
                    category = category,
                    minModifiedMs = minModifiedMs,
                    maxModifiedMs = maxModifiedMs,
                    limit = 500
                )
                for (candidate in embeddedCandidates) {
                    val rawVec = candidate.embeddingVector ?: continue
                    val docVec = VectorMath.bytesToFloatArray(rawVec)
                    val sim = VectorMath.cosineSimilarity(queryVector, docVec)
                    if (sim >= 0.24f || mergedMap.containsKey(candidate.id)) {
                        val existing = mergedMap[candidate.id]
                        val vectorBoost = sim.coerceAtLeast(0f) * 0.12f
                        if (existing != null) {
                            mergedMap[candidate.id] = existing.copy(
                                score = existing.score + vectorBoost,
                                semanticSimilarity = sim
                            )
                        } else {
                            mergedMap[candidate.id] = HybridSearchHit(
                                file = candidate,
                                score = vectorBoost,
                                ftsMatched = false,
                                semanticSimilarity = sim
                            )
                        }
                    }
                }
            }
        }

        return mergedMap.values
            .sortedByDescending { it.score }
            .take(limit)
    }
}
