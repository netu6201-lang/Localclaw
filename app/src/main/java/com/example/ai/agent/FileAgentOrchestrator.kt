package com.example.ai.agent

import com.example.ai.engine.HybridSearchEngine
import com.example.ai.engine.LocalLlmEngineManager
import com.example.data.db.AgentMemoryDao
import com.example.data.db.AgentMemoryEntity
import com.example.data.db.FileIndexDao
import com.example.data.db.FileIndexEntity
import com.example.data.db.FtsQuerySanitizer
import com.example.data.preferences.LlmProviderMode
import com.example.operations.OperationControlManager
import com.example.operations.OperationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Core ReAct Agentic Loop (Plan -> Tool Call -> Execute -> Observe -> Respond)
 * supporting both On-Device Gemma/Qwen AND Ollama API, with full access to all
 * file/search/model/scan tools AND EdgeClaw App Settings.
 */
class FileAgentOrchestrator(
    private val llmEngineManager: LocalLlmEngineManager,
    private val hybridSearchEngine: HybridSearchEngine,
    private val fileIndexDao: FileIndexDao,
    private val agentMemoryDao: AgentMemoryDao,
    private val fileOpsExecutor: FileOperationsExecutor,
    private val guardrails: SandboxGuardrails,
    private val operationControlManager: OperationControlManager,
    private val systemToolbox: AgentSystemToolbox
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val _messages = MutableStateFlow<List<AgentChatMessage>>(emptyList())
    val messages: StateFlow<List<AgentChatMessage>> = _messages.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingActionBatch?>(null)
    val pendingConfirmation: StateFlow<PendingActionBatch?> = _pendingConfirmation.asStateFlow()

    private val _isAgentBusy = MutableStateFlow(false)
    val isAgentBusy: StateFlow<Boolean> = _isAgentBusy.asStateFlow()

    private var confirmationDeferred: CompletableDeferred<Boolean>? = null
    private var activeAgentJob: Job? = null
    private var activeAssistantMsgId: String? = null

    fun resolvePendingConfirmation(approved: Boolean) {
        confirmationDeferred?.complete(approved)
        confirmationDeferred = null
        _pendingConfirmation.value = null
    }

    fun pauseAgent() {
        operationControlManager.pause(OperationType.AGENT_TASK, "Agent paused by user")
    }

    fun resumeAgent() {
        operationControlManager.resume(OperationType.AGENT_TASK, "Resuming agent step...")
    }

    fun stopAgent() {
        operationControlManager.stop(OperationType.AGENT_TASK, "Stopped by user")
        confirmationDeferred?.complete(false)
        confirmationDeferred = null
        _pendingConfirmation.value = null
        activeAgentJob?.cancel()
        activeAssistantMsgId?.let { id ->
            _messages.update { list ->
                list.map { msg ->
                    if (msg.id == id && msg.isStreaming) {
                        msg.copy(
                            text = if (msg.text == "Thinking on-device...") "Agent stopped by user." else "${msg.text}\n\n[Stopped by user]",
                            isStreaming = false
                        )
                    } else msg
                }
            }
        }
        _isAgentBusy.value = false
    }

    fun clearConversation() {
        _messages.value = emptyList()
    }

    suspend fun submitUserPrompt(userText: String) = withContext(Dispatchers.IO) {
        if (userText.isBlank() || _isAgentBusy.value) return@withContext
        _isAgentBusy.value = true
        activeAgentJob = currentCoroutineContext()[Job]

        val userMsg = AgentChatMessage(
            id = UUID.randomUUID().toString(),
            isUser = true,
            text = userText.trim()
        )
        val assistantMsgId = UUID.randomUUID().toString()
        activeAssistantMsgId = assistantMsgId
        val traces = mutableListOf<AgentStepTrace>()

        val maxSteps = 8
        operationControlManager.start(
            type = OperationType.AGENT_TASK,
            total = maxSteps,
            detail = "Analyzing request..."
        )

        _messages.update {
            it + userMsg + AgentChatMessage(
                id = assistantMsgId,
                isUser = false,
                text = "Thinking on-device...",
                traces = emptyList(),
                isStreaming = true
            )
        }

        try {
            operationControlManager.checkpoint(OperationType.AGENT_TASK)
            val inferenceReady = llmEngineManager.isInferenceAvailable()
            if (!inferenceReady) {
                val fallbackTrace = AgentStepTrace(
                    stage = TraceStage.PLAN,
                    title = "Engine Standby -> Direct Tool & Cross-Script Assist",
                    detail = "No local LLM loaded and Ollama not configured. Executing direct tool/settings/search assist."
                )
                traces += fallbackTrace
                operationControlManager.checkpoint(OperationType.AGENT_TASK)
                val localAnswer = runDirectOfflineAssist(userText.trim(), traces)
                updateAssistantMessage(assistantMsgId, localAnswer, traces, isStreaming = false)
                operationControlManager.complete(OperationType.AGENT_TASK, "Completed offline assist")
                return@withContext
            }

            // Refresh system prompt with live app_settings + persisted agent_memory rules
            val rulesSummary = agentMemoryDao.getAll()
                .take(10)
                .joinToString("\n") { "- [${it.memoryKey}]: ${it.ruleText}" }
            val settingsSummary = systemToolbox.getAppSettingsSummary()
            val systemPrompt = AgentPromptAndSchema.buildSystemPrompt(
                userRulesSummary = rulesSummary,
                currentSettingsSummary = settingsSummary
            )
            llmEngineManager.resetConversation(systemPrompt)

            var currentTurnPrompt = "User request: $userText"
            var finalResponse = ""
            var lastBatchId: String? = null

            for (step in 1..maxSteps) {
                operationControlManager.checkpoint(OperationType.AGENT_TASK)
                operationControlManager.updateProgress(
                    OperationType.AGENT_TASK,
                    step,
                    maxSteps,
                    "Step $step / $maxSteps: Generating plan..."
                )

                val streamBuffer = StringBuilder()
                try {
                    llmEngineManager.generateStreaming(currentTurnPrompt).collect { chunk ->
                        operationControlManager.checkpoint(OperationType.AGENT_TASK)
                        streamBuffer.append(chunk)
                        val displayPreview = stripToolMarkupForPreview(streamBuffer.toString())
                        updateAssistantMessage(
                            assistantMsgId,
                            displayPreview.ifBlank { "Step $step: Reasoning..." },
                            traces,
                            isStreaming = true
                        )
                    }
                } catch (streamErr: Throwable) {
                    if (streamErr is CancellationException) throw streamErr
                    val telemetrySnapshot = llmEngineManager.telemetry.value
                    // If Ollama wasn't running during first step in AUTO_FALLBACK (or explicit OLLAMA),
                    // gracefully execute direct offline tool/search assist and include diagnostic note.
                    if (step == 1 && streamBuffer.isEmpty()) {
                        traces += AgentStepTrace(
                            stage = TraceStage.ERROR,
                            title = "LLM Connection / Runtime Fallback",
                            detail = "${streamErr.message ?: "Unavailable"} -> Running direct offline assist."
                        )
                        val direct = runDirectOfflineAssist(userText.trim(), traces)
                        val note = if (telemetrySnapshot.providerMode == LlmProviderMode.OLLAMA) {
                            "[Ollama API unreachable at ${telemetrySnapshot.ollamaBaseUrl}: ${streamErr.message}]\n\n$direct"
                        } else {
                            "[LLM Standby (${streamErr.message ?: "not running"}) -> Direct Offline Assist]\n\n$direct"
                        }
                        updateAssistantMessage(assistantMsgId, note, traces, isStreaming = false)
                        operationControlManager.complete(OperationType.AGENT_TASK, "Completed via offline fallback")
                        return@withContext
                    }
                    throw streamErr
                }

                val rawOutput = streamBuffer.toString().trim()
                val parsedTool = parseToolCall(rawOutput)

                if (parsedTool == null) {
                    finalResponse = rawOutput.ifBlank { "Completed." }
                    traces += AgentStepTrace(
                        stage = TraceStage.RESPONSE,
                        title = "Final Response",
                        detail = finalResponse.take(240)
                    )
                    break
                }

                if (parsedTool.thought.isNotBlank()) {
                    traces += AgentStepTrace(
                        stage = TraceStage.PLAN,
                        title = "Step $step Plan",
                        detail = parsedTool.thought
                    )
                }

                traces += AgentStepTrace(
                    stage = TraceStage.TOOL_CALL,
                    title = "Tool: ${parsedTool.tool}",
                    detail = parsedTool.args.toString()
                )
                updateAssistantMessage(assistantMsgId, "Executing ${parsedTool.tool}...", traces, isStreaming = true)

                operationControlManager.checkpoint(OperationType.AGENT_TASK)
                operationControlManager.updateProgress(
                    OperationType.AGENT_TASK,
                    step,
                    maxSteps,
                    "Executing tool: ${parsedTool.tool}"
                )
                val observation = executeToolWithGuardrails(parsedTool, traces) { batchId ->
                    lastBatchId = batchId
                }

                traces += AgentStepTrace(
                    stage = TraceStage.OBSERVE,
                    title = "Observation (${parsedTool.tool})",
                    detail = observation.take(450)
                )
                updateAssistantMessage(assistantMsgId, "Analyzing observation...", traces, isStreaming = true)

                currentTurnPrompt = buildString {
                    append("TOOL_OBSERVATION for '${parsedTool.tool}':\n")
                    append(observation)
                    append("\n\nContinue. Either call the next tool inside <tool_call>{...}</tool_call> or give the final plain-text answer to the user.")
                }

                if (step == maxSteps) {
                    finalResponse = "Reached maximum autonomous steps ($maxSteps). Last observation:\n$observation"
                }
            }

            updateAssistantMessage(
                id = assistantMsgId,
                text = finalResponse.ifBlank { "Done." },
                traces = traces,
                batchId = lastBatchId,
                isStreaming = false
            )
            operationControlManager.complete(OperationType.AGENT_TASK, "Agent task complete")
        } catch (_: CancellationException) {
            traces += AgentStepTrace(
                stage = TraceStage.ERROR,
                title = "Agent Stopped",
                detail = "Operation stopped by user."
            )
            updateAssistantMessage(
                id = assistantMsgId,
                text = "Agent stopped by user.",
                traces = traces,
                isStreaming = false
            )
            operationControlManager.stop(OperationType.AGENT_TASK, "Stopped by user")
        } catch (t: Throwable) {
            traces += AgentStepTrace(
                stage = TraceStage.ERROR,
                title = "Agent Error",
                detail = t.message ?: t::class.simpleName.orEmpty()
            )
            updateAssistantMessage(
                id = assistantMsgId,
                text = "Agent stopped: ${t.message}",
                traces = traces,
                isStreaming = false
            )
            operationControlManager.fail(OperationType.AGENT_TASK, t.message ?: "Agent error")
        } finally {
            activeAgentJob = null
            activeAssistantMsgId = null
            _pendingConfirmation.value = null
            _isAgentBusy.value = false
        }
    }

    private suspend fun runDirectOfflineAssist(
        userText: String,
        traces: MutableList<AgentStepTrace>
    ): String {
        val lower = userText.lowercase(Locale.US)

        // Check direct settings / scan / model commands even when LLM isn't loaded
        if (lower.contains("auto scan") || lower.contains("auto-scan") || lower.contains("ऑटो स्कैन")) {
            val mode = if (lower.contains("off") || lower.contains("disable") || lower.contains("band")) "MANUAL" else "AUTO_SCAN"
            traces += AgentStepTrace(stage = TraceStage.TOOL_CALL, title = "Tool: update_app_settings", detail = "{\"scan_mode\":\"$mode\"}")
            val out = systemToolbox.updateAppSettings(UpdateAppSettingsArgs(scanMode = mode))
            traces += AgentStepTrace(stage = TraceStage.OBSERVE, title = "Observation (update_app_settings)", detail = out)
            return out
        }

        if (lower.contains("batch scan") || lower.contains("बैच स्कैन")) {
            traces += AgentStepTrace(stage = TraceStage.TOOL_CALL, title = "Tool: update_app_settings", detail = "{\"scan_mode\":\"BATCH\"}")
            val out = systemToolbox.updateAppSettings(UpdateAppSettingsArgs(scanMode = "BATCH"))
            traces += AgentStepTrace(stage = TraceStage.OBSERVE, title = "Observation (update_app_settings)", detail = out)
            return out
        }

        if (lower.contains("manual scan") || lower.contains("manual selection")) {
            traces += AgentStepTrace(stage = TraceStage.TOOL_CALL, title = "Tool: update_app_settings", detail = "{\"scan_mode\":\"MANUAL\"}")
            val out = systemToolbox.updateAppSettings(UpdateAppSettingsArgs(scanMode = "MANUAL"))
            traces += AgentStepTrace(stage = TraceStage.OBSERVE, title = "Observation (update_app_settings)", detail = out)
            return out
        }

        if ((lower.contains("setting") || lower.contains("सेटिंग") || lower.contains("status")) &&
            !lower.contains("search") && !lower.contains("find")
        ) {
            traces += AgentStepTrace(stage = TraceStage.TOOL_CALL, title = "Tool: get_app_settings", detail = "{}")
            val out = systemToolbox.getAppSettingsSummary()
            traces += AgentStepTrace(stage = TraceStage.OBSERVE, title = "Observation (get_app_settings)", detail = out)
            return "Current EdgeClaw Settings:\n$out"
        }

        if (lower.contains("scan storage") || lower.contains("start scan") || lower.contains("full scan") || lower.contains("स्कैन शुरू")) {
            traces += AgentStepTrace(stage = TraceStage.TOOL_CALL, title = "Tool: scan_storage", detail = "{\"action\":\"FULL_SCAN\"}")
            val out = systemToolbox.handleScanStorage("FULL_SCAN")
            traces += AgentStepTrace(stage = TraceStage.OBSERVE, title = "Observation (scan_storage)", detail = out)
            return out
        }

        if (lower.contains("list model") || lower.contains("test ollama") || lower.contains("ollama models")) {
            val action = if (lower.contains("test ollama")) "TEST_OLLAMA" else "LIST"
            traces += AgentStepTrace(stage = TraceStage.TOOL_CALL, title = "Tool: manage_models", detail = "{\"action\":\"$action\"}")
            val out = systemToolbox.handleManageModels(ManageModelsArgs(action = action))
            traces += AgentStepTrace(stage = TraceStage.OBSERVE, title = "Observation (manage_models)", detail = out)
            return out
        }

        if (lower.contains("undo") || lower.contains("वापस")) {
            val latest = systemToolbox.getLatestUndoableBatchId()
            if (latest != null) {
                traces += AgentStepTrace(stage = TraceStage.TOOL_CALL, title = "Tool: undo_batch", detail = "{\"batch_id\":\"$latest\"}")
                val rep = fileOpsExecutor.undoBatch(latest)
                traces += AgentStepTrace(stage = TraceStage.OBSERVE, title = "Observation (undo_batch)", detail = rep.message)
                return rep.message
            }
        }

        val ftsQuery = FtsQuerySanitizer.toCrossScriptFtsQuery(userText)
        val hits = hybridSearchEngine.search(
            rawQuery = userText,
            limit = 10
        )
        val usedSemantic = hits.any { it.semanticSimilarity > 0f }
        val memories = agentMemoryDao.queryByKeyword(userText.take(24), limit = 5)

        traces += AgentStepTrace(
            stage = TraceStage.TOOL_CALL,
            title = "Tool: search_files",
            detail = "{\"query\": \"${ftsQuery.ifBlank { userText }}\", \"mode\": \"${if (usedSemantic) "HYBRID_CROSS_SCRIPT" else "FTS4_CROSS_SCRIPT"}\"}"
        )
        traces += AgentStepTrace(
            stage = TraceStage.OBSERVE,
            title = "Observation (search_files)",
            detail = "Found ${hits.size} cross-script match(es), ${memories.size} memory rule(s)."
        )

        return buildString {
            val header = if (usedSemantic) {
                "Offline Cross-Script Hybrid Search (Hindi + English + EmbeddingGemma)"
            } else {
                "Offline Cross-Script Search (Hindi + Hinglish + FTS4)"
            }
            append("$header (Load Gemma in Models or configure Ollama API in Settings for full multi-step AI reasoning):\n\n")
            if (hits.isEmpty()) {
                append("No indexed files matched \"$userText\". Try scanning media/folders in Settings -> Scan Mode.")
            } else {
                append("Matched ${hits.size} file(s):\n")
                hits.forEachIndexed { index, hit ->
                    val file = hit.file
                    val simLabel = if (hit.semanticSimilarity > 0f) {
                        val pct = (hit.semanticSimilarity * 100f).toInt().coerceIn(0, 100)
                        " • semantic ${pct}%"
                    } else {
                        ""
                    }
                    append("${index + 1}. ${file.fileName} (${file.fileCategory}, ${file.sizeBytes / 1024} KB$simLabel)\n")
                    append("   Path: ${file.filePath}\n")
                    if (file.extractedText.isNotBlank()) {
                        append("   Snippet: ${file.extractedText.take(110).replace("\n", " ")}...\n")
                    }
                }
            }
            if (memories.isNotEmpty()) {
                append("\nActive Memory Rules:\n")
                memories.forEach { append("• ${it.memoryKey}: ${it.ruleText}\n") }
            }
        }
    }

    private suspend fun executeToolWithGuardrails(
        call: AgentToolCall,
        traces: MutableList<AgentStepTrace>,
        onBatchExecuted: (String) -> Unit
    ): String {
        return when (call.tool.trim().lowercase()) {
            "search_files" -> {
                val args = decodeArgs<SearchFilesArgs>(call.args) ?: SearchFilesArgs()
                val category = args.fileType.uppercase().takeIf { it != "ALL" && it.isNotBlank() }
                val minMs = when (args.dateRange.uppercase()) {
                    "TODAY" -> System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                    "LAST_7_DAYS" -> System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                    "LAST_30_DAYS" -> System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
                    else -> null
                }
                if (args.query.isNotBlank()) {
                    val hits = hybridSearchEngine.search(
                        rawQuery = args.query,
                        category = category,
                        minModifiedMs = minMs,
                        limit = 20
                    )
                    if (hits.isEmpty()) {
                        "No matching files found in SQLite FTS4, cross-script transliteration, or EmbeddingGemma vector index."
                    } else {
                        hits.joinToString("\n") { hit ->
                            val f = hit.file
                            val preview = f.extractedText.take(120).replace("\n", " ")
                            val simTag = if (hit.semanticSimilarity > 0f) {
                                String.format(Locale.US, " | sim=%.2f", hit.semanticSimilarity)
                            } else {
                                ""
                            }
                            "- ${f.fileName} | category=${f.fileCategory} | size=${f.sizeBytes}B$simTag | path=${f.filePath}" +
                                if (preview.isNotBlank()) " | text=\"$preview\"" else ""
                        }
                    }
                } else {
                    val results: List<FileIndexEntity> = fileIndexDao.searchFallbackLike(
                        likeTerm = "",
                        category = category,
                        minModifiedMs = minMs,
                        limit = 20
                    )
                    if (results.isEmpty()) {
                        "No matching files found in SQLite FTS index."
                    } else {
                        results.joinToString("\n") { f ->
                            val preview = f.extractedText.take(120).replace("\n", " ")
                            "- ${f.fileName} | category=${f.fileCategory} | size=${f.sizeBytes}B | path=${f.filePath}" +
                                if (preview.isNotBlank()) " | text=\"$preview\"" else ""
                        }
                    }
                }
            }

            "read_file_summary" -> {
                val args = decodeArgs<ReadFileSummaryArgs>(call.args)
                    ?: return "Error: Missing file_path argument."
                when (val check = guardrails.validatePath(args.filePath)) {
                    is SandboxGuardrails.ValidationResult.Blocked -> check.reason
                    is SandboxGuardrails.ValidationResult.Allowed -> {
                        val indexed = fileIndexDao.getByPath(check.canonicalFile.absolutePath)
                        if (indexed != null && indexed.extractedText.isNotBlank()) {
                            "File: ${indexed.fileName}\nPath: ${indexed.filePath}\nContent:\n${indexed.extractedText.take(2500)}"
                        } else if (check.canonicalFile.exists() && check.canonicalFile.isFile) {
                            val rawSnippet = runCatching {
                                if (check.canonicalFile.length() <= 512_000L) {
                                    check.canonicalFile.readText().take(2000)
                                } else {
                                    "Binary or large file (${check.canonicalFile.length()} bytes). Run OCR/PDF indexer to extract text."
                                }
                            }.getOrDefault("Unable to read text from file.")
                            "File: ${check.canonicalFile.name}\nSnippet:\n$rawSnippet"
                        } else {
                            "File does not exist at ${check.canonicalFile.absolutePath}"
                        }
                    }
                }
            }

            "list_directory" -> {
                val args = decodeArgs<ListDirectoryArgs>(call.args) ?: ListDirectoryArgs()
                systemToolbox.listDirectory(args.path)
            }

            "file_action" -> {
                val args = decodeArgs<FileActionArgs>(call.args)
                    ?: return "Error: Invalid file_action arguments."
                val op = args.operation.uppercase().trim()

                if (op in setOf("MOVE", "COPY", "RENAME") && args.sourcePaths.size > 3) {
                    val batchId = UUID.randomUUID().toString()
                    val approved = requestHumanConfirmation(
                        PendingActionBatch(
                            batchId = batchId,
                            actionTitle = "Batch $op (${args.sourcePaths.size} files)",
                            operation = op,
                            sourcePaths = args.sourcePaths,
                            destinationPath = args.destinationPath,
                            reason = "Guardrail triggered: batch operation affects more than 3 files."
                        ),
                        traces
                    )
                    if (!approved) {
                        return "User REJECTED the batch $op action. Do not retry without permission."
                    }
                }

                val report = fileOpsExecutor.executeFileAction(
                    operation = op,
                    rawSources = args.sourcePaths,
                    rawDestination = args.destinationPath
                )
                if (report.success) onBatchExecuted(report.batchId)
                report.message
            }

            "safe_trash" -> {
                val args = decodeArgs<SafeTrashArgs>(call.args)
                    ?: return "Error: Missing file_paths for safe_trash."
                val batchId = UUID.randomUUID().toString()
                val approved = requestHumanConfirmation(
                    PendingActionBatch(
                        batchId = batchId,
                        actionTitle = "Move ${args.filePaths.size} file(s) to .agent_trash",
                        operation = "SAFE_TRASH",
                        sourcePaths = args.filePaths,
                        destinationPath = guardrails.safeTrashRoot.absolutePath,
                        reason = "Guardrail triggered: All trash operations require explicit human approval."
                    ),
                    traces
                )
                if (!approved) {
                    return "User REJECTED safe_trash. Files were left untouched."
                }
                val report = fileOpsExecutor.executeSafeTrash(args.filePaths, batchId = batchId)
                if (report.success) onBatchExecuted(report.batchId)
                report.message
            }

            "undo_batch" -> {
                val args = decodeArgs<UndoBatchArgs>(call.args) ?: UndoBatchArgs()
                val targetBatch = args.batchId.trim().ifBlank {
                    systemToolbox.getLatestUndoableBatchId() ?: ""
                }
                if (targetBatch.isBlank()) {
                    "No active undoable file operation batch found in action_logs."
                } else {
                    fileOpsExecutor.undoBatch(targetBatch).message
                }
            }

            "memory_upsert" -> {
                val args = decodeArgs<MemoryUpsertArgs>(call.args)
                    ?: return "Error: Missing key or rule_text."
                agentMemoryDao.upsert(
                    AgentMemoryEntity(
                        memoryKey = args.key.trim().lowercase().replace(" ", "_"),
                        ruleText = args.ruleText.trim()
                    )
                )
                "Saved memory rule [${args.key}]: ${args.ruleText}"
            }

            "memory_query" -> {
                val args = decodeArgs<MemoryQueryArgs>(call.args) ?: MemoryQueryArgs()
                val rows = if (args.context.isBlank()) {
                    agentMemoryDao.getAll()
                } else {
                    agentMemoryDao.queryByKeyword(args.context.trim())
                        .ifEmpty { agentMemoryDao.getAll() }
                }
                if (rows.isEmpty()) {
                    "No saved user rules in agent_memory yet."
                } else {
                    rows.joinToString("\n") { "- ${it.memoryKey}: ${it.ruleText}" }
                }
            }

            "scan_storage" -> {
                val args = decodeArgs<ScanStorageArgs>(call.args) ?: ScanStorageArgs()
                systemToolbox.handleScanStorage(args.action)
            }

            "manage_models" -> {
                val args = decodeArgs<ManageModelsArgs>(call.args) ?: ManageModelsArgs()
                systemToolbox.handleManageModels(args)
            }

            "get_app_settings" -> {
                systemToolbox.getAppSettingsSummary()
            }

            "update_app_settings" -> {
                val args = decodeArgs<UpdateAppSettingsArgs>(call.args)
                    ?: return "Error: Invalid update_app_settings arguments."
                systemToolbox.updateAppSettings(args)
            }

            else -> "Unknown tool '${call.tool}'. Available: search_files, read_file_summary, list_directory, file_action, safe_trash, undo_batch, memory_upsert, memory_query, scan_storage, manage_models, get_app_settings, update_app_settings."
        }
    }

    private suspend fun requestHumanConfirmation(
        batch: PendingActionBatch,
        traces: MutableList<AgentStepTrace>
    ): Boolean {
        traces += AgentStepTrace(
            stage = TraceStage.CONFIRMATION_REQUIRED,
            title = "Paused: ${batch.actionTitle}",
            detail = batch.reason
        )
        val deferred = CompletableDeferred<Boolean>()
        confirmationDeferred = deferred
        _pendingConfirmation.value = batch
        return deferred.await()
    }

    fun parseToolCall(raw: String): AgentToolCall? {
        val taggedMatch = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
            .find(raw)
        val candidateJson = taggedMatch?.groupValues?.getOrNull(1)
            ?: extractFirstJsonObject(raw)
            ?: return null

        return runCatching {
            json.decodeFromString<AgentToolCall>(candidateJson)
        }.getOrNull()
    }

    private fun extractFirstJsonObject(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.contains("\"tool\"")) return null
        val start = trimmed.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until trimmed.length) {
            val ch = trimmed[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') depth++
                if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        return trimmed.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private inline fun <reified T> decodeArgs(obj: JsonObject): T? =
        runCatching { json.decodeFromString<T>(obj.toString()) }.getOrNull()

    private fun stripToolMarkupForPreview(text: String): String =
        text.replace(Regex("<tool_call>.*", RegexOption.DOT_MATCHES_ALL), "").trim()

    private fun updateAssistantMessage(
        id: String,
        text: String,
        traces: List<AgentStepTrace>,
        batchId: String? = null,
        isStreaming: Boolean
    ) {
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == id) {
                    msg.copy(
                        text = text,
                        traces = traces.toList(),
                        batchId = batchId ?: msg.batchId,
                        isStreaming = isStreaming
                    )
                } else {
                    msg
                }
            }
        }
    }
}
