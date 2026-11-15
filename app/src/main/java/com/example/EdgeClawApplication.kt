package com.example

import android.app.Application
import com.example.ai.agent.FileAgentOrchestrator
import com.example.ai.agent.FileOperationsExecutor
import com.example.ai.agent.SandboxGuardrails
import com.example.ai.engine.HybridSearchEngine
import com.example.ai.engine.LocalEmbeddingEngineManager
import com.example.ai.engine.LocalLlmEngineManager
import com.example.ai.engine.ModelCatalog
import com.example.data.db.EdgeClawDatabase
import com.example.data.preferences.AppPreferences
import com.example.operations.OperationControlManager
import com.example.workers.MediaAutoScanScheduler
import com.example.workers.MediaFileExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    val database: EdgeClawDatabase = EdgeClawDatabase.getInstance(application)
    val preferences: AppPreferences = AppPreferences(application)
    val guardrails: SandboxGuardrails = SandboxGuardrails(application)
    val operationControlManager: OperationControlManager = OperationControlManager()
    val mediaExtractor: MediaFileExtractor = MediaFileExtractor(application)
    val mediaAutoScanScheduler: MediaAutoScanScheduler = MediaAutoScanScheduler(application)
    val llmEngineManager: LocalLlmEngineManager = LocalLlmEngineManager(application)
    val embeddingEngineManager: LocalEmbeddingEngineManager = LocalEmbeddingEngineManager(application)
    val hybridSearchEngine: HybridSearchEngine = HybridSearchEngine(
        fileIndexDao = database.fileIndexDao(),
        embeddingEngineManager = embeddingEngineManager
    )
    val fileOpsExecutor: FileOperationsExecutor = FileOperationsExecutor(
        guardrails = guardrails,
        fileIndexDao = database.fileIndexDao(),
        actionLogDao = database.actionLogDao()
    )
    val agentOrchestrator: FileAgentOrchestrator = FileAgentOrchestrator(
        llmEngineManager = llmEngineManager,
        hybridSearchEngine = hybridSearchEngine,
        fileIndexDao = database.fileIndexDao(),
        agentMemoryDao = database.agentMemoryDao(),
        fileOpsExecutor = fileOpsExecutor,
        guardrails = guardrails,
        operationControlManager = operationControlManager
    )
}

class EdgeClawApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)

        appScope.launch {
            container.database.modelRecordDao().insertIgnore(ModelCatalog.defaultModels)
            val settings = container.preferences.settingsFlow.first()
            if (settings.autoScanNewMedia) {
                container.mediaAutoScanScheduler.setAutoScanEnabled(true)
            }
        }
    }
}
