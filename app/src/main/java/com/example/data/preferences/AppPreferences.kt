package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "edgeclaw_prefs")

enum class HardwareDelegate(val label: String, val subtitle: String) {
    NPU("NPU", "Hexagon / Neural Core"),
    GPU("GPU", "OpenCL / ML Drift"),
    CPU("CPU", "XNNPACK Multi-Thread")
}

enum class ScanMode(val label: String, val subtitle: String) {
    MANUAL("Manual", "Pick 1 media item"),
    AUTO_SCAN("Auto-Scan", "New arrivals"),
    BATCH("Batch Scan", "Multiple files")
}

enum class LlmProviderMode(val label: String, val subtitle: String) {
    ON_DEVICE("On-Device", "Local Gemma / Qwen"),
    OLLAMA("Ollama API", "Local / LAN / Remote"),
    AUTO_FALLBACK("Auto Backup", "Offline first, Ollama fallback")
}

data class EngineSettings(
    val delegate: HardwareDelegate = HardwareDelegate.GPU,
    val activeModelId: String = "",
    val maxTokens: Int = 1024,
    val topK: Int = 32,
    val temperature: Float = 0.25f,
    val lastIndexedMs: Long = 0L,
    val autoScanNewMedia: Boolean = false,
    val scanMode: ScanMode = ScanMode.MANUAL,
    val llmProviderMode: LlmProviderMode = LlmProviderMode.AUTO_FALLBACK,
    val ollamaBaseUrl: String = "http://127.0.0.1:11434",
    val ollamaModelName: String = "gemma3:4b",
    val ollamaApiKey: String = ""
)

class AppPreferences(private val context: Context) {

    private object Keys {
        val DELEGATE = stringPreferencesKey("hardware_delegate")
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val TOP_K = intPreferencesKey("top_k")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val LAST_INDEXED_MS = longPreferencesKey("last_indexed_ms")
        val AUTO_SCAN_NEW_MEDIA = booleanPreferencesKey("auto_scan_new_media")
        val SCAN_MODE = stringPreferencesKey("scan_mode")
        val LLM_PROVIDER_MODE = stringPreferencesKey("llm_provider_mode")
        val OLLAMA_BASE_URL = stringPreferencesKey("ollama_base_url")
        val OLLAMA_MODEL_NAME = stringPreferencesKey("ollama_model_name")
        val OLLAMA_API_KEY = stringPreferencesKey("ollama_api_key")
    }

    val settingsFlow: Flow<EngineSettings> = context.dataStore.data.map { prefs ->
        val delegateName = prefs[Keys.DELEGATE] ?: HardwareDelegate.GPU.name
        val delegate = runCatching { HardwareDelegate.valueOf(delegateName) }
            .getOrDefault(HardwareDelegate.GPU)
        val scanModeName = prefs[Keys.SCAN_MODE] ?: if (prefs[Keys.AUTO_SCAN_NEW_MEDIA] == true) {
            ScanMode.AUTO_SCAN.name
        } else {
            ScanMode.MANUAL.name
        }
        val scanMode = runCatching { ScanMode.valueOf(scanModeName) }
            .getOrDefault(ScanMode.MANUAL)
        val providerName = prefs[Keys.LLM_PROVIDER_MODE] ?: LlmProviderMode.AUTO_FALLBACK.name
        val providerMode = runCatching { LlmProviderMode.valueOf(providerName) }
            .getOrDefault(LlmProviderMode.AUTO_FALLBACK)

        EngineSettings(
            delegate = delegate,
            activeModelId = prefs[Keys.ACTIVE_MODEL_ID] ?: "",
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 1024,
            topK = prefs[Keys.TOP_K] ?: 32,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.25f,
            lastIndexedMs = prefs[Keys.LAST_INDEXED_MS] ?: 0L,
            autoScanNewMedia = prefs[Keys.AUTO_SCAN_NEW_MEDIA] ?: false,
            scanMode = scanMode,
            llmProviderMode = providerMode,
            ollamaBaseUrl = prefs[Keys.OLLAMA_BASE_URL] ?: "http://127.0.0.1:11434",
            ollamaModelName = prefs[Keys.OLLAMA_MODEL_NAME] ?: "gemma3:4b",
            ollamaApiKey = prefs[Keys.OLLAMA_API_KEY] ?: ""
        )
    }

    suspend fun setDelegate(delegate: HardwareDelegate) {
        context.dataStore.edit { it[Keys.DELEGATE] = delegate.name }
    }

    suspend fun setActiveModelId(modelId: String) {
        context.dataStore.edit { it[Keys.ACTIVE_MODEL_ID] = modelId }
    }

    suspend fun setLastIndexedMs(timestamp: Long) {
        context.dataStore.edit { it[Keys.LAST_INDEXED_MS] = timestamp }
    }

    suspend fun setAutoScanNewMedia(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.AUTO_SCAN_NEW_MEDIA] = enabled
            if (enabled) {
                it[Keys.SCAN_MODE] = ScanMode.AUTO_SCAN.name
            }
        }
    }

    suspend fun setScanMode(mode: ScanMode) {
        context.dataStore.edit {
            it[Keys.SCAN_MODE] = mode.name
            it[Keys.AUTO_SCAN_NEW_MEDIA] = (mode == ScanMode.AUTO_SCAN)
        }
    }

    suspend fun setLlmProviderMode(mode: LlmProviderMode) {
        context.dataStore.edit { it[Keys.LLM_PROVIDER_MODE] = mode.name }
    }

    suspend fun setOllamaConfig(baseUrl: String, modelName: String, apiKey: String = "") {
        context.dataStore.edit {
            val normalizedUrl = baseUrl.trim().trimEnd('/').ifBlank { "http://127.0.0.1:11434" }
            it[Keys.OLLAMA_BASE_URL] = normalizedUrl
            it[Keys.OLLAMA_MODEL_NAME] = modelName.trim().ifBlank { "gemma3:4b" }
            it[Keys.OLLAMA_API_KEY] = apiKey.trim()
        }
    }

    suspend fun updateSampling(maxTokens: Int, topK: Int, temperature: Float) {
        context.dataStore.edit {
            it[Keys.MAX_TOKENS] = maxTokens
            it[Keys.TOP_K] = topK
            it[Keys.TEMPERATURE] = temperature
        }
    }
}
