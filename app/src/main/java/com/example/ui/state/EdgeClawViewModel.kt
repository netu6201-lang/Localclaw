package com.example.ui.state

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.EdgeClawApplication
import com.example.ai.agent.AgentChatMessage
import com.example.ai.agent.PendingActionBatch
import com.example.ai.engine.LlmTelemetry
import com.example.ai.engine.ModelCatalog
import com.example.data.db.ActionLogEntity
import com.example.data.db.AgentMemoryEntity
import com.example.data.db.FileIndexEntity
import com.example.data.db.FtsQuerySanitizer
import com.example.data.db.ModelRecordEntity
import com.example.data.preferences.EngineSettings
import com.example.data.preferences.HardwareDelegate
import com.example.workers.ModelDownloadWorker
import com.example.workers.StorageIndexerWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class EdgeClawViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EdgeClawApplication
    private val container = app.container
    private val workManager = WorkManager.getInstance(application)

    private val fileDao = container.database.fileIndexDao()
    private val memoryDao = container.database.agentMemoryDao()
    private val actionLogDao = container.database.actionLogDao()
    private val modelDao = container.database.modelRecordDao()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("ALL")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _hasAllFilesAccess = MutableStateFlow(checkAllFilesAccess())
    val hasAllFilesAccess: StateFlow<Boolean> = _hasAllFilesAccess.asStateFlow()

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _indexStatusMessage = MutableStateFlow("")
    val indexStatusMessage: StateFlow<String> = _indexStatusMessage.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    private val _uiBannerMessage = MutableStateFlow<String?>(null)
    val uiBannerMessage: StateFlow<String?> = _uiBannerMessage.asStateFlow()

    val engineSettings: StateFlow<EngineSettings> = container.preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EngineSettings())

    val telemetry: StateFlow<LlmTelemetry> = container.llmEngineManager.telemetry

    val indexedCount: StateFlow<Int> = fileDao.observeIndexedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val models: StateFlow<List<ModelRecordEntity>> = modelDao.observeAllModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agentMessages: StateFlow<List<AgentChatMessage>> = container.agentOrchestrator.messages
    val pendingConfirmation: StateFlow<PendingActionBatch?> = container.agentOrchestrator.pendingConfirmation
    val isAgentBusy: StateFlow<Boolean> = container.agentOrchestrator.isAgentBusy

    val actionLogs: StateFlow<List<ActionLogEntity>> = actionLogDao.observeRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memoryRules: StateFlow<List<AgentMemoryEntity>> = memoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trashedFiles: StateFlow<List<FileIndexEntity>> = fileDao.observeTrashedFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val explorerFiles: StateFlow<List<FileIndexEntity>> = combine(_searchQuery, _selectedCategory) { query, cat ->
        query.trim() to cat
    }.flatMapLatest { (query, cat) ->
        val safeFts = FtsQuerySanitizer.toSafeFtsQuery(query)
        when {
            safeFts.isNotEmpty() -> fileDao.observeSearchFts(safeFts, limit = 120)
            cat != "ALL" -> fileDao.observeByCategory(cat, limit = 300)
            else -> fileDao.observeAllActiveFiles(limit = 400)
        }
    }.combine(_selectedCategory) { list, cat ->
        if (cat == "ALL") list else list.filter { it.fileCategory == cat }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        observeIndexerWork()
    }

    fun checkAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun refreshPermissions() {
        _hasAllFilesAccess.value = checkAllFilesAccess()
        container.llmEngineManager.refreshMemoryTelemetry()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    fun clearBanner() {
        _uiBannerMessage.value = null
    }

    fun startStorageIndexing() {
        val request = OneTimeWorkRequestBuilder<StorageIndexerWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_INDEXER_WORK)
            .build()
        workManager.enqueueUniqueWork(
            "edgeclaw_storage_indexer",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun observeIndexerWork() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(TAG_INDEXER_WORK).collect { infos ->
                val latest = infos.lastOrNull() ?: return@collect
                when (latest.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        _isIndexing.value = true
                        val done = latest.progress.getInt(StorageIndexerWorker.KEY_INDEXED_COUNT, 0)
                        val total = latest.progress.getInt(StorageIndexerWorker.KEY_TOTAL_COUNT, 0)
                        _indexStatusMessage.value = if (total > 0) {
                            "Indexing & OCR: $done / $total files"
                        } else {
                            "Scanning storage & extracting text..."
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _isIndexing.value = false
                        val count = latest.outputData.getInt(StorageIndexerWorker.KEY_INDEXED_COUNT, 0)
                        _indexStatusMessage.value = "Indexed $count file(s) with OCR & PDF extraction"
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        _isIndexing.value = false
                        _indexStatusMessage.value = "Indexing paused or cancelled"
                    }
                    else -> Unit
                }
            }
        }
    }

    fun downloadModel(model: ModelRecordEntity) {
        val workName = "download_${model.modelId}"
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to model.modelId))
            .addTag(workName)
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)

        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(workName).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                val pct = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                _downloadProgress.update { it + (model.modelId to pct) }
                if (info.state.isFinished) {
                    _downloadProgress.update { it - model.modelId }
                    if (info.state == WorkInfo.State.FAILED) {
                        val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                            ?: "Model download failed"
                        _uiBannerMessage.value = err
                    }
                }
            }
        }
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork("download_$modelId")
        viewModelScope.launch(Dispatchers.IO) {
            modelDao.updateDownloadState(modelId, "NOT_DOWNLOADED", 0L, "")
        }
    }

    fun importLocalModel(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val displayFileName = queryFileName(getApplication(), uri)
                        ?: "custom_model_${System.currentTimeMillis()}.litertlm"
                    val safeName = displayFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    if (!safeName.endsWith(".litertlm", true) && !safeName.endsWith(".task", true)) {
                        error("Unsupported file. Select a .litertlm or .task model file.")
                    }
                    val destFile = File(ModelCatalog.modelsDir(getApplication()), safeName)
                    val digest = MessageDigest.getInstance("SHA-256")
                    resolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val r = input.read(buf)
                                if (r == -1) break
                                output.write(buf, 0, r)
                                digest.update(buf, 0, r)
                            }
                        }
                    } ?: error("Cannot open selected model URI")

                    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    val modelId = "local_${safeName.lowercase()}"
                    val format = if (safeName.endsWith(".task", true)) "TASK" else "LITERTLM"
                    val record = ModelRecordEntity(
                        modelId = modelId,
                        displayName = safeName.substringBeforeLast('.'),
                        description = "Locally imported model inside private app sandbox",
                        downloadUrl = "",
                        fileName = safeName,
                        localPath = destFile.absolutePath,
                        expectedSizeBytes = destFile.length(),
                        downloadedBytes = destFile.length(),
                        format = format,
                        recommendedBackend = "GPU",
                        status = "READY",
                        verifiedSha256 = sha256
                    )
                    modelDao.upsert(record)
                    record
                }
            }
            result.onSuccess { record ->
                _uiBannerMessage.value = "Imported ${record.fileName} (${record.downloadedBytes / (1024 * 1024)} MB)"
            }.onFailure { err ->
                _uiBannerMessage.value = err.message ?: "Failed to import model"
            }
        }
    }

    fun loadModel(model: ModelRecordEntity) {
        viewModelScope.launch {
            val delegate = engineSettings.value.delegate
            val settings = engineSettings.value
            val systemPrompt = com.example.ai.agent.AgentPromptAndSchema.buildSystemPrompt()
            val res = container.llmEngineManager.loadModel(
                modelId = model.modelId,
                displayName = model.displayName,
                modelPath = model.localPath,
                requestedDelegate = delegate,
                systemInstruction = systemPrompt,
                maxTokens = settings.maxTokens,
                topK = settings.topK,
                temperature = settings.temperature
            )
            res.onSuccess {
                container.preferences.setActiveModelId(model.modelId)
                _uiBannerMessage.value = "Loaded ${model.displayName}"
            }.onFailure { err ->
                _uiBannerMessage.value = "Model load error: ${err.message}"
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            container.llmEngineManager.unloadModel()
            container.preferences.setActiveModelId("")
        }
    }

    fun deleteModelFile(model: ModelRecordEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (telemetry.value.loadedModelId == model.modelId) {
                container.llmEngineManager.unloadModel()
            }
            if (model.localPath.isNotBlank()) {
                File(model.localPath).delete()
            }
            if (model.modelId.startsWith("local_")) {
                modelDao.deleteByModelId(model.modelId)
            } else {
                modelDao.updateDownloadState(model.modelId, "NOT_DOWNLOADED", 0L, "")
            }
        }
    }

    fun selectHardwareDelegate(delegate: HardwareDelegate) {
        viewModelScope.launch {
            container.preferences.setDelegate(delegate)
            val currentTelemetry = telemetry.value
            if (currentTelemetry.loadedModelId.isNotBlank() && currentTelemetry.loadedModelPath.isNotBlank()) {
                val model = modelDao.getByModelId(currentTelemetry.loadedModelId)
                if (model != null) {
                    val settings = engineSettings.value
                    container.llmEngineManager.loadModel(
                        modelId = model.modelId,
                        displayName = model.displayName,
                        modelPath = model.localPath,
                        requestedDelegate = delegate,
                        systemInstruction = com.example.ai.agent.AgentPromptAndSchema.buildSystemPrompt(),
                        maxTokens = settings.maxTokens,
                        topK = settings.topK,
                        temperature = settings.temperature
                    )
                }
            }
        }
    }

    fun sendAgentPrompt(prompt: String) {
        viewModelScope.launch {
            container.agentOrchestrator.submitUserPrompt(prompt)
        }
    }

    fun approvePendingAction(approved: Boolean) {
        container.agentOrchestrator.resolvePendingConfirmation(approved)
    }

    fun clearAgentChat() {
        container.agentOrchestrator.clearConversation()
    }

    fun undoBatch(batchId: String) {
        viewModelScope.launch {
            val report = container.fileOpsExecutor.undoBatch(batchId)
            _uiBannerMessage.value = report.message
        }
    }

    fun safeTrashSingleFile(file: FileIndexEntity) {
        viewModelScope.launch {
            val report = container.fileOpsExecutor.executeSafeTrash(
                rawPaths = listOf(file.filePath),
                batchId = UUID.randomUUID().toString()
            )
            _uiBannerMessage.value = report.message
        }
    }

    fun upsertMemoryRule(key: String, ruleText: String) {
        if (key.isBlank() || ruleText.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            memoryDao.upsert(
                AgentMemoryEntity(
                    memoryKey = key.trim().lowercase().replace(" ", "_"),
                    ruleText = ruleText.trim()
                )
            )
        }
    }

    fun deleteMemoryRule(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryDao.deleteByKey(key)
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    override fun onCleared() {
        super.onCleared()
        container.llmEngineManager.shutdown()
    }

    companion object {
        private const val TAG_INDEXER_WORK = "edgeclaw_indexer"

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return EdgeClawViewModel(application) as T
                }
            }
    }
}
