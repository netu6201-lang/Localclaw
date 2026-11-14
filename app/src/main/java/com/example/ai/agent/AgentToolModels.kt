package com.example.ai.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AgentToolCall(
    val thought: String = "",
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap())
)

enum class TraceStage {
    PLAN,
    TOOL_CALL,
    OBSERVE,
    CONFIRMATION_REQUIRED,
    RESPONSE,
    ERROR
}

data class AgentStepTrace(
    val id: Long = System.nanoTime(),
    val stage: TraceStage,
    val title: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class PendingActionBatch(
    val batchId: String,
    val actionTitle: String,
    val operation: String, // MOVE, COPY, RENAME, SAFE_TRASH
    val sourcePaths: List<String>,
    val destinationPath: String,
    val reason: String
)

data class AgentChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val traces: List<AgentStepTrace> = emptyList(),
    val batchId: String? = null,
    val isStreaming: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

@Serializable
data class SearchFilesArgs(
    val query: String = "",
    @SerialName("file_type") val fileType: String = "ALL",
    @SerialName("date_range") val dateRange: String = "ALL"
)

@Serializable
data class ReadFileSummaryArgs(
    @SerialName("file_path") val filePath: String
)

@Serializable
data class FileActionArgs(
    val operation: String,
    @SerialName("source_paths") val sourcePaths: List<String> = emptyList(),
    @SerialName("destination_path") val destinationPath: String = ""
)

@Serializable
data class SafeTrashArgs(
    @SerialName("file_paths") val filePaths: List<String> = emptyList()
)

@Serializable
data class MemoryUpsertArgs(
    val key: String,
    @SerialName("rule_text") val ruleText: String
)

@Serializable
data class MemoryQueryArgs(
    val context: String = ""
)
