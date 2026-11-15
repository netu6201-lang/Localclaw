package com.example.ai.agent

import com.example.ai.engine.EngineRuntimeStatus
import com.example.ai.engine.HybridSearchEngine
import com.example.ai.engine.LocalLlmEngineManager
import com.example.data.db.AgentMemoryDao
import com.example.data.db.AgentMemoryEntity
import com.example.data.db.FileIndexDao
import com.example.data.db.FileIndexEntity
import com.example.data.db.FtsQuerySanitizer
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
 * bound strictly to local Room FTS4, EmbeddingGemma vector search, ML Kit OCR/PDF text,
 * and sandboxed Kotlin file operations.
 */
class FileAgentOrchestrator(
    private val llmEngineManager: LocalLlmEngineManager,
    private val hybridSearchEngine: HybridSearchEngine,
    private val fileIndexDao: FileIndexDao,
    private val agentMemoryDao: AgentMemoryDao,
    private val fileOpsExecutor: FileOperationsExecutor,
    private val guardrails: SandboxGuardrails,
    private val operationControlManager: OperationControlManager
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

        val maxSteps = 5
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
            val engineReady = llmEngineManager.telemetry.value.status == EngineRuntimeStatus.READY
            if (!engineReady) {
                val fallbackTrace = AgentStepTrace(
                    stage = TraceStage.PLAN,
                    title = "Engine Standby -> Direct Hybrid/FTS4 Assist",
                    detail = "No .litertlm/.task LLM loaded yet. Executing direct offline Hybrid + FTS4 + Memory lookup."
                )
                traces += fallbackTrace
                operationControlManager.checkpoint(OperationType.AGENT_TASK)
                val localAnswer = runDirectOfflineAssist(userText.trim(), traces)
                updateAssistantMessage(assistantMsgId, localAnswer, traces, isStreaming = false)
                operationControlManager.complete(OperationType.AGENT_TASK, "Completed offline lookup")
                return@withContext
            }

            // Refresh system prompt with current persisted agent_memory rules
            val rulesSummary = agentMemoryDao.getAll()
                .take(10)
                .joinToString("\n") { "- [${it.memoryKey}]: ${it.ruleText}" }
            val systemPrompt = AgentPromptAndSchema.buildSystemPrompt(userRulesSummary = rulesSummary)
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
                    detail = observation.take(400)
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
        val ftsQuery = FtsQuerySanitizer.toSafeFtsQuery(userText)
        val hits = hybridSearchEngine.search(
            rawQuery = userText,
            limit = 8
        )
        val usedSemantic = hits.any { it.semanticSimilarity > 0f }
        val memories = agentMemoryDao.queryByKeyword(userText.take(24), limit = 5)

        traces += AgentStepTrace(
            stage = TraceStage.TOOL_CALL,
            title = "Tool: search_files",
            detail = "{\"query\": \"${ftsQuery.ifBlank { userText }}\", \"mode\": \"${if (usedSemantic) "HYBRID_VECTOR_FTS4" else "FTS4"}\"}"
        )
        traces += AgentStepTrace(
            stage = TraceStage.OBSERVE,
            title = "Observation (search_files)",
            detail = "Found ${hits.size} ${if (usedSemantic) "Hybrid (FTS4 + EmbeddingGemma)" else "FTS4"} match(es), ${memories.size} memory rule(s)."
        )

        return buildString {
            val header = if (usedSemantic) {
                "Offline Hybrid Search (EmbeddingGemma + FTS4)"
            } else {
                "Offline FTS4 Index Result"
            }
            append("$header (Load an Agent LLM in the Models tab for full autonomous reasoning & multi-step file organization):\n\n")
            if (hits.isEmpty()) {
                append("No indexed files matched \"$userText\". Try tapping 'Scan & OCR' in the Explorer tab.")
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
                        "No matching files found in SQLite FTS4 or EmbeddingGemma vector index."
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

            "file_action" -> {
                val args = decodeArgs<FileActionArgs>(call.args)
                    ?: return "Error: Invalid file_action arguments."
                val op = args.operation.uppercase().trim()

                // Safety Guardrail: Any batch move/copy/rename affecting >3 files pauses for Approve/Reject
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

            else -> "Unknown tool '${call.tool}'. Available: search_files, read_file_summary, file_action, safe_trash, memory_upsert, memory_query."
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
