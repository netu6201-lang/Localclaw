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

data class EngineSettings(
    val delegate: HardwareDelegate = HardwareDelegate.GPU,
    val activeModelId: String = "",
    val maxTokens: Int = 1024,
    val topK: Int = 32,
    val temperature: Float = 0.25f,
    val lastIndexedMs: Long = 0L,
    val autoScanNewMedia: Boolean = false
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
    }

    val settingsFlow: Flow<EngineSettings> = context.dataStore.data.map { prefs ->
        val delegateName = prefs[Keys.DELEGATE] ?: HardwareDelegate.GPU.name
        val delegate = runCatching { HardwareDelegate.valueOf(delegateName) }
            .getOrDefault(HardwareDelegate.GPU)
        EngineSettings(
            delegate = delegate,
            activeModelId = prefs[Keys.ACTIVE_MODEL_ID] ?: "",
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 1024,
            topK = prefs[Keys.TOP_K] ?: 32,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.25f,
            lastIndexedMs = prefs[Keys.LAST_INDEXED_MS] ?: 0L,
            autoScanNewMedia = prefs[Keys.AUTO_SCAN_NEW_MEDIA] ?: false
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
        context.dataStore.edit { it[Keys.AUTO_SCAN_NEW_MEDIA] = enabled }
    }

    suspend fun updateSampling(maxTokens: Int, topK: Int, temperature: Float) {
        context.dataStore.edit {
            it[Keys.MAX_TOKENS] = maxTokens
            it[Keys.TOP_K] = topK
            it[Keys.TEMPERATURE] = temperature
        }
    }
}
