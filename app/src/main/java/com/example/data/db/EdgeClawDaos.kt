package com.example.data.db

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.search.HindiTransliterator
import kotlinx.coroutines.flow.Flow

@Immutable
data class FileSearchListItem(
    @ColumnInfo(name = "id")
    val id: Long,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "parent_dir")
    val parentDir: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "file_category")
    val fileCategory: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "last_modified_ms")
    val lastModifiedMs: Long,
    @ColumnInfo(name = "snippet_preview")
    val snippetPreview: String,
    @ColumnInfo(name = "has_embedding")
    val hasEmbedding: Boolean
)

@Dao
interface FileIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: FileIndexEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFiles(files: List<FileIndexEntity>)

    @Query("SELECT * FROM file_index WHERE is_trashed = 0 ORDER BY last_modified_ms DESC LIMIT :limit")
    fun observeAllActiveFiles(limit: Int = 500): Flow<List<FileIndexEntity>>

    @Query("SELECT * FROM file_index WHERE is_trashed = 0 AND file_category = :category ORDER BY last_modified_ms DESC LIMIT :limit")
    fun observeByCategory(category: String, limit: Int = 300): Flow<List<FileIndexEntity>>

    @Query("SELECT * FROM file_index WHERE is_trashed = 1 ORDER BY indexed_at_ms DESC")
    fun observeTrashedFiles(): Flow<List<FileIndexEntity>>

    @Query("SELECT COUNT(*) FROM file_index WHERE is_trashed = 0")
    fun observeIndexedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM file_index WHERE is_trashed = 0 AND embedding_vector IS NOT NULL")
    fun observeEmbeddedCount(): Flow<Int>

    @Query("SELECT * FROM file_index WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FileIndexEntity?

    @Query("SELECT * FROM file_index WHERE file_path = :path LIMIT 1")
    suspend fun getByPath(path: String): FileIndexEntity?

    @Query(
        """
        SELECT f.id, f.file_path, f.file_name, f.parent_dir, f.mime_type, f.file_category,
               f.size_bytes, f.last_modified_ms,
               SUBSTR(f.extracted_text, 1, 220) AS snippet_preview,
               (f.embedding_vector IS NOT NULL) AS has_embedding
        FROM file_index AS f
        JOIN file_index_fts AS fts ON f.id = fts.rowid
        WHERE file_index_fts MATCH :ftsQuery
          AND f.is_trashed = 0
          AND (:category IS NULL OR f.file_category = :category)
          AND (:minModifiedMs IS NULL OR f.last_modified_ms >= :minModifiedMs)
        ORDER BY f.last_modified_ms DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchFtsPaged(
        ftsQuery: String,
        category: String? = null,
        minModifiedMs: Long? = null,
        limit: Int = 60,
        offset: Int = 0
    ): List<FileSearchListItem>

    @Query(
        """
        SELECT COUNT(*)
        FROM file_index AS f
        JOIN file_index_fts AS fts ON f.id = fts.rowid
        WHERE file_index_fts MATCH :ftsQuery
          AND f.is_trashed = 0
          AND (:category IS NULL OR f.file_category = :category)
          AND (:minModifiedMs IS NULL OR f.last_modified_ms >= :minModifiedMs)
        """
    )
    suspend fun countFtsMatches(
        ftsQuery: String,
        category: String? = null,
        minModifiedMs: Long? = null
    ): Int

    @Query(
        """
        SELECT id, file_path, file_name, parent_dir, mime_type, file_category,
               size_bytes, last_modified_ms,
               SUBSTR(extracted_text, 1, 220) AS snippet_preview,
               (embedding_vector IS NOT NULL) AS has_embedding
        FROM file_index
        WHERE is_trashed = 0
          AND (:category IS NULL OR file_category = :category)
          AND (:minModifiedMs IS NULL OR last_modified_ms >= :minModifiedMs)
          AND (:likeTerm = '' OR file_name LIKE '%' || :likeTerm || '%' OR extracted_text LIKE '%' || :likeTerm || '%' OR file_path LIKE '%' || :likeTerm || '%')
        ORDER BY last_modified_ms DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchFallbackLikePaged(
        likeTerm: String,
        category: String? = null,
        minModifiedMs: Long? = null,
        limit: Int = 60,
        offset: Int = 0
    ): List<FileSearchListItem>

    @Query(
        """
        SELECT COUNT(*)
        FROM file_index
        WHERE is_trashed = 0
          AND (:category IS NULL OR file_category = :category)
          AND (:minModifiedMs IS NULL OR last_modified_ms >= :minModifiedMs)
          AND (:likeTerm = '' OR file_name LIKE '%' || :likeTerm || '%' OR extracted_text LIKE '%' || :likeTerm || '%' OR file_path LIKE '%' || :likeTerm || '%')
        """
    )
    suspend fun countFallbackLikeMatches(
        likeTerm: String,
        category: String? = null,
        minModifiedMs: Long? = null
    ): Int

    @Query(
        """
        SELECT id, file_path, file_name, parent_dir, mime_type, file_category,
               size_bytes, last_modified_ms,
               SUBSTR(extracted_text, 1, 220) AS snippet_preview,
               (embedding_vector IS NOT NULL) AS has_embedding
        FROM file_index
        WHERE is_trashed = 0
          AND (:category IS NULL OR file_category = :category)
          AND (:minModifiedMs IS NULL OR last_modified_ms >= :minModifiedMs)
        ORDER BY last_modified_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getSearchItemsForPhoneticMatch(
        category: String? = null,
        minModifiedMs: Long? = null,
        limit: Int = 600
    ): List<FileSearchListItem>

    @Query(
        """
        SELECT * FROM file_index
        WHERE is_trashed = 0
          AND embedding_vector IS NOT NULL
          AND (:category IS NULL OR file_category = :category)
          AND (:minModifiedMs IS NULL OR last_modified_ms >= :minModifiedMs)
          AND (:maxModifiedMs IS NULL OR last_modified_ms <= :maxModifiedMs)
        ORDER BY last_modified_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getFilesWithEmbeddings(
        category: String? = null,
        minModifiedMs: Long? = null,
        maxModifiedMs: Long? = null,
        limit: Int = 500
    ): List<FileIndexEntity>

    @Query(
        """
        SELECT * FROM file_index
        WHERE is_trashed = 0
          AND (embedding_vector IS NULL OR embedding_model_id != :modelId)
        ORDER BY last_modified_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getFilesNeedingEmbeddings(modelId: String, limit: Int = 300): List<FileIndexEntity>

    @Query("UPDATE file_index SET embedding_vector = :vector, embedding_model_id = :modelId WHERE id = :fileId")
    suspend fun updateFileEmbedding(fileId: Long, vector: ByteArray, modelId: String)

    @Query(
        """
        SELECT f.* FROM file_index AS f
        JOIN file_index_fts AS fts ON f.id = fts.rowid
        WHERE file_index_fts MATCH :ftsQuery
          AND f.is_trashed = 0
          AND (:category IS NULL OR f.file_category = :category)
          AND (:minModifiedMs IS NULL OR f.last_modified_ms >= :minModifiedMs)
          AND (:maxModifiedMs IS NULL OR f.last_modified_ms <= :maxModifiedMs)
        ORDER BY f.last_modified_ms DESC
        LIMIT :limit
        """
    )
    suspend fun searchFts(
        ftsQuery: String,
        category: String? = null,
        minModifiedMs: Long? = null,
        maxModifiedMs: Long? = null,
        limit: Int = 40
    ): List<FileIndexEntity>

    @Query(
        """
        SELECT * FROM file_index
        WHERE is_trashed = 0
          AND (:category IS NULL OR file_category = :category)
          AND (:minModifiedMs IS NULL OR last_modified_ms >= :minModifiedMs)
          AND (:maxModifiedMs IS NULL OR last_modified_ms <= :maxModifiedMs)
          AND (file_name LIKE '%' || :likeTerm || '%' OR extracted_text LIKE '%' || :likeTerm || '%' OR file_path LIKE '%' || :likeTerm || '%')
        ORDER BY last_modified_ms DESC
        LIMIT :limit
        """
    )
    suspend fun searchFallbackLike(
        likeTerm: String,
        category: String? = null,
        minModifiedMs: Long? = null,
        maxModifiedMs: Long? = null,
        limit: Int = 40
    ): List<FileIndexEntity>

    @Query(
        """
        SELECT * FROM file_index
        WHERE is_trashed = 0
          AND (:category IS NULL OR file_category = :category)
          AND (:minModifiedMs IS NULL OR last_modified_ms >= :minModifiedMs)
          AND (:maxModifiedMs IS NULL OR last_modified_ms <= :maxModifiedMs)
        ORDER BY last_modified_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getActiveCandidatesForPhoneticSearch(
        category: String? = null,
        minModifiedMs: Long? = null,
        maxModifiedMs: Long? = null,
        limit: Int = 600
    ): List<FileIndexEntity>

    @Query(
        """
        SELECT f.* FROM file_index AS f
        JOIN file_index_fts AS fts ON f.id = fts.rowid
        WHERE file_index_fts MATCH :ftsQuery AND f.is_trashed = 0
        ORDER BY f.last_modified_ms DESC
        LIMIT :limit
        """
    )
    fun observeSearchFts(ftsQuery: String, limit: Int = 100): Flow<List<FileIndexEntity>>

    @Query("UPDATE file_index SET file_path = :newPath, file_name = :newName, parent_dir = :newParent, is_trashed = :isTrashed WHERE file_path = :oldPath")
    suspend fun updateFilePathAndTrashState(
        oldPath: String,
        newPath: String,
        newName: String,
        newParent: String,
        isTrashed: Boolean
    )

    @Query("DELETE FROM file_index WHERE file_path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM file_index")
    suspend fun clearAll()
}

@Dao
interface AgentMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: AgentMemoryEntity): Long

    @Query("SELECT * FROM agent_memory ORDER BY updated_at_ms DESC")
    fun observeAll(): Flow<List<AgentMemoryEntity>>

    @Query("SELECT * FROM agent_memory ORDER BY updated_at_ms DESC")
    suspend fun getAll(): List<AgentMemoryEntity>

    @Query(
        """
        SELECT * FROM agent_memory
        WHERE memory_key LIKE '%' || :keyword || '%'
           OR rule_text LIKE '%' || :keyword || '%'
        ORDER BY updated_at_ms DESC
        LIMIT :limit
        """
    )
    suspend fun queryByKeyword(keyword: String, limit: Int = 15): List<AgentMemoryEntity>

    @Query("DELETE FROM agent_memory WHERE memory_key = :key")
    suspend fun deleteByKey(key: String)
}

@Dao
interface ActionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ActionLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ActionLogEntity>)

    @Query("SELECT * FROM action_logs ORDER BY timestamp_ms DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 100): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_logs WHERE batch_id = :batchId AND is_undone = 0 ORDER BY id DESC")
    suspend fun getActiveLogsForBatch(batchId: String): List<ActionLogEntity>

    @Query("UPDATE action_logs SET is_undone = 1 WHERE batch_id = :batchId")
    suspend fun markBatchUndone(batchId: String)

    @Query("UPDATE action_logs SET is_undone = 1 WHERE id = :id")
    suspend fun markLogUndone(id: Long)
}

