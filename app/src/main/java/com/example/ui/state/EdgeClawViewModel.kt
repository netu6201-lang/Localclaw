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
import com.example.ai.engine.EmbeddingRuntimeStatus
import com.example.ai.engine.EmbeddingTelemetry
import com.example.ai.engine.LlmTelemetry
import com.example.ai.engine.ModelCatalog
import com.example.ai.engine.VectorMath
import com.example.data.db.ActionLogEntity
import com.example.data.db.AgentMemoryEntity
import com.example.data.db.FileIndexEntity
import com.example.data.db.FileSearchListItem
import com.example.data.db.FtsQuerySanitizer
import com.example.data.db.ModelRecordEntity
import com.example.data.preferences.EngineSettings
import com.example.data.preferences.HardwareDelegate
import com.example.operations.OperationProgress
import com.example.operations.OperationStatus
import com.example.operations.OperationType
import com.example.workers.ModelDownloadWorker
import com.example.workers.StorageIndexerWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SearchScreenUiState(
    val query: String = "",
    val category: String = "ALL",
    val dateRange: String = "ALL", // ALL, TODAY, LAST_7_DAYS, LAST_30_DAYS
    val useHybrid: Boolean = true,
    val results: List<FileSearchListItem> = emptyList(),
    val totalMatches: Int = 0,
    val latencyMs: Long = 0L,
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val usedHybridSemantic: Boolean = false,
    val searchStoppedByUser: Boolean = false
)

class EdgeClawViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EdgeClawApplication
    private val container = app.container
    private val workManager = WorkManager.getInstance(application)

    private val fileDao = container.database.fileIndexDao()
    private val memoryDao = container.database.agentMemoryDao()
    private val actionLogDao = container.database.actionLogDao()
    private val modelDao = container.database.modelRecordDao()
    private val opControl = container.operationControlManager

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

    private val _isVectorizing = MutableStateFlow(false)
    val isVectorizing: StateFlow<Boolean> = _isVectorizing.asStateFlow()

    private val _vectorizeStatusMessage = MutableStateFlow("")
    val vectorizeStatusMessage: StateFlow<String> = _vectorizeStatusMessage.asStateFlow()

    // Dedicated Search Screen State (Paginated Room FTS4 + Hybrid Vector)
    private val _searchScreenState = MutableStateFlow(SearchScreenUiState())
    val searchScreenState: StateFlow<SearchScreenUiState> = _searchScreenState.asStateFlow()

    private var vectorizeJob: Job? = null
    private var mediaPickJob: Job? = null
    private var searchJob: Job? = null

    val operationStates: StateFlow<Map<OperationType, OperationProgress>> = opControl.operations

    val engineSettings: StateFlow<EngineSettings> = container.preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EngineSettings())

    val telemetry: StateFlow<LlmTelemetry> = container.llmEngineManager.telemetry

    val embeddingTelemetry: StateFlow<EmbeddingTelemetry> = container.embeddingEngineManager.telemetry

    val indexedCount: StateFlow<Int> = fileDao.observeIndexedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val embeddedCount: StateFlow<Int> = fileDao.observeEmbeddedCount()
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
    val explorerFiles: StateFlow<List<FileIndexEntity>> = combine(
        _searchQuery,
        _selectedCategory,
        embeddingTelemetry,
        embeddedCount
    ) { query, cat, embedState, _ ->
        Triple(query.trim(), cat, embedState.status == EmbeddingRuntimeStatus.READY)
    }.flatMapLatest { (query, cat, embedderReady) ->
        val safeFts = FtsQuerySanitizer.toSafeFtsQuery(query)
        val catFilter = cat.takeIf { it != "ALL" }
        when {
            query.isNotBlank() && embedderReady -> flow {
                val hits = withContext(Dispatchers.IO) {
                    container.hybridSearchEngine.search(
                        rawQuery = query,
                        category = catFilter,
                        limit = 120
                    )
                }
                emit(hits.map { it.file })
            }
            safeFts.isNotEmpty() -> fileDao.observeSearchFts(safeFts, limit = 120)
            query.isNotBlank() -> flow {
                val hits = withContext(Dispatchers.IO) {
                    fileDao.searchFallbackLike(query, category = catFilter, limit = 120)
                }
                emit(hits)
            }
            cat != "ALL" -> fileDao.observeByCategory(cat, limit = 300)
            else -> fileDao.observeAllActiveFiles(limit = 400)
        }
    }.combine(_selectedCategory) { list, cat ->
        if (cat == "ALL") list else list.filter { it.fileCategory == cat }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        observeIndexerWork()
        observeSearchScreenInputs()
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

    // -------------------------------------------------------------------------
    // Performant Debounced + Paginated Search Screen Engine (Room FTS4 + Vector)
    // -------------------------------------------------------------------------

    @OptIn(FlowPreview::class)
    private fun observeSearchScreenInputs() {
        viewModelScope.launch {
            combine(
                _searchScreenState.mapSearchCriteria(),
                indexedCount,
                embeddedCount
            ) { criteria, _, _ -> criteria }
                .debounce(180L)
                .distinctUntilChanged()
                .collect {
                    executeSearchPage(reset = true)
                }
        }
    }

    private fun StateFlow<SearchScreenUiState>.mapSearchCriteria() =
        combine(
            this,
            embeddingTelemetry
        ) { state, embed ->
            SearchCriteria(
                query = state.query.trim(),
                category = state.category,
                dateRange = state.dateRange,
                useHybrid = state.useHybrid && embed.status == EmbeddingRuntimeStatus.READY
            )
        }

    private data class SearchCriteria(
        val query: String,
        val category: String,
        val dateRange: String,
        val useHybrid: Boolean
    )

    fun updateSearchScreenQuery(query: String) {
        _searchScreenState.update { it.copy(query = query, searchStoppedByUser = false) }
    }

    fun selectSearchScreenCategory(category: String) {
        _searchScreenState.update { it.copy(category = category, searchStoppedByUser = false) }
    }

    fun selectSearchScreenDateRange(dateRange: String) {
        _searchScreenState.update { it.copy(dateRange = dateRange, searchStoppedByUser = false) }
    }

    fun toggleSearchScreenHybrid(enabled: Boolean) {
        _searchScreenState.update { it.copy(useHybrid = enabled, searchStoppedByUser = false) }
    }

    fun triggerSearchNow() {
        _searchScreenState.update { it.copy(searchStoppedByUser = false) }
        executeSearchPage(reset = true)
    }

    fun stopActiveSearch() {
        searchJob?.cancel()
        _searchScreenState.update {
            it.copy(
                isSearching = false,
                isLoadingMore = false,
                searchStoppedByUser = true
            )
        }
    }

    fun loadMoreSearchResults() {
        val current = _searchScreenState.value
        if (current.isSearching || current.isLoadingMore || !current.hasMore) return
        executeSearchPage(reset = false)
    }

    private fun executeSearchPage(reset: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val state = _searchScreenState.value
            val offset = if (reset) 0 else state.results.size
            val query = state.query.trim()
            val catFilter = state.category.takeIf { it != "ALL" }
            val minMs = when (state.dateRange) {
                "TODAY" -> System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                "LAST_7_DAYS" -> System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                "LAST_30_DAYS" -> System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
                else -> null
            }

            _searchScreenState.update {
                it.copy(
                    isSearching = reset,
                    isLoadingMore = !reset,
                    searchStoppedByUser = false
                )
            }

            val startNs = System.nanoTime()
            try {
                val embedReady = embeddingTelemetry.value.status == EmbeddingRuntimeStatus.READY
                val shouldRunHybrid = reset && query.isNotBlank() && state.useHybrid && embedReady

                if (shouldRunHybrid) {
                    val hybridHits = withContext(Dispatchers.IO) {
                        container.hybridSearchEngine.search(
                            rawQuery = query,
                            category = catFilter,
                            minModifiedMs = minMs,
                            limit = SEARCH_PAGE_SIZE
                        )
                    }
                    val projected = hybridHits.map { hit ->
                        val f = hit.file
                        FileSearchListItem(
                            id = f.id,
                            filePath = f.filePath,
                            fileName = f.fileName,
                            parentDir = f.parentDir,
                            mimeType = f.mimeType,
                            fileCategory = f.fileCategory,
                            sizeBytes = f.sizeBytes,
                            lastModifiedMs = f.lastModifiedMs,
                            snippetPreview = f.extractedText.take(220),
                            hasEmbedding = f.embeddingVector != null
                        )
                    }
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1L)
                    _searchScreenState.update {
                        it.copy(
                            results = projected,
                            totalMatches = projected.size,
                            latencyMs = elapsedMs,
                            isSearching = false,
                            isLoadingMore = false,
                            hasMore = false,
                            usedHybridSemantic = true
                        )
                    }
                } else {
                    val ftsExpr = FtsQuerySanitizer.toSafeFtsQuery(query)
                    val (pageItems, totalCount) = withContext(Dispatchers.IO) {
                        when {
                            ftsExpr.isNotEmpty() -> {
                                val ftsCount = fileDao.countFtsMatches(ftsExpr, catFilter, minMs)
                                if (ftsCount > 0) {
                                    val rows = fileDao.searchFtsPaged(
                                        ftsQuery = ftsExpr,
                                        category = catFilter,
                                        minModifiedMs = minMs,
                                        limit = SEARCH_PAGE_SIZE,
                                        offset = offset
                                    )
                                    rows to ftsCount
                                } else {
                                    val likeCount = fileDao.countFallbackLikeMatches(query, catFilter, minMs)
                                    val rows = fileDao.searchFallbackLikePaged(
                                        likeTerm = query,
                                        category = catFilter,
                                        minModifiedMs = minMs,
                                        limit = SEARCH_PAGE_SIZE,
                                        offset = offset
                                    )
                                    rows to likeCount
                                }
                            }
                            else -> {
                                val likeCount = fileDao.countFallbackLikeMatches(query, catFilter, minMs)
                                val rows = fileDao.searchFallbackLikePaged(
                                    likeTerm = query,
                                    category = catFilter,
                                    minModifiedMs = minMs,
                                    limit = SEARCH_PAGE_SIZE,
                                    offset = offset
                                )
                                rows to likeCount
                            }
                        }
                    }

                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1L)
                    _searchScreenState.update { prev ->
                        val merged = if (reset) pageItems else (prev.results + pageItems).distinctBy { it.id }
                        prev.copy(
                            results = merged,
                            totalMatches = totalCount,
                            latencyMs = elapsedMs,
                            isSearching = false,
                            isLoadingMore = false,
                            hasMore = merged.size < totalCount,
                            usedHybridSemantic = false
                        )
                    }
                }
            } catch (_: CancellationException) {
                _searchScreenState.update { it.copy(isSearching = false, isLoadingMore = false) }
            }
        }
    }

    suspend fun loadFullFileForInspector(fileId: Long): FileIndexEntity? =
        withContext(Dispatchers.IO) { fileDao.getById(fileId) }

    // -------------------------------------------------------------------------
    // Scan Options: Single Media, Multiple Media, Full Storage, Auto-Scan New Media
    // -------------------------------------------------------------------------

    fun startStorageIndexing() {
        val request = OneTimeWorkRequestBuilder<StorageIndexerWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(StorageIndexerWorker.TAG_INDEXER_WORK)
            .build()
        workManager.enqueueUniqueWork(
            StorageIndexerWorker.UNIQUE_INDEXER_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun scanSelectedMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        mediaPickJob?.cancel()
        mediaPickJob = viewModelScope.launch(Dispatchers.IO) {
            val opType = OperationType.MEDIA_PICK_SCAN
            opControl.start(
                type = opType,
                total = uris.size,
                detail = "Preparing ${uris.size} selected item(s)..."
            )
            var scannedCount = 0
            try {
                for ((index, uri) in uris.withIndex()) {
                    opControl.checkpoint(opType)
                    opControl.updateProgress(
                        type = opType,
                        processed = index + 1,
                        total = uris.size,
                        detail = "OCR & Indexing item ${index + 1} / ${uris.size}"
                    )
                    val entity = container.mediaExtractor.indexSelectedUri(
                        uri = uri,
                        fileDao = fileDao,
                        embeddingManager = container.embeddingEngineManager
                    )
                    if (entity != null) {
                        scannedCount++
                    }
                }
                opControl.complete(opType, "Scanned $scannedCount selected media item(s)")
                _uiBannerMessage.value = "Indexed $scannedCount selected media item(s) into Local Vault."
            } catch (_: CancellationException) {
                opControl.stop(opType, "Selected media scan stopped ($scannedCount saved)")
            } catch (t: Throwable) {
                opControl.fail(opType, t.message ?: "Failed scanning selected media")
                _uiBannerMessage.value = "Selected media scan failed: ${t.message}"
            }
        }
    }

    fun setAutoScanNewMedia(enabled: Boolean) {
        viewModelScope.launch {
            container.preferences.setAutoScanNewMedia(enabled)
            container.mediaAutoScanScheduler.setAutoScanEnabled(enabled)
            _uiBannerMessage.value = if (enabled) {
                "Auto-scan ON: new photos, screenshots & videos will index automatically."
            } else {
                "Auto-scan OFF"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Unified Pause / Resume / Stop Operation Controls
    // -------------------------------------------------------------------------

    fun pauseOperation(type: OperationType) {
        when (type) {
            OperationType.AGENT_TASK -> container.agentOrchestrator.pauseAgent()
            OperationType.STORAGE_SCAN,
            OperationType.MEDIA_PICK_SCAN,
            OperationType.VECTORIZE,
            OperationType.MODEL_DOWNLOAD -> opControl.pause(type)
        }
    }

    fun resumeOperation(type: OperationType) {
        when (type) {
            OperationType.AGENT_TASK -> container.agentOrchestrator.resumeAgent()
            OperationType.MODEL_DOWNLOAD -> {
                val modelId = opControl.getState(OperationType.MODEL_DOWNLOAD).subjectId
                opControl.resume(type, "Resuming download...")
                if (modelId.isNotBlank()) {
                    viewModelScope.launch {
                        modelDao.getByModelId(modelId)?.let { downloadModel(it) }
                    }
                }
            }
            OperationType.STORAGE_SCAN,
            OperationType.MEDIA_PICK_SCAN,
            OperationType.VECTORIZE -> opControl.resume(type)
        }
    }

    fun stopOperation(type: OperationType) {
        when (type) {
            OperationType.STORAGE_SCAN -> {
                opControl.stop(type, "Storage scan stopped by user")
                workManager.cancelUniqueWork(StorageIndexerWorker.UNIQUE_INDEXER_WORK)
                _isIndexing.value = false
                _indexStatusMessage.value = "Scan stopped by user"
            }
            OperationType.MEDIA_PICK_SCAN -> {
                opControl.stop(type, "Selected media scan stopped")
                mediaPickJob?.cancel()
            }
            OperationType.VECTORIZE -> {
                opControl.stop(type, "Vectorization stopped")
                vectorizeJob?.cancel()
                _isVectorizing.value = false
                _vectorizeStatusMessage.value = "Vectorization stopped by user."
            }
            OperationType.MODEL_DOWNLOAD -> {
                val modelId = opControl.getState(OperationType.MODEL_DOWNLOAD).subjectId
                if (modelId.isNotBlank()) {
                    cancelDownload(modelId)
                } else {
                    opControl.stop(type, "Download stopped")
                }
            }
            OperationType.AGENT_TASK -> container.agentOrchestrator.stopAgent()
        }
    }

    private fun observeIndexerWork() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(StorageIndexerWorker.TAG_INDEXER_WORK).collect { infos ->
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
                        if (opControl.getState(OperationType.STORAGE_SCAN).status == OperationStatus.STOPPED) {
                            _indexStatusMessage.value = "Scan stopped by user"
                        } else {
                            _indexStatusMessage.value = "Indexing paused or stopped"
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Model Downloads, Pause/Resume/Stop, Local Import, and LLM / Embedder Loading
    // -------------------------------------------------------------------------

    fun downloadModel(model: ModelRecordEntity) {
        val workName = "download_${model.modelId}"
        opControl.start(
            type = OperationType.MODEL_DOWNLOAD,
            total = 100,
            detail = "Starting ${model.displayName}...",
            subjectId = model.modelId
        )
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to model.modelId))
            .addTag(workName)
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)

        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(workName).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                val pct = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                if (pct > 0) {
                    _downloadProgress.update { it + (model.modelId to pct) }
                }
                if (info.state.isFinished) {
                    val wasPaused = info.outputData.getBoolean(ModelDownloadWorker.KEY_PAUSED, false)
                    if (!wasPaused) {
                        _downloadProgress.update { it - model.modelId }
                    }
                    if (info.state == WorkInfo.State.FAILED) {
                        val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                        if (!err.isNullOrBlank()) {
                            _uiBannerMessage.value = err
                        }
                    }
                }
            }
        }
    }

    fun pauseModelDownload(model: ModelRecordEntity) {
        opControl.pause(OperationType.MODEL_DOWNLOAD, "Pausing ${model.displayName}...")
    }

    fun resumeModelDownload(model: ModelRecordEntity) {
        opControl.resume(OperationType.MODEL_DOWNLOAD, "Resuming ${model.displayName}...")
        downloadModel(model)
    }

    fun cancelDownload(modelId: String) {
        opControl.stop(OperationType.MODEL_DOWNLOAD, "Download stopped")
        workManager.cancelUniqueWork("download_$modelId")
        _downloadProgress.update { it - modelId }
        viewModelScope.launch(Dispatchers.IO) {
            val record = modelDao.getByModelId(modelId)
            if (record != null) {
                File(ModelCatalog.modelsDir(getApplication()), "${record.fileName}.part").delete()
            }
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
                    val modelRole = if (safeName.contains("embed", ignoreCase = true)) "EMBEDDING" else "LLM"
                    val record = ModelRecordEntity(
                        modelId = modelId,
                        displayName = safeName.substringBeforeLast('.'),
                        description = if (modelRole == "EMBEDDING") {
                            "Locally imported EmbeddingGemma / vector encoder in private app sandbox"
                        } else {
                            "Locally imported model inside private app sandbox"
                        },
                        downloadUrl = "",
                        fileName = safeName,
                        localPath = destFile.absolutePath,
                        expectedSizeBytes = destFile.length(),
                        downloadedBytes = destFile.length(),
                        format = format,
                        modelRole = modelRole,
                        recommendedBackend = if (modelRole == "EMBEDDING") "CPU" else "GPU",
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

    fun loadEmbeddingModel(model: ModelRecordEntity) {
        viewModelScope.launch {
            val delegate = engineSettings.value.delegate
            val res = container.embeddingEngineManager.loadModel(
                modelId = model.modelId,
                displayName = model.displayName,
                modelPath = model.localPath,
                requestedDelegate = delegate
            )
            res.onSuccess {
                _uiBannerMessage.value = "Loaded embedder: ${model.displayName}"
                buildMissingEmbeddings()
            }.onFailure { err ->
                _uiBannerMessage.value = "Embedder load error: ${err.message}"
            }
        }
    }

    fun unloadEmbeddingModel() {
        opControl.stop(OperationType.VECTORIZE, "Embedder unloaded")
        vectorizeJob?.cancel()
        _isVectorizing.value = false
        viewModelScope.launch {
            container.embeddingEngineManager.unloadModel()
            _vectorizeStatusMessage.value = ""
        }
    }

    fun buildMissingEmbeddings() {
        val currentEmbedder = embeddingTelemetry.value
        if (currentEmbedder.status != EmbeddingRuntimeStatus.READY || currentEmbedder.loadedModelId.isBlank()) {
            _uiBannerMessage.value = "Load EmbeddingGemma in the Models tab first."
            return
        }
        if (_isVectorizing.value) return

        vectorizeJob = viewModelScope.launch(Dispatchers.IO) {
            _isVectorizing.value = true
            val opType = OperationType.VECTORIZE
            opControl.start(opType, total = 0, detail = "Preparing EmbeddingGemma vectorization...")
            var encodedCount = 0
            try {
                while (true) {
                    opControl.checkpoint(opType)
                    val modelId = embeddingTelemetry.value.loadedModelId
                    if (embeddingTelemetry.value.status != EmbeddingRuntimeStatus.READY || modelId.isBlank()) break
                    val pending = fileDao.getFilesNeedingEmbeddings(modelId = modelId, limit = 100)
                    if (pending.isEmpty()) break

                    val totalExpected = encodedCount + pending.size
                    _vectorizeStatusMessage.value = "Vectorizing ${pending.size} indexed files ($encodedCount done)..."
                    for (file in pending) {
                        opControl.checkpoint(opType)
                        if (embeddingTelemetry.value.status != EmbeddingRuntimeStatus.READY) break
                        val imageBytes = if (file.fileCategory == "IMAGE" && !file.filePath.startsWith("content://")) {
                            container.mediaExtractor.loadDownsampledImageBytesFromFile(File(file.filePath))
                        } else {
                            null
                        }
                        val vec = container.embeddingEngineManager.embedDocument(
                            title = file.fileName,
                            content = file.extractedText,
                            imageBytes = imageBytes
                        )
                        if (vec != null && vec.isNotEmpty()) {
                            fileDao.updateFileEmbedding(
                                fileId = file.id,
                                vector = VectorMath.floatArrayToBytes(vec),
                                modelId = modelId
                            )
                            encodedCount++
                            opControl.updateProgress(
                                type = opType,
                                processed = encodedCount,
                                total = totalExpected,
                                detail = "Vectorizing ${file.fileName}"
                            )
                            if (encodedCount % 5 == 0) {
                                _vectorizeStatusMessage.value = "Vectorized $encodedCount file(s) with EmbeddingGemma..."
                            }
                        }
                    }
                }
                _vectorizeStatusMessage.value = if (encodedCount > 0) {
                    "Vectorized $encodedCount file(s) for hybrid semantic search."
                } else {
                    "All indexed files are vectorized."
                }
                opControl.complete(opType, _vectorizeStatusMessage.value)
            } catch (_: CancellationException) {
                opControl.stop(opType, "Vectorization stopped ($encodedCount saved)")
                _vectorizeStatusMessage.value = "Vectorization stopped ($encodedCount saved)."
            } catch (t: Throwable) {
                opControl.fail(opType, t.message ?: "Vectorization failed")
                _vectorizeStatusMessage.value = "Vectorization error: ${t.message}"
            } finally {
                _isVectorizing.value = false
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
            if (embeddingTelemetry.value.loadedModelId == model.modelId) {
                container.embeddingEngineManager.unloadModel()
            }
            if (model.localPath.isNotBlank()) {
                File(model.localPath).delete()
            }
            File(ModelCatalog.modelsDir(getApplication()), "${model.fileName}.part").delete()
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
            val currentEmbedTelemetry = embeddingTelemetry.value
            if (currentEmbedTelemetry.loadedModelId.isNotBlank() && currentEmbedTelemetry.loadedModelPath.isNotBlank()) {
                val embedModel = modelDao.getByModelId(currentEmbedTelemetry.loadedModelId)
                if (embedModel != null) {
                    container.embeddingEngineManager.loadModel(
                        modelId = embedModel.modelId,
                        displayName = embedModel.displayName,
                        modelPath = embedModel.localPath,
                        requestedDelegate = delegate
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
            if (file.filePath.startsWith("content://")) {
                withContext(Dispatchers.IO) {
                    fileDao.deleteByPath(file.filePath)
                }
                _uiBannerMessage.value = "Removed '${file.fileName}' from vault index."
            } else {
                val report = container.fileOpsExecutor.executeSafeTrash(
                    rawPaths = listOf(file.filePath),
                    batchId = UUID.randomUUID().toString()
                )
                _uiBannerMessage.value = report.message
            }
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

    override fun onCleared() {
        super.onCleared()
        vectorizeJob?.cancel()
        mediaPickJob?.cancel()
        searchJob?.cancel()
        container.llmEngineManager.shutdown()
        container.embeddingEngineManager.shutdown()
    }

    companion object {
        private const val SEARCH_PAGE_SIZE = 60

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return EdgeClawViewModel(application) as T
                }
            }
    }
}
