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
data class ListDirectoryArgs(
    val path: String = ""
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
data class UndoBatchArgs(
    @SerialName("batch_id") val batchId: String = ""
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

@Serializable
data class ScanStorageArgs(
    val action: String = "FULL_SCAN" // FULL_SCAN | STATUS
)

@Serializable
data class ManageModelsArgs(
    val action: String = "LIST", // LIST | LOAD_LLM | UNLOAD_LLM | LOAD_EMBEDDER | UNLOAD_EMBEDDER | BUILD_VECTORS | TEST_OLLAMA
    @SerialName("model_id") val modelId: String = ""
)

@Serializable
data class UpdateAppSettingsArgs(
    @SerialName("scan_mode") val scanMode: String? = null, // MANUAL | AUTO_SCAN | BATCH
    @SerialName("hardware_delegate") val hardwareDelegate: String? = null, // NPU | GPU | CPU
    @SerialName("llm_provider_mode") val llmProviderMode: String? = null, // ON_DEVICE | OLLAMA | AUTO_FALLBACK
    @SerialName("ollama_base_url") val ollamaBaseUrl: String? = null,
    @SerialName("ollama_model_name") val ollamaModelName: String? = null,
    @SerialName("ollama_api_key") val ollamaApiKey: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_k") val topK: Int? = null,
    val temperature: Float? = null
)
