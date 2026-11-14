package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ai.agent.AgentChatMessage
import com.example.ai.agent.AgentStepTrace
import com.example.ai.agent.PendingActionBatch
import com.example.ai.agent.TraceStage
import com.example.ai.engine.EngineRuntimeStatus
import com.example.ai.engine.LlmTelemetry
import com.example.data.db.ActionLogEntity
import com.example.data.db.AgentMemoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_PROMPTS = listOf(
    "Find electricity bills and summarize",
    "Organize receipts into /Documents/Receipts",
    "Remember: put invoices in /Documents/Invoices",
    "Find large downloads from the last 7 days"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    messages: List<AgentChatMessage>,
    pendingConfirmation: PendingActionBatch?,
    isAgentBusy: Boolean,
    telemetry: LlmTelemetry,
    actionLogs: List<ActionLogEntity>,
    memoryRules: List<AgentMemoryEntity>,
    initialDraftPrompt: String,
    onConsumeDraftPrompt: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onApproveBatch: (Boolean) -> Unit,
    onUndoBatch: (String) -> Unit,
    onUpsertMemory: (String, String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearChat: () -> Unit,
    onOpenModelsTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(initialDraftPrompt) {
        if (initialDraftPrompt.isNotBlank()) {
            input = initialDraftPrompt
            onConsumeDraftPrompt()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                input = spoken
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak file command for EdgeClaw")
            }
            runCatching { speechLauncher.launch(intent) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Top Agent Status & Action Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "EdgeClaw Agent",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onOpenModelsTab)
                ) {
                    val statusColor = if (telemetry.status == EngineRuntimeStatus.READY ||
                        telemetry.status == EngineRuntimeStatus.GENERATING
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                    Text(
                        text = if (telemetry.status == EngineRuntimeStatus.READY ||
                            telemetry.status == EngineRuntimeStatus.GENERATING
                        ) {
                            "${telemetry.loadedModelName} • ${telemetry.activeDelegate.label} • ${
                                String.format(Locale.US, "%.1f", telemetry.tokensPerSecond)
                            } tok/s"
                        } else {
                            "No LLM loaded (FTS4 Assist Active) • Tap to load model"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = { showMemorySheet = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("open_memory_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.BookmarkAdded,
                        contentDescription = "Agent Memory Rules",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = { showHistorySheet = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("open_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = "Action History and Undo",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (messages.isNotEmpty()) {
                    IconButton(
                        onClick = onClearChat,
                        enabled = !isAgentBusy,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Clear conversation"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Confirmation Guardrail Card (>3 files batch move or safe_trash)
        AnimatedVisibility(visible = pendingConfirmation != null) {
            pendingConfirmation?.let { batch ->
                ConfirmationGuardrailCard(
                    batch = batch,
                    onApprove = { onApproveBatch(true) },
                    onReject = { onApproveBatch(false) }
                )
            }
        }

        // Chat / Trace List or Empty State
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                AgentEmptyState(
                    onPickPrompt = { prompt ->
                        input = prompt
                        onSendPrompt(prompt)
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        AgentMessageBubble(
                            message = msg,
                            onUndoBatch = onUndoBatch
                        )
                    }
                }
            }
        }

        // Conversational Agent Input Bar (Text + Voice)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("agent_prompt_input"),
                placeholder = {
                    Text(
                        "Ask EdgeClaw to search, summarize, or organize...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(
                                        RecognizerIntent.EXTRA_PROMPT,
                                        "Speak file command for EdgeClaw"
                                    )
                                }
                                runCatching { speechLauncher.launch(intent) }
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("agent_voice_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Voice input",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )

            Button(
                onClick = {
                    val prompt = input.trim()
                    if (prompt.isNotEmpty()) {
                        input = ""
                        onSendPrompt(prompt)
                    }
                },
                enabled = input.isNotBlank() && !isAgentBusy,
                modifier = Modifier
                    .height(52.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("agent_send_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send prompt to EdgeClaw Agent"
                )
            }
        }
    }

    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            ActionHistorySheetContent(
                logs = actionLogs,
                onUndoBatch = onUndoBatch
            )
        }
    }

    if (showMemorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showMemorySheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            AgentMemorySheetContent(
                rules = memoryRules,
                onUpsert = onUpsertMemory,
                onDelete = onDeleteMemory
            )
        }
    }
}

@Composable
private fun AgentEmptyState(
    onPickPrompt: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_edge_hero_1794649652645),
            contentDescription = "EdgeClaw Autonomous Agent",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(MaterialTheme.shapes.large)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "OpenClaw ReAct File Agent",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Plan -> Tool Call -> Execute -> Observe -> Respond\n100% private inside your Android sandbox.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(QUICK_PROMPTS) { prompt ->
                SuggestionChip(
                    onClick = { onPickPrompt(prompt) },
                    label = {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun AgentMessageBubble(
    message: AgentChatMessage,
    onUndoBatch: (String) -> Unit
) {
    var traceExpanded by remember(message.id) { mutableStateOf(message.isStreaming) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (message.isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            modifier = Modifier
                .fillMaxWidth(if (message.isUser) 0.86f else 0.96f)
                .border(
                    width = 1.dp,
                    color = if (message.isUser) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.isUser && message.traces.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { traceExpanded = !traceExpanded }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Terminal,
                                contentDescription = "Agent tool trace",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ReAct Trace (${message.traces.size} steps)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Icon(
                            imageVector = if (traceExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Toggle trace",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = traceExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            message.traces.forEach { trace ->
                                TraceStepItem(trace)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                if (!message.isUser && message.batchId != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onUndoBatch(message.batchId) },
                        modifier = Modifier.testTag("undo_inline_${message.batchId}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo batch action",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Undo File Operation")
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceStepItem(trace: AgentStepTrace) {
    val badgeColor = when (trace.stage) {
        TraceStage.PLAN -> MaterialTheme.colorScheme.secondary
        TraceStage.TOOL_CALL -> MaterialTheme.colorScheme.primary
        TraceStage.OBSERVE -> MaterialTheme.colorScheme.onSurfaceVariant
        TraceStage.CONFIRMATION_REQUIRED -> MaterialTheme.colorScheme.tertiary
        TraceStage.RESPONSE -> MaterialTheme.colorScheme.primary
        TraceStage.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp)
    ) {
        Text(
            text = "${trace.stage.name} • ${trace.title}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = badgeColor
        )
        Text(
            text = trace.detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConfirmationGuardrailCard(
    batch: PendingActionBatch,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .border(1.5.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.medium)
            .testTag("confirmation_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = "Guardrail approval required",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = batch.actionTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = batch.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "Target: ${batch.destinationPath}\nFiles (${batch.sourcePaths.size}): ${
                    batch.sourcePaths.take(4).joinToString { it.substringAfterLast('/') }
                }${if (batch.sourcePaths.size > 4) " +${batch.sourcePaths.size - 4} more" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("approve_action_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Approve")
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("reject_action_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reject")
                }
            }
        }
    }
}

@Composable
private fun ActionHistorySheetContent(
    logs: List<ActionLogEntity>,
    onUndoBatch: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Action Audit & Undo Log",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "All agent file moves, renames, and .agent_trash actions are reversible.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (logs.isEmpty()) {
            Text(
                text = "No file actions recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.height(340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${log.operation} • ${log.summary}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(log.timestampMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!log.isUndone) {
                                OutlinedButton(
                                    onClick = { onUndoBatch(log.batchId) },
                                    modifier = Modifier.testTag("undo_batch_${log.id}")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Undo")
                                }
                            } else {
                                Text(
                                    text = "UNDONE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun AgentMemorySheetContent(
    rules: List<AgentMemoryEntity>,
    onUpsert: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var ruleInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Persistent Agent Memory",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Stored in SQLite `agent_memory`. Injected into Gemma's system prompt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("Rule Key") },
                placeholder = { Text("electricity_bills") },
                singleLine = true,
                modifier = Modifier.weight(0.4f)
            )
            OutlinedTextField(
                value = ruleInput,
                onValueChange = { ruleInput = it },
                label = { Text("Rule") },
                placeholder = { Text("Always move to /Documents/Bills") },
                singleLine = true,
                modifier = Modifier.weight(0.6f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                if (keyInput.isNotBlank() && ruleInput.isNotBlank()) {
                    onUpsert(keyInput, ruleInput)
                    keyInput = ""
                    ruleInput = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Save Preference Rule")
        }

        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.height(220.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(rules, key = { it.memoryKey }) { rule ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rule.memoryKey,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = rule.ruleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { onDelete(rule.memoryKey) }) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete memory rule",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}
