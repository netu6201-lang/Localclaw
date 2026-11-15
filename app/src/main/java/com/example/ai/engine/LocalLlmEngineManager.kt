package com.example.ai.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.example.data.preferences.HardwareDelegate
import com.example.data.preferences.LlmProviderMode
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

enum class EngineRuntimeStatus {
    UNLOADED,
    LOADING,
    READY,
    GENERATING,
    ERROR
}

data class LlmTelemetry(
    val status: EngineRuntimeStatus = EngineRuntimeStatus.UNLOADED,
    val loadedModelId: String = "",
    val loadedModelName: String = "No model loaded",
    val loadedModelPath: String = "",
    val requestedDelegate: HardwareDelegate = HardwareDelegate.GPU,
    val activeDelegate: HardwareDelegate = HardwareDelegate.GPU,
    val providerMode: LlmProviderMode = LlmProviderMode.AUTO_FALLBACK,
    val isUsingOllama: Boolean = false,
    val ollamaBaseUrl: String = "http://127.0.0.1:11434",
    val ollamaModelName: String = "gemma3:4b",
    val tokensPerSecond: Float = 0f,
    val totalTokensGenerated: Int = 0,
    val ttftMs: Long = 0L,
    val appPssRamMb: Int = 0,
    val systemAvailableRamMb: Int = 0,
    val systemTotalRamMb: Int = 0,
    val statusNote: String = "Select an offline Gemma model or configure Ollama API in Settings"
)

class LocalLlmEngineManager(private val context: Context) {

    private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EdgeClaw-LLM-Engine").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val engineDispatcher = singleThreadExecutor.asCoroutineDispatcher()
    private val managerScope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val mutex = Mutex()

    // LiteRT-LM handles
    private var liteRtEngine: Engine? = null
    private var liteRtConversation: Conversation? = null

    // MediaPipe Tasks GenAI handles (.task fallback/dual support)
    private var mediaPipeInference: LlmInference? = null
    private var mediaPipeSession: LlmInferenceSession? = null

    // Ollama HTTP API fallback & alternative LLM backend
    private val ollamaClient = OllamaApiClient()
    private val ollamaHistory = mutableListOf<OllamaChatMessage>()

    @Volatile
    private var activeFormat: String = ""

    @Volatile
    private var configuredProviderMode: LlmProviderMode = LlmProviderMode.AUTO_FALLBACK

    @Volatile
    private var configuredOllamaUrl: String = "http://127.0.0.1:11434"

    @Volatile
    private var configuredOllamaModel: String = "gemma3:4b"

    @Volatile
    private var configuredOllamaApiKey: String = ""

    @Volatile
    private var configuredMaxTokens: Int = 1024

    @Volatile
    private var configuredTemperature: Float = 0.25f

    private val _telemetry = MutableStateFlow(LlmTelemetry())
    val telemetry: StateFlow<LlmTelemetry> = _telemetry.asStateFlow()

    init {
        refreshMemoryTelemetry()
    }

    fun configureProvider(
        mode: LlmProviderMode,
        ollamaBaseUrl: String,
        ollamaModelName: String,
        ollamaApiKey: String = "",
        maxTokens: Int = 1024,
        temperature: Float = 0.25f
    ) {
        configuredProviderMode = mode
        configuredOllamaUrl = ollamaBaseUrl.trim().trimEnd('/').ifBlank { "http://127.0.0.1:11434" }
        configuredOllamaModel = ollamaModelName.trim().ifBlank { "gemma3:4b" }
        configuredOllamaApiKey = ollamaApiKey.trim()
        configuredMaxTokens = maxTokens
        configuredTemperature = temperature

        val localLoaded = hasLocalModelLoaded()
        val usingOllama = (mode == LlmProviderMode.OLLAMA) ||
            (mode == LlmProviderMode.AUTO_FALLBACK && !localLoaded && configuredOllamaModel.isNotBlank())

        _telemetry.update { current ->
            val updatedModelName = when {
                localLoaded -> current.loadedModelName
                mode == LlmProviderMode.OLLAMA -> "Ollama • $configuredOllamaModel"
                else -> "No model loaded"
            }
            current.copy(
                providerMode = mode,
                ollamaBaseUrl = configuredOllamaUrl,
                ollamaModelName = configuredOllamaModel,
                loadedModelName = updatedModelName,
                isUsingOllama = usingOllama,
                statusNote = when {
                    mode == LlmProviderMode.OLLAMA ->
                        "Ollama API active (${configuredOllamaModel} @ ${configuredOllamaUrl})"
                    localLoaded -> current.statusNote
                    mode == LlmProviderMode.AUTO_FALLBACK ->
                        "Standby • Will use Ollama ($configuredOllamaModel) if offline LLM unavailable"
                    else -> "Select or download an offline .litertlm / .task model"
                }
            )
        }
    }

