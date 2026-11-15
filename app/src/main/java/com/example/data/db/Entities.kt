package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_index",
    indices = [
        Index(value = ["file_path"], unique = true),
        Index(value = ["file_category"]),
        Index(value = ["last_modified_ms"])
    ]
)
data class FileIndexEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "parent_dir")
    val parentDir: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "file_category")
    val fileCategory: String, // IMAGE, PDF, DOCUMENT, AUDIO, VIDEO, ARCHIVE, OTHER
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "last_modified_ms")
    val lastModifiedMs: Long,
    @ColumnInfo(name = "extracted_text")
    val extractedText: String = "",
    @ColumnInfo(name = "embedding_vector", typeAffinity = ColumnInfo.BLOB)
    val embeddingVector: ByteArray? = null,
    @ColumnInfo(name = "embedding_model_id")
    val embeddingModelId: String = "",
    @ColumnInfo(name = "is_trashed")
    val isTrashed: Boolean = false,
    @ColumnInfo(name = "indexed_at_ms")
    val indexedAtMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileIndexEntity) return false
        return id == other.id &&
            filePath == other.filePath &&
            fileName == other.fileName &&
            sizeBytes == other.sizeBytes &&
            lastModifiedMs == other.lastModifiedMs &&
            extractedText == other.extractedText &&
            embeddingModelId == other.embeddingModelId &&
            isTrashed == other.isTrashed &&
            indexedAtMs == other.indexedAtMs &&
            (embeddingVector contentEquals other.embeddingVector)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + filePath.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + lastModifiedMs.hashCode()
        result = 31 * result + (embeddingVector?.contentHashCode() ?: 0)
        return result
    }
}

@Fts4(
    contentEntity = FileIndexEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61
)
@Entity(tableName = "file_index_fts")
data class FileIndexFtsEntity(
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "extracted_text")
    val extractedText: String
)

@Entity(
    tableName = "agent_memory",
    indices = [Index(value = ["memory_key"], unique = true)]
)
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "memory_key")
    val memoryKey: String,
    @ColumnInfo(name = "rule_text")
    val ruleText: String,
    @ColumnInfo(name = "category")
    val category: String = "PREFERENCE",
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "action_logs",
    indices = [Index(value = ["batch_id"]), Index(value = ["timestamp_ms"])]
)
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "batch_id")
    val batchId: String,
    @ColumnInfo(name = "operation")
    val operation: String, // MOVE, COPY, RENAME, CREATE_FOLDER, SAFE_TRASH, RESTORE
    @ColumnInfo(name = "source_path")
    val sourcePath: String,
    @ColumnInfo(name = "destination_path")
    val destinationPath: String,
    @ColumnInfo(name = "summary")
    val summary: String,
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_undone")
    val isUndone: Boolean = false
)

@Entity(
    tableName = "model_records",
    indices = [Index(value = ["model_id"], unique = true)]
)
data class ModelRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "download_url")
    val downloadUrl: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "local_path")
    val localPath: String = "",
    @ColumnInfo(name = "expected_size_bytes")
    val expectedSizeBytes: Long,
    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long = 0L,
    @ColumnInfo(name = "format")
    val format: String, // LITERTLM or TASK
    @ColumnInfo(name = "model_role")
    val modelRole: String = "LLM", // LLM or EMBEDDING
    @ColumnInfo(name = "recommended_backend")
    val recommendedBackend: String, // NPU, GPU, CPU
    @ColumnInfo(name = "status")
    val status: String = "NOT_DOWNLOADED", // NOT_DOWNLOADED, DOWNLOADING, VERIFYING, READY, FAILED
    @ColumnInfo(name = "verified_sha256")
    val verifiedSha256: String = "",
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
)