@Dao
interface ModelRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelRecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(models: List<ModelRecordEntity>)

    @Query("SELECT * FROM model_records ORDER BY id ASC")
    fun observeAllModels(): Flow<List<ModelRecordEntity>>

    @Query("SELECT * FROM model_records WHERE model_id = :modelId LIMIT 1")
    suspend fun getByModelId(modelId: String): ModelRecordEntity?

    @Query("UPDATE model_records SET status = :status, downloaded_bytes = :downloadedBytes, local_path = :localPath, verified_sha256 = :sha256, updated_at_ms = :updatedAt WHERE model_id = :modelId")
    suspend fun updateDownloadState(
        modelId: String,
        status: String,
        downloadedBytes: Long,
        localPath: String,
        sha256: String = "",
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM model_records WHERE model_id = :modelId")
    suspend fun deleteByModelId(modelId: String)
}

object FtsQuerySanitizer {
    /**
     * Converts free-form natural language queries into safe SQLite FTS4 MATCH expressions
     * using prefix matching (e.g. "electricity bill" -> "electricity* bill*"), preserving Unicode scripts.
     */
    fun toSafeFtsQuery(raw: String): String {
        val tokens = raw
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}_\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in setOf("and", "or", "not", "near") }
            .distinct()
            .take(8)
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "$it*" }
    }

    /**
     * Builds an expanded FTS4 OR query covering Hindi (Devanagari), Hinglish transliteration,
     * phonetic vowel variations (e.g. भूरी / भुरी / bhuri / bhoori), and English concepts.
     */
    fun toCrossScriptFtsQuery(raw: String): String {
        val expanded = HindiTransliterator.expandQueryTokens(raw)
            .map { it.replace(Regex("[^\\p{L}\\p{N}_]"), "") }
            .filter { it.length >= 2 || it.all { ch -> ch.isDigit() } }
            .distinct()
            .take(18)
        if (expanded.isEmpty()) return toSafeFtsQuery(raw)
        return expanded.joinToString(" OR ") { "$it*" }
    }
}
