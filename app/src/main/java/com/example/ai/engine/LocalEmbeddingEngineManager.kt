package com.example.ai.engine

import android.content.Context
import com.example.data.preferences.HardwareDelegate
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

enum class EmbeddingRuntimeStatus {
    UNLOADED,
    LOADING,
    READY,
    EMBEDDING,
    ERROR
}

data class EmbeddingTelemetry(
    val status: EmbeddingRuntimeStatus = EmbeddingRuntimeStatus.UNLOADED,
    val loadedModelId: String = "",
    val loadedModelName: String = "No embedder loaded",
    val loadedModelPath: String = "",
    val activeDelegate: HardwareDelegate = HardwareDelegate.CPU,
    val vectorDimensions: Int = 0,
    val lastLatencyMs: Long = 0L,
    val statusNote: String = "Load EmbeddingGemma in Models tab for semantic vector search"
)

class LocalEmbeddingEngineManager(private val context: Context) {

    private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EdgeClaw-Embedding-Engine").apply { priority = Thread.NORM_PRIORITY }
    }
    private val engineDispatcher = singleThreadExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val mutex = Mutex()

    private var embeddingEngine: EmbeddingEngine? = null

    private val _telemetry = MutableStateFlow(EmbeddingTelemetry())
    val telemetry: StateFlow<EmbeddingTelemetry> = _telemetry.asStateFlow()

    private val defaultOptions = EmbeddingOptions(
        normalize = true,
        insertSpecialTokens = true
    )

    suspend fun loadModel(
        modelId: String,
        displayName: String,
        modelPath: String,
        requestedDelegate: HardwareDelegate
    ): Result<Unit> = withContext(engineDispatcher) {
        mutex.withLock {
            val file = File(modelPath)
            if (!file.exists() || !file.isFile || file.length() < 1024L) {
                val msg = "Embedding model file missing: $modelPath"
                _telemetry.update { it.copy(status = EmbeddingRuntimeStatus.ERROR, statusNote = msg) }
                return@withLock Result.failure(IllegalStateException(msg))
            }

            if (_telemetry.value.status == EmbeddingRuntimeStatus.READY &&
                _telemetry.value.loadedModelPath == modelPath &&
                _telemetry.value.activeDelegate == requestedDelegate
            ) {
                return@withLock Result.success(Unit)
            }

            releaseInternal()

            _telemetry.update {
                it.copy(
                    status = EmbeddingRuntimeStatus.LOADING,
                    loadedModelId = modelId,
                    loadedModelName = displayName,
                    loadedModelPath = modelPath,
                    statusNote = "Initializing EmbeddingGemma on ${requestedDelegate.label}..."
                )
            }

            val fallbackOrder = when (requestedDelegate) {
                HardwareDelegate.NPU -> listOf(HardwareDelegate.NPU, HardwareDelegate.GPU, HardwareDelegate.CPU)
                HardwareDelegate.GPU -> listOf(HardwareDelegate.GPU, HardwareDelegate.CPU)
                HardwareDelegate.CPU -> listOf(HardwareDelegate.CPU)
            }

            var lastError: Throwable? = null
            for (candidate in fallbackOrder) {
                try {
                    val backend = when (candidate) {
                        HardwareDelegate.NPU -> Backend.NPU(
                            nativeLibraryDir = context.applicationInfo.nativeLibraryDir
                        )
                        HardwareDelegate.GPU -> Backend.GPU()
                        HardwareDelegate.CPU -> Backend.CPU()
                    }
                    val config = EmbeddingEngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        cacheDir = context.cacheDir.absolutePath
                    )
                    val engine = EmbeddingEngine(config)
                    engine.initialize()

                    // Warm-up inference to verify output dimensions (e.g. 768 for EmbeddingGemma)
                    val warmup = engine.computeEmbedding(
                        listOf(InputData.Text("task: search query | text: warmup")),
                        defaultOptions
                    )
                    val dims = warmup.embedding.size

                    embeddingEngine = engine
                    _telemetry.update {
                        it.copy(
                            status = EmbeddingRuntimeStatus.READY,
                            activeDelegate = candidate,
                            vectorDimensions = dims,
                            statusNote = "EmbeddingGemma Ready (${dims}-dim) on ${candidate.label}"
                        )
                    }
                    return@withLock Result.success(Unit)
                } catch (t: Throwable) {
                    lastError = t
                }
            }

            releaseInternal()
            _telemetry.update {
                it.copy(
                    status = EmbeddingRuntimeStatus.ERROR,
                    statusNote = "Embedder load failed: ${lastError?.message ?: "Unsupported model"}"
                )
            }
            Result.failure(lastError ?: IllegalStateException("Failed to initialize EmbeddingEngine"))
        }
    }

    suspend fun embedQuery(query: String): FloatArray? = withContext(engineDispatcher) {
        mutex.withLock {
            val engine = embeddingEngine ?: return@withLock null
            val start = System.currentTimeMillis()
            return@withLock runCatching {
                _telemetry.update { it.copy(status = EmbeddingRuntimeStatus.EMBEDDING) }
                val prompt = "task: search query | text: ${query.trim().take(512)}"
                val result = engine.computeEmbedding(
                    listOf(InputData.Text(prompt)),
                    defaultOptions
                )
                val latency = System.currentTimeMillis() - start
                _telemetry.update {
                    it.copy(
                        status = EmbeddingRuntimeStatus.READY,
                        lastLatencyMs = latency
                    )
                }
                result.embedding
            }.onFailure {
                _telemetry.update { it.copy(status = EmbeddingRuntimeStatus.READY) }
            }.getOrNull()
        }
    }

    suspend fun embedDocument(
        title: String,
        content: String,
        imageBytes: ByteArray? = null
    ): FloatArray? = withContext(engineDispatcher) {
        mutex.withLock {
            val engine = embeddingEngine ?: return@withLock null
            val start = System.currentTimeMillis()

            // Try multimodal image embedding first if imageBytes supplied (e.g., EmbeddingGemma 2),
            // otherwise fall back seamlessly to text embedding over title + OCR content.
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val imageVec = runCatching {
                    val inputs = buildList {
                        add(InputData.Image(imageBytes))
                        val combined = "$title ${content.take(400)}".trim()
                        if (combined.isNotEmpty()) {
                            add(InputData.Text("task: search result | text: $combined"))
                        }
                    }
                    engine.computeEmbedding(inputs, defaultOptions).embedding
                }.getOrNull()
                if (imageVec != null && imageVec.isNotEmpty()) {
                    _telemetry.update {
                        it.copy(
                            status = EmbeddingRuntimeStatus.READY,
                            lastLatencyMs = System.currentTimeMillis() - start
                        )
                    }
                    return@withLock imageVec
                }
            }

            return@withLock runCatching {
                val snippet = "title: $title | text: ${content.take(1200)}".trim()
                val prompt = "task: search result | $snippet"
                val result = engine.computeEmbedding(
                    listOf(InputData.Text(prompt)),
                    defaultOptions
                )
                _telemetry.update {
                    it.copy(
                        status = EmbeddingRuntimeStatus.READY,
                        lastLatencyMs = System.currentTimeMillis() - start
                    )
                }
                result.embedding
            }.getOrNull()
        }
    }

    suspend fun unloadModel() = withContext(engineDispatcher) {
        mutex.withLock {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        runCatching { embeddingEngine?.close() }
        embeddingEngine = null
        _telemetry.update {
            it.copy(
                status = EmbeddingRuntimeStatus.UNLOADED,
                loadedModelId = "",
                loadedModelName = "No embedder loaded",
                loadedModelPath = "",
                vectorDimensions = 0,
                statusNote = "Load EmbeddingGemma in Models tab for semantic vector search"
            )
        }
    }

    fun shutdown() {
        scope.launch {
            mutex.withLock { releaseInternal() }
            singleThreadExecutor.shutdown()
        }
    }
}