    suspend fun testOllamaConnection(
        baseUrl: String = configuredOllamaUrl,
        apiKey: String = configuredOllamaApiKey
    ): Result<List<String>> = ollamaClient.fetchAvailableModels(baseUrl, apiKey)

    fun hasLocalModelLoaded(): Boolean =
        (activeFormat == "LITERTLM" && liteRtConversation != null) ||
            (activeFormat == "TASK" && mediaPipeSession != null)

    fun isOllamaConfigured(): Boolean =
        configuredOllamaUrl.isNotBlank() && configuredOllamaModel.isNotBlank()

    fun isInferenceAvailable(): Boolean = when (configuredProviderMode) {
        LlmProviderMode.ON_DEVICE -> hasLocalModelLoaded() && _telemetry.value.status == EngineRuntimeStatus.READY
        LlmProviderMode.OLLAMA -> isOllamaConfigured()
        LlmProviderMode.AUTO_FALLBACK -> (hasLocalModelLoaded() && _telemetry.value.status == EngineRuntimeStatus.READY) || isOllamaConfigured()
    }

    fun refreshMemoryTelemetry() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val sysInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(sysInfo)

        val nativeMb = (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).toInt()
        val pssMb = Debug.getPss().takeIf { it > 0 }?.let { (it / 1024L).toInt() } ?: nativeMb
        val availMb = (sysInfo.availMem / (1024L * 1024L)).toInt()
        val totalMb = (sysInfo.totalMem / (1024L * 1024L)).toInt()

