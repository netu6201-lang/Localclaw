package com.example.operations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

enum class OperationType(val title: String) {
    STORAGE_SCAN("Storage Scan & OCR"),
    MEDIA_PICK_SCAN("Selected Media Scan"),
    VECTORIZE("Embedding Vectorization"),
    MODEL_DOWNLOAD("Model Download"),
    AGENT_TASK("EdgeClaw Agent Task")
}

enum class OperationStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    STOPPED,
    FAILED
}

data class OperationProgress(
    val type: OperationType,
    val status: OperationStatus = OperationStatus.IDLE,
    val processed: Int = 0,
    val total: Int = 0,
    val detail: String = "",
    val subjectId: String = ""
) {
    val isActiveOrPaused: Boolean
        get() = status == OperationStatus.RUNNING || status == OperationStatus.PAUSED
}

/**
 * Coordinates cooperative Pause / Resume / Stop across all long-running operations
 * (full storage scan, selected media indexing, EmbeddingGemma vectorization,
 * model downloads, and autonomous agent reasoning).
 */
class OperationControlManager {

    private val _operations = MutableStateFlow(
        OperationType.entries.associateWith { OperationProgress(type = it) }
    )
    val operations: StateFlow<Map<OperationType, OperationProgress>> = _operations.asStateFlow()

    fun getState(type: OperationType): OperationProgress =
        _operations.value[type] ?: OperationProgress(type = type)

    fun start(
        type: OperationType,
        total: Int = 0,
        detail: String = "",
        subjectId: String = ""
    ) {
        _operations.update { current ->
            current + (
                type to OperationProgress(
                    type = type,
                    status = OperationStatus.RUNNING,
                    processed = 0,
                    total = total,
                    detail = detail,
                    subjectId = subjectId
                )
            )
        }
    }

    fun updateProgress(
        type: OperationType,
        processed: Int,
        total: Int,
        detail: String? = null
    ) {
        _operations.update { current ->
            val prev = current[type] ?: OperationProgress(type = type)
            if (prev.status == OperationStatus.STOPPED) return@update current
            current + (
                type to prev.copy(
                    status = if (prev.status == OperationStatus.PAUSED) {
                        OperationStatus.PAUSED
                    } else {
                        OperationStatus.RUNNING
                    },
                    processed = processed,
                    total = total,
                    detail = detail ?: prev.detail
                )
            )
        }
    }

    fun pause(type: OperationType, detail: String? = null) {
        _operations.update { current ->
            val prev = current[type] ?: return@update current
            if (prev.status != OperationStatus.RUNNING) return@update current
            current + (
                type to prev.copy(
                    status = OperationStatus.PAUSED,
                    detail = detail ?: "Paused (${prev.processed}/${prev.total.coerceAtLeast(1)})"
                )
            )
        }
    }

    fun resume(type: OperationType, detail: String? = null) {
        _operations.update { current ->
            val prev = current[type] ?: return@update current
            if (prev.status != OperationStatus.PAUSED) return@update current
            current + (
                type to prev.copy(
                    status = OperationStatus.RUNNING,
                    detail = detail ?: "Resuming..."
                )
            )
        }
    }

    fun stop(type: OperationType, detail: String? = null) {
        _operations.update { current ->
            val prev = current[type] ?: OperationProgress(type = type)
            current + (
                type to prev.copy(
                    status = OperationStatus.STOPPED,
                    detail = detail ?: "Stopped by user"
                )
            )
        }
    }

    fun complete(type: OperationType, detail: String = "Completed") {
        _operations.update { current ->
            val prev = current[type] ?: OperationProgress(type = type)
            current + (
                type to prev.copy(
                    status = OperationStatus.COMPLETED,
                    processed = if (prev.total > 0) prev.total else prev.processed,
                    detail = detail
                )
            )
        }
    }

    fun fail(type: OperationType, detail: String) {
        _operations.update { current ->
            val prev = current[type] ?: OperationProgress(type = type)
            current + (
                type to prev.copy(
                    status = OperationStatus.FAILED,
                    detail = detail
                )
            )
        }
    }

    fun reset(type: OperationType) {
        _operations.update { current ->
            current + (type to OperationProgress(type = type))
        }
    }

    fun isPaused(type: OperationType): Boolean =
        getState(type).status == OperationStatus.PAUSED

    fun isStopped(type: OperationType): Boolean =
        getState(type).status == OperationStatus.STOPPED

    /**
     * Cooperative suspension point:
     * - Throws [CancellationException] immediately if the operation was stopped.
     * - Suspends without busy-waiting if paused until resumed or stopped.
     */
    suspend fun checkpoint(type: OperationType) {
        val current = getState(type).status
        if (current == OperationStatus.STOPPED) {
            throw CancellationException("${type.title} stopped by user")
        }
        if (current == OperationStatus.PAUSED) {
            val nextStatus = _operations
                .map { it[type]?.status ?: OperationStatus.IDLE }
                .first { it != OperationStatus.PAUSED }
            if (nextStatus == OperationStatus.STOPPED) {
                throw CancellationException("${type.title} stopped by user")
            }
        }
    }
}
