package com.example.ai.engine

import com.example.data.db.FileIndexDao
import com.example.data.db.FileIndexEntity
import com.example.data.db.FtsQuerySanitizer
import com.example.search.HindiTransliterator
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
 * Combines SQLite FTS4 keyword/prefix matching, Hindi <-> English cross-script phonetic matching,
 * and EmbeddingGemma vector cosine similarity.
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

        // 1. Cross-script FTS4 expansion (Hindi/Devanagari + transliterated Roman + English concepts)
        val expandedFtsExpr = FtsQuerySanitizer.toCrossScriptFtsQuery(query)
        val ftsHits = if (expandedFtsExpr.isNotEmpty()) {
            runCatching {
                fileIndexDao.searchFts(
                    ftsQuery = expandedFtsExpr,
                    category = category,
                    minModifiedMs = minModifiedMs,
                    maxModifiedMs = maxModifiedMs,
                    limit = limit * 2
                )
            }.getOrDefault(emptyList())
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

        // 2. Phonetic cross-script scan (catches Devanagari filenames/OCR like "भूरी" from "bhuri"
        //    and Roman filenames like "bhuri_bill.jpg" from "भुरी" or "भूरी", including underscores)
        val phoneticHits = fileIndexDao.getActiveCandidatesForPhoneticSearch(
            category = category,
            minModifiedMs = minModifiedMs,
            maxModifiedMs = maxModifiedMs,
            limit = 600
        ).mapNotNull { candidate ->
            val phoneticScore = HindiTransliterator.scoreCrossScriptMatch(
                rawQuery = query,
                fileName = candidate.fileName,
                extractedText = candidate.extractedText,
                filePath = candidate.filePath
            )
            if (phoneticScore > 0f) candidate to phoneticScore else null
        }.sortedByDescending { it.second }.take(limit)

        val mergedMap = LinkedHashMap<Long, HybridSearchHit>()

        val keywordResults = (ftsHits + fallbackHits).distinctBy { it.id }
        keywordResults.forEachIndexed { idx, file ->
            val rrf = 1f / (12f + idx)
            val bonus = HindiTransliterator.scoreCrossScriptMatch(
                rawQuery = query,
                fileName = file.fileName,
                extractedText = file.extractedText,
                filePath = file.filePath
            ) * 0.02f
            mergedMap[file.id] = HybridSearchHit(
                file = file,
                score = rrf + bonus,
                ftsMatched = true,
                semanticSimilarity = 0f
            )
        }

        for ((file, phoneticScore) in phoneticHits) {
            val existing = mergedMap[file.id]
            val boost = (phoneticScore * 0.03f).coerceAtMost(0.15f)
            if (existing != null) {
                mergedMap[file.id] = existing.copy(score = existing.score + boost)
            } else {
                mergedMap[file.id] = HybridSearchHit(
                    file = file,
                    score = 0.04f + boost,
                    ftsMatched = true,
                    semanticSimilarity = 0f
                )
            }
        }

        // 3. If EmbeddingGemma is loaded, fuse cosine similarity scores across embedded files
        if (embeddingEngineManager.telemetry.value.status == EmbeddingRuntimeStatus.READY) {
            val semanticQuery = buildString {
                append(query)
                val extras = HindiTransliterator.expandQueryTokens(query)
                    .filter { !HindiTransliterator.containsDevanagari(it) }
                    .take(6)
                if (extras.isNotEmpty() && HindiTransliterator.containsDevanagari(query)) {
                    append(" (")
                    append(extras.joinToString(" "))
                    append(")")
                }
            }
            val queryVector = embeddingEngineManager.embedQuery(semanticQuery)
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