        _telemetry.update {
            it.copy(
                appPssRamMb = pssMb,
                systemAvailableRamMb = availMb,
                systemTotalRamMb = totalMb
            )
        }
    }

    suspend fun loadModel(
        modelId: String,
        displayName: String,
        modelPath: String,
        requestedDelegate: HardwareDelegate,
        systemInstruction: String,
        maxTokens: Int = 1024,
        topK: Int = 32,
        temperature: Float = 0.25f
    ): Result<Unit> = withContext(engineDispatcher) {
        mutex.withLock {
            val file = File(modelPath)
            if (!file.exists() || !file.isFile || file.length() < 1024L) {
                val msg = "Model file missing or incomplete: $modelPath"
                _telemetry.update {
                    it.copy(
                        status = EngineRuntimeStatus.ERROR,
                        statusNote = msg
                    )
                }
                return@withLock Result.failure(IllegalStateException(msg))
            }

            if (_telemetry.value.status == EngineRuntimeStatus.READY &&
                _telemetry.value.loadedModelPath == modelPath &&
                _telemetry.value.requestedDelegate == requestedDelegate
            ) {
                return@withLock Result.success(Unit)
            }

            releaseInternal()

            _telemetry.update {
                it.copy(
                    status = EngineRuntimeStatus.LOADING,
                    loadedModelId = modelId,
                    loadedModelName = displayName,
                    loadedModelPath = modelPath,
                    requestedDelegate = requestedDelegate,
                    isUsingOllama = configuredProviderMode == LlmProviderMode.OLLAMA,
                    statusNote = "Initializing $displayName on ${requestedDelegate.label}..."
                )
            }
            refreshMemoryTelemetry()

            val isTaskBundle = modelPath.endsWith(".task", ignoreCase = true)
            return@withLock try {
                if (isTaskBundle) {
                    initializeMediaPipeTask(
                        modelPath = modelPath,
                        requestedDelegate = requestedDelegate,
                        maxTokens = maxTokens,
                        topK = topK,
                        temperature = temperature
                    )
                } else {
                    initializeLiteRtLm(
                        modelPath = modelPath,
                        requestedDelegate = requestedDelegate,
                        systemInstruction = systemInstruction,
                        topK = topK,
                        temperature = temperature
                    )
                }
                refreshMemoryTelemetry()
                Result.success(Unit)
            } catch (t: Throwable) {
                releaseInternal()
                _telemetry.update {
                    it.copy(
                        status = EngineRuntimeStatus.ERROR,
                        statusNote = "Load failed: ${t.message ?: t::class.simpleName}"
                    )
                }
                Result.failure(t)
            }
        }
    }

    private fun initializeLiteRtLm(
        modelPath: String,
        requestedDelegate: HardwareDelegate,
        systemInstruction: String,
        topK: Int,
        temperature: Float
    ) {
        val fallbackOrder = when (requestedDelegate) {
            HardwareDelegate.NPU -> listOf(HardwareDelegate.NPU, HardwareDelegate.GPU, HardwareDelegate.CPU)
            HardwareDelegate.GPU -> listOf(HardwareDelegate.GPU, HardwareDelegate.CPU)
            HardwareDelegate.CPU -> listOf(HardwareDelegate.CPU)
        }

        var lastError: Throwable? = null
        for (candidate in fallbackOrder) {
            var candidateEngine: Engine? = null
            try {
                val backend = when (candidate) {
                    HardwareDelegate.NPU -> Backend.NPU(
                        nativeLibraryDir = context.applicationInfo.nativeLibraryDir
                    )
                    HardwareDelegate.GPU -> Backend.GPU()
                    HardwareDelegate.CPU -> Backend.CPU()
                }
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    cacheDir = context.cacheDir.absolutePath
                )
                candidateEngine = Engine(config)
                candidateEngine.initialize()

                val conversation = candidateEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemInstruction),
                        samplerConfig = SamplerConfig(
                            topK = topK,
                            topP = 0.92,
                            temperature = temperature.toDouble()
                        )
                    )
                )
                liteRtEngine = candidateEngine
                liteRtConversation = conversation
                activeFormat = "LITERTLM"

                val note = if (candidate == requestedDelegate) {
                    "Ready on ${candidate.label} (${candidate.subtitle})"
                } else {
                    "${requestedDelegate.label} unavailable on SoC; fell back to ${candidate.label}"
                }
                _telemetry.update {
                    it.copy(
                        status = EngineRuntimeStatus.READY,
                        activeDelegate = candidate,
                        isUsingOllama = configuredProviderMode == LlmProviderMode.OLLAMA,
                        statusNote = note
                    )
                }
                return
            } catch (t: Throwable) {
                lastError = t
                runCatching {
                    if (liteRtConversation != null) {
                        liteRtConversation?.close()
                        liteRtConversation = null
                    }
                }
                candidateEngine = null
            }
        }
        throw lastError ?: IllegalStateException("Unable to initialize LiteRT-LM engine")
    }

    private fun initializeMediaPipeTask(
        modelPath: String,
        requestedDelegate: HardwareDelegate,
        maxTokens: Int,
        topK: Int,
        temperature: Float
    ) {
        val candidates = when (requestedDelegate) {
            HardwareDelegate.NPU, HardwareDelegate.GPU ->
                listOf(HardwareDelegate.GPU, HardwareDelegate.CPU)
            HardwareDelegate.CPU ->
                listOf(HardwareDelegate.CPU)
        }

        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val mpBackend = when (candidate) {
                    HardwareDelegate.GPU, HardwareDelegate.NPU -> LlmInference.Backend.GPU
                    HardwareDelegate.CPU -> LlmInference.Backend.CPU
                }
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(maxTokens)
                    .setPreferredBackend(mpBackend)
                    .build()

                val inference = LlmInference.createFromOptions(context, options)
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(topK)
                    .setTemperature(temperature)
                    .build()
                val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)

                mediaPipeInference = inference
                mediaPipeSession = session
                activeFormat = "TASK"

                val note = if (candidate == requestedDelegate) {
                    "MediaPipe GenAI Ready on ${candidate.label}"
                } else {
                    "${requestedDelegate.label} routed to MediaPipe ${candidate.label}"
                }
                _telemetry.update {
                    it.copy(
                        status = EngineRuntimeStatus.READY,
                        activeDelegate = candidate,
                        isUsingOllama = configuredProviderMode == LlmProviderMode.OLLAMA,
                        statusNote = note
                    )
                }
                return
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Unable to initialize MediaPipe LlmInference")
    }

    suspend fun resetConversation(systemInstruction: String, topK: Int = 32, temperature: Float = 0.25f) {
        synchronized(ollamaHistory) {
            ollamaHistory.clear()
            ollamaHistory.add(OllamaChatMessage(role = "system", content = systemInstruction))
        }
        withContext(engineDispatcher) {
            mutex.withLock {
                if (activeFormat == "LITERTLM" && liteRtEngine != null) {
                    liteRtConversation?.close()
                    liteRtConversation = null
                    liteRtConversation = liteRtEngine?.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(systemInstruction),
                            samplerConfig = SamplerConfig(
                                topK = topK,
                                topP = 0.92,
                                temperature = temperature.toDouble()
                            )
                        )
                    )
                } else if (activeFormat == "TASK" && mediaPipeInference != null) {
                    mediaPipeSession?.close()
                    val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(topK)
                        .setTemperature(temperature)
                        .build()
                    mediaPipeSession = LlmInferenceSession.createFromOptions(
                        requireNotNull(mediaPipeInference),
                        sessionOptions
                    )
                }
            }
        }
    }

    fun generateStreaming(prompt: String): Flow<String> {
        val localReady = hasLocalModelLoaded() && _telemetry.value.status == EngineRuntimeStatus.READY
        return when (configuredProviderMode) {
            LlmProviderMode.OLLAMA -> generateOllamaStreaming(prompt)
            LlmProviderMode.ON_DEVICE -> generateLocalStreaming(prompt)
            LlmProviderMode.AUTO_FALLBACK -> {
                if (localReady) {
                    flow {
                        var emittedAny = false
                        try {
                            generateLocalStreaming(prompt).collect { chunk ->
                                emittedAny = true
                                emit(chunk)
                            }
                        } catch (t: Throwable) {
                            if (!emittedAny && t !is CancellationException && isOllamaConfigured()) {
                                generateOllamaStreaming(prompt).collect { emit(it) }
                            } else {
                                throw t
                            }
                        }
                    }
                } else {
                    generateOllamaStreaming(prompt)
                }
            }
        }
    }

    private fun generateOllamaStreaming(prompt: String): Flow<String> = flow {
        if (!isOllamaConfigured()) {
            throw IllegalStateException("Ollama URL or model name not configured. Configure Ollama in Settings tab.")
        }

        val messagesSnapshot = synchronized(ollamaHistory) {
            ollamaHistory.add(OllamaChatMessage(role = "user", content = prompt))
            ollamaHistory.toList()
        }

        val previousStatus = _telemetry.value.status
        val previousModelName = _telemetry.value.loadedModelName
        val previousModelPath = _telemetry.value.loadedModelPath

        _telemetry.update {
            it.copy(
                status = EngineRuntimeStatus.GENERATING,
                isUsingOllama = true,
                loadedModelName = "Ollama • $configuredOllamaModel",
                tokensPerSecond = 0f,
                totalTokensGenerated = 0,
                ttftMs = 0L,
                statusNote = "Streaming via Ollama ($configuredOllamaUrl)"
            )
        }

        val startMs = System.currentTimeMillis()
        var firstTokenMs = 0L
        var chunkCount = 0
        val assistantReply = StringBuilder()

        try {
            ollamaClient.streamChat(
                baseUrl = configuredOllamaUrl,
                model = configuredOllamaModel,
                apiKey = configuredOllamaApiKey,
                messages = messagesSnapshot,
                temperature = configuredTemperature,
                maxTokens = configuredMaxTokens
            ).collect { chunk ->
                if (chunk.isNotEmpty()) {
                    assistantReply.append(chunk)
                    val now = System.currentTimeMillis()
                    if (firstTokenMs == 0L) {
                        firstTokenMs = now - startMs
                    }
                    val approxTokens = chunk.split(Regex("\\s+")).count { it.isNotEmpty() }.coerceAtLeast(1)
                    chunkCount += approxTokens
                    val elapsedSec = ((now - startMs).coerceAtLeast(1L)) / 1000f
                    _telemetry.update {
                        it.copy(
                            totalTokensGenerated = chunkCount,
                            ttftMs = firstTokenMs,
                            tokensPerSecond = (chunkCount / elapsedSec)
                        )
                    }
                    emit(chunk)
                }
            }
            synchronized(ollamaHistory) {
                if (assistantReply.isNotEmpty()) {
                    ollamaHistory.add(OllamaChatMessage(role = "assistant", content = assistantReply.toString()))
                }
            }
            refreshMemoryTelemetry()
            _telemetry.update {
                it.copy(
                    status = if (hasLocalModelLoaded()) EngineRuntimeStatus.READY else EngineRuntimeStatus.UNLOADED,
                    loadedModelName = if (hasLocalModelLoaded()) previousModelName else "Ollama • $configuredOllamaModel",
                    loadedModelPath = previousModelPath,
                    statusNote = "Ollama ready ($configuredOllamaModel)"
                )
            }
        } catch (e: CancellationException) {
            _telemetry.update {
                it.copy(
                    status = if (hasLocalModelLoaded()) EngineRuntimeStatus.READY else previousStatus,
                    statusNote = "Ollama request cancelled"
                )
            }
            throw e
        } catch (t: Throwable) {
            _telemetry.update {
                it.copy(
                    status = if (hasLocalModelLoaded()) EngineRuntimeStatus.READY else EngineRuntimeStatus.ERROR,
                    isUsingOllama = true,
                    statusNote = "Ollama error: ${t.message ?: t::class.simpleName}"
                )
            }
            throw t
        }
    }

    private fun generateLocalStreaming(prompt: String): Flow<String> = callbackFlow {
        val conv = liteRtConversation
        val mpSession = mediaPipeSession

        if (_telemetry.value.status != EngineRuntimeStatus.READY) {
            close(IllegalStateException("No local model loaded. Load a Gemma model in Models or enable Ollama API in Settings."))
            return@callbackFlow
        }

        _telemetry.update {
            it.copy(
                status = EngineRuntimeStatus.GENERATING,
                isUsingOllama = false,
                tokensPerSecond = 0f,
                totalTokensGenerated = 0,
                ttftMs = 0L
            )
        }

        val startMs = System.currentTimeMillis()
        var firstTokenMs = 0L
        var chunkCount = 0

        try {
            if (activeFormat == "LITERTLM" && conv != null) {
                conv.sendMessageAsync(prompt).collect { message ->
                    val chunk = message.toString()
                    if (chunk.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        if (firstTokenMs == 0L) {
                            firstTokenMs = now - startMs
                        }
                        val approxTokens = chunk.split(Regex("\\s+")).count { it.isNotEmpty() }.coerceAtLeast(1)
                        chunkCount += approxTokens
                        val elapsedSec = ((now - startMs).coerceAtLeast(1L)) / 1000f
                        _telemetry.update {
                            it.copy(
                                totalTokensGenerated = chunkCount,
                                ttftMs = firstTokenMs,
                                tokensPerSecond = (chunkCount / elapsedSec)
                            )
                        }
                        trySend(chunk)
                    }
                }
                refreshMemoryTelemetry()
                _telemetry.update { it.copy(status = EngineRuntimeStatus.READY) }
                close()
            } else if (activeFormat == "TASK" && mpSession != null) {
                mpSession.addQueryChunk(prompt)
                mpSession.generateResponseAsync { partialResult, done ->
                    if (!partialResult.isNullOrEmpty()) {
                        val now = System.currentTimeMillis()
                        if (firstTokenMs == 0L) firstTokenMs = now - startMs
                        val approxTokens = partialResult.split(Regex("\\s+")).count { it.isNotEmpty() }.coerceAtLeast(1)
                        chunkCount += approxTokens
                        val elapsedSec = ((now - startMs).coerceAtLeast(1L)) / 1000f
                        _telemetry.update {
                            it.copy(
                                totalTokensGenerated = chunkCount,
                                ttftMs = firstTokenMs,
                                tokensPerSecond = (chunkCount / elapsedSec)
                            )
                        }
                        trySend(partialResult)
                    }
                    if (done) {
                        refreshMemoryTelemetry()
                        _telemetry.update { it.copy(status = EngineRuntimeStatus.READY) }
                        close()
                    }
                }
            } else {
                close(IllegalStateException("Inference session unavailable"))
            }
        } catch (e: CancellationException) {
            _telemetry.update { it.copy(status = EngineRuntimeStatus.READY) }
            throw e
        } catch (t: Throwable) {
            _telemetry.update {
                it.copy(
                    status = EngineRuntimeStatus.ERROR,
                    statusNote = "Inference error: ${t.message}"
                )
            }
            close(t)
        }

        awaitClose {
            if (_telemetry.value.status == EngineRuntimeStatus.GENERATING) {
                runCatching { liteRtConversation?.cancelProcess() }
                _telemetry.update { it.copy(status = EngineRuntimeStatus.READY) }
            }
        }
    }.flowOn(engineDispatcher)

    suspend fun unloadModel() = withContext(engineDispatcher) {
        mutex.withLock {
            releaseInternal()
        }
    }

    private suspend fun releaseInternal() {
        while (_telemetry.value.status == EngineRuntimeStatus.GENERATING && !(_telemetry.value.isUsingOllama)) {
            runCatching { liteRtConversation?.cancelProcess() }
            delay(80)
        }
        runCatching { liteRtConversation?.close() }
        liteRtConversation = null
        runCatching { liteRtEngine?.close() }
        liteRtEngine = null

        runCatching { mediaPipeSession?.close() }
        mediaPipeSession = null
        runCatching { mediaPipeInference?.close() }
        mediaPipeInference = null

        activeFormat = ""
        refreshMemoryTelemetry()
        _telemetry.update {
            it.copy(
                status = EngineRuntimeStatus.UNLOADED,
                loadedModelId = "",
                loadedModelName = if (configuredProviderMode == LlmProviderMode.OLLAMA) {
                    "Ollama • $configuredOllamaModel"
                } else {
                    "No model loaded"
                },
                loadedModelPath = "",
                isUsingOllama = configuredProviderMode == LlmProviderMode.OLLAMA,
                statusNote = if (configuredProviderMode == LlmProviderMode.OLLAMA) {
                    "Ollama API active ($configuredOllamaModel)"
                } else {
                    "Engine unloaded"
                }
            )
        }
    }

    fun shutdown() {
        managerScope.launch {
            mutex.withLock {
                releaseInternal()
            }
            singleThreadExecutor.shutdown()
        }
    }
}
