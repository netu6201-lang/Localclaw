package com.example.ai.agent

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.ai.engine.EmbeddingRuntimeStatus
import com.example.ai.engine.LocalEmbeddingEngineManager
import com.example.ai.engine.LocalLlmEngineManager
import com.example.ai.engine.VectorMath
import com.example.data.db.ActionLogDao
import com.example.data.db.FileIndexDao
import com.example.data.db.ModelRecordDao
import com.example.data.preferences.AppPreferences
import com.example.data.preferences.HardwareDelegate
import com.example.data.preferences.LlmProviderMode
import com.example.data.preferences.ScanMode
import com.example.operations.OperationControlManager
import com.example.operations.OperationType
import com.example.workers.MediaAutoScanScheduler
import com.example.workers.MediaFileExtractor
import com.example.workers.StorageIndexerWorker
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Grants the EdgeClaw Agent full programmatic access to app settings, storage scans,
 * directory inspection, model management, Ollama connection testing, and EmbeddingGemma vectorization.
 */
class AgentSystemToolbox(
    private val context: Context,
    private val preferences: AppPreferences,
    private val mediaAutoScanScheduler: MediaAutoScanScheduler,
    private val llmEngineManager: LocalLlmEngineManager,
    private val embeddingEngineManager: LocalEmbeddingEngineManager,
    private val fileIndexDao: FileIndexDao,
    private val modelRecordDao: ModelRecordDao,
    private val actionLogDao: ActionLogDao,
    private val mediaExtractor: MediaFileExtractor,
    private val guardrails: SandboxGuardrails,
    private val operationControlManager: OperationControlManager
) {
    private val workManager: WorkManager?
        get() = runCatching { WorkManager.getInstance(context) }.getOrNull()

    suspend fun getAppSettingsSummary(): String {
        val settings = preferences.settingsFlow.first()
        val llm = llmEngineManager.telemetry.value
        val embed = embeddingEngineManager.telemetry.value
        val indexedCount = fileIndexDao.observeIndexedCount().first()
        val embeddedCount = fileIndexDao.observeEmbeddedCount().first()
        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        return buildString {
            appendLine("- scan_mode: ${settings.scanMode.name} (auto_scan_new_media=${settings.autoScanNewMedia})")
            appendLine("- hardware_delegate: ${settings.delegate.name} (active LLM delegate=${llm.activeDelegate.name})")
            appendLine("- llm_provider_mode: ${settings.llmProviderMode.name}")
            appendLine("- active_llm: ${llm.loadedModelName} (status=${llm.status.name}, usingOllama=${llm.isUsingOllama})")
            appendLine("- ollama_base_url: ${settings.ollamaBaseUrl}")
            appendLine("- ollama_model_name: ${settings.ollamaModelName}")
            appendLine("- ollama_api_key_configured: ${settings.ollamaApiKey.isNotBlank()}")
            appendLine("- active_embedder: ${embed.loadedModelName} (status=${embed.status.name}, dims=${embed.vectorDimensions})")
            appendLine("- indexed_files: $indexedCount | vectorized_files: $embeddedCount")
            appendLine("- all_files_storage_permission: $allFiles")
            appendLine("- sampling: maxTokens=${settings.maxTokens}, topK=${settings.topK}, temperature=${settings.temperature}")
        }.trim()
    }

    suspend fun updateAppSettings(args: UpdateAppSettingsArgs): String {
        val changes = mutableListOf<String>()

        args.scanMode?.trim()?.uppercase()?.let { rawMode ->
            val mode = runCatching { ScanMode.valueOf(rawMode) }.getOrNull()
                ?: return "Invalid scan_mode '$rawMode'. Allowed: MANUAL, AUTO_SCAN, BATCH."
            preferences.setScanMode(mode)
            mediaAutoScanScheduler.setAutoScanEnabled(mode == ScanMode.AUTO_SCAN)
            changes += "scan_mode=${mode.name}"
        }

        args.hardwareDelegate?.trim()?.uppercase()?.let { rawDelegate ->
            val delegate = runCatching { HardwareDelegate.valueOf(rawDelegate) }.getOrNull()
                ?: return "Invalid hardware_delegate '$rawDelegate'. Allowed: NPU, GPU, CPU."
            preferences.setDelegate(delegate)
            changes += "hardware_delegate=${delegate.name}"
        }

        val current = preferences.settingsFlow.first()

        val newProviderMode = args.llmProviderMode?.trim()?.uppercase()?.let { rawProv ->
            runCatching { LlmProviderMode.valueOf(rawProv) }.getOrNull()
                ?: return "Invalid llm_provider_mode '$rawProv'. Allowed: ON_DEVICE, OLLAMA, AUTO_FALLBACK."
        } ?: current.llmProviderMode

        if (args.llmProviderMode != null) {
            preferences.setLlmProviderMode(newProviderMode)
            changes += "llm_provider_mode=${newProviderMode.name}"
        }

        val newOllamaUrl = args.ollamaBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: current.ollamaBaseUrl
        val newOllamaModel = args.ollamaModelName?.trim()?.takeIf { it.isNotBlank() } ?: current.ollamaModelName
        val newOllamaKey = args.ollamaApiKey ?: current.ollamaApiKey

        if (args.ollamaBaseUrl != null || args.ollamaModelName != null || args.ollamaApiKey != null) {
            preferences.setOllamaConfig(newOllamaUrl, newOllamaModel, newOllamaKey)
            if (args.ollamaBaseUrl != null) changes += "ollama_base_url=$newOllamaUrl"
            if (args.ollamaModelName != null) changes += "ollama_model_name=$newOllamaModel"
            if (args.ollamaApiKey != null) changes += "ollama_api_key=${if (newOllamaKey.isBlank()) "cleared" else "updated"}"
        }

        val newMaxTokens = args.maxTokens?.coerceIn(128, 8192) ?: current.maxTokens
        val newTopK = args.topK?.coerceIn(1, 128) ?: current.topK
        val newTemp = args.temperature?.coerceIn(0f, 2f) ?: current.temperature
        if (args.maxTokens != null || args.topK != null || args.temperature != null) {
            preferences.updateSampling(newMaxTokens, newTopK, newTemp)
            changes += "sampling(maxTokens=$newMaxTokens, topK=$newTopK, temp=$newTemp)"
        }

        llmEngineManager.configureProvider(
            mode = newProviderMode,
            ollamaBaseUrl = newOllamaUrl,
            ollamaModelName = newOllamaModel,
            ollamaApiKey = newOllamaKey,
            maxTokens = newMaxTokens,
            temperature = newTemp
        )

        return if (changes.isEmpty()) {
            "No settings fields were modified. Current settings:\n${getAppSettingsSummary()}"
        } else {
            "Updated settings: ${changes.joinToString(", ")}\n\nCurrent settings:\n${getAppSettingsSummary()}"
        }
    }

    suspend fun listDirectory(rawPath: String): String {
        val targetPath = rawPath.trim().ifBlank { Environment.getExternalStorageDirectory().absolutePath }
        return when (val check = guardrails.validatePath(targetPath)) {
            is SandboxGuardrails.ValidationResult.Blocked -> check.reason
            is SandboxGuardrails.ValidationResult.Allowed -> {
                val dir = check.canonicalFile
                if (!dir.exists()) return "Directory does not exist: ${dir.absolutePath}"
                if (!dir.isDirectory) return "Path is a file, not a directory: ${dir.absolutePath}"
                val entries = dir.listFiles()
                    ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    .orEmpty()
                if (entries.isEmpty()) {
                    "Directory '${dir.absolutePath}' is empty."
                } else {
                    val shown = entries.take(50).joinToString("\n") { entry ->
                        val kind = if (entry.isDirectory) "DIR" else "FILE (${entry.length() / 1024} KB)"
                        "- [${kind}] ${entry.name} | path=${entry.absolutePath}"
                    }
                    "Directory ${dir.absolutePath} (${entries.size} entries):\n$shown" +
                        if (entries.size > 50) "\n...and ${entries.size - 50} more." else ""
                }
            }
        }
    }

    suspend fun handleScanStorage(action: String): String {
        val normalized = action.trim().uppercase()
        if (normalized == "STATUS") {
            val scanOp = operationControlManager.getState(OperationType.STORAGE_SCAN)
            val indexedCount = fileIndexDao.observeIndexedCount().first()
            return "Indexed files: $indexedCount | Scan status: ${scanOp.status.name} (${scanOp.detail})"
        }
        val wm = workManager
            ?: return "WorkManager unavailable in current test environment; run indexing inside app runtime."
        val request = OneTimeWorkRequestBuilder<StorageIndexerWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(StorageIndexerWorker.TAG_INDEXER_WORK)
            .build()
        wm.enqueueUniqueWork(
            StorageIndexerWorker.UNIQUE_INDEXER_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return "Started background storage indexing & ML Kit OCR scan across Downloads, Documents, Pictures, and DCIM. User can Pause/Resume/Stop anytime."
    }

    suspend fun handleManageModels(args: ManageModelsArgs): String {
        val action = args.action.trim().uppercase()
        return when (action) {
            "LIST" -> {
                val models = modelRecordDao.observeAllModels().first()
                val llmTelemetry = llmEngineManager.telemetry.value
                val embedTelemetry = embeddingEngineManager.telemetry.value
                buildString {
                    appendLine("Active LLM: ${llmTelemetry.loadedModelName} (${llmTelemetry.status.name}, provider=${llmTelemetry.providerMode.name})")
                    appendLine("Active Embedder: ${embedTelemetry.loadedModelName} (${embedTelemetry.status.name}, dims=${embedTelemetry.vectorDimensions})")
                    appendLine("Ollama: ${llmTelemetry.ollamaModelName} @ ${llmTelemetry.ollamaBaseUrl}")
                    appendLine("\nCatalog Models:")
                    models.forEach { m ->
                        appendLine("- id=${m.modelId} | role=${m.modelRole} | status=${m.status} | name=${m.displayName}")
                    }
                }.trim()
            }
            "TEST_OLLAMA" -> {
                val settings = preferences.settingsFlow.first()
                val res = llmEngineManager.testOllamaConnection(settings.ollamaBaseUrl, settings.ollamaApiKey)
                res.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) {
                            "Connected to Ollama at ${settings.ollamaBaseUrl}, but no models were listed. Run `ollama pull gemma3:4b`."
                        } else {
                            "Connected to Ollama at ${settings.ollamaBaseUrl}. Available models: ${list.joinToString(", ")}"
                        }
                    },
                    onFailure = { err ->
                        "Ollama connection failed (${settings.ollamaBaseUrl}): ${err.message}"
                    }
                )
            }
            "UNLOAD_LLM" -> {
                llmEngineManager.unloadModel()
                preferences.setActiveModelId("")
                "Unloaded on-device LLM."
            }
            "UNLOAD_EMBEDDER" -> {
                embeddingEngineManager.unloadModel()
                "Unloaded on-device EmbeddingGemma model."
            }
            "LOAD_LLM" -> {
                val settings = preferences.settingsFlow.first()
                val model = if (args.modelId.isNotBlank()) {
                    modelRecordDao.getByModelId(args.modelId)
                } else {
                    modelRecordDao.observeAllModels().first().firstOrNull { it.modelRole != "EMBEDDING" && it.status == "READY" }
                } ?: return "No downloaded READY LLM found matching '${args.modelId}'."
                val res = llmEngineManager.loadModel(
                    modelId = model.modelId,
                    displayName = model.displayName,
                    modelPath = model.localPath,
                    requestedDelegate = settings.delegate,
                    systemInstruction = AgentPromptAndSchema.buildSystemPrompt(),
                    maxTokens = settings.maxTokens,
                    topK = settings.topK,
                    temperature = settings.temperature
                )
                res.fold(
                    onSuccess = {
                        preferences.setActiveModelId(model.modelId)
                        "Loaded LLM '${model.displayName}' on ${settings.delegate.label}."
                    },
                    onFailure = { err ->
                        "Failed to load '${model.displayName}': ${err.message}"
                    }
                )
            }
            "LOAD_EMBEDDER" -> {
                val settings = preferences.settingsFlow.first()
                val model = if (args.modelId.isNotBlank()) {
                    modelRecordDao.getByModelId(args.modelId)
                } else {
                    modelRecordDao.observeAllModels().first().firstOrNull { it.modelRole == "EMBEDDING" && it.status == "READY" }
                } ?: return "No downloaded READY embedding model found."
                val res = embeddingEngineManager.loadModel(
                    modelId = model.modelId,
                    displayName = model.displayName,
                    modelPath = model.localPath,
                    requestedDelegate = settings.delegate
                )
                res.fold(
                    onSuccess = { "Loaded EmbeddingGemma '${model.displayName}'." },
                    onFailure = { err -> "Failed to load embedder: ${err.message}" }
                )
            }
            "BUILD_VECTORS" -> {
                val embed = embeddingEngineManager.telemetry.value
                if (embed.status != EmbeddingRuntimeStatus.READY || embed.loadedModelId.isBlank()) {
                    return "EmbeddingGemma is not loaded yet. Load an embedding model first in the Models tab or call manage_models(action='LOAD_EMBEDDER')."
                }
                val pending = fileIndexDao.getFilesNeedingEmbeddings(embed.loadedModelId, limit = 80)
                if (pending.isEmpty()) {
                    return "All indexed files already have EmbeddingGemma vectors."
                }
                var done = 0
                for (file in pending) {
                    val img = if (file.fileCategory == "IMAGE" && !file.filePath.startsWith("content://")) {
                        mediaExtractor.loadDownsampledImageBytesFromFile(File(file.filePath))
                    } else null
                    val vec = embeddingEngineManager.embedDocument(file.fileName, file.extractedText, img)
                    if (vec != null && vec.isNotEmpty()) {
                        fileIndexDao.updateFileEmbedding(file.id, VectorMath.floatArrayToBytes(vec), embed.loadedModelId)
                        done++
                    }
                }
                "Generated EmbeddingGemma vectors for $done file(s)."
            }
            else -> "Unknown manage_models action '$action'."
        }
    }

    suspend fun getLatestUndoableBatchId(): String? {
        return actionLogDao.observeRecentLogs(limit = 20).first()
            .firstOrNull { !it.isUndone }
            ?.batchId
    }
}
