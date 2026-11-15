package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai.agent.AgentChatMessage
import com.example.ai.agent.PendingActionBatch
import com.example.ai.engine.EmbeddingTelemetry
import com.example.ai.engine.LlmTelemetry
import com.example.data.db.ActionLogEntity
import com.example.data.db.AgentMemoryEntity
import com.example.data.db.FileIndexEntity
import com.example.operations.OperationProgress
import com.example.operations.OperationType
import com.example.ui.state.SearchAgentMode
import com.example.ui.state.SearchScreenUiState

/**
 * Combined Search & Agent workspace where a single top toggle lets users switch smoothly
 * between searching on their own (Room FTS4 + EmbeddingGemma) or asking EdgeClaw Agent for help.
 */
@Composable
fun SearchAndAgentScreen(
    mode: SearchAgentMode,
    onSelectMode: (SearchAgentMode) -> Unit,
    // Search props
    searchState: SearchScreenUiState,
    indexedCount: Int,
    embeddingTelemetry: EmbeddingTelemetry,
    operationStates: Map<OperationType, OperationProgress>,
    onSearchQueryChange: (String) -> Unit,
    onSelectSearchCategory: (String) -> Unit,
    onSelectSearchDateRange: (String) -> Unit,
    onToggleHybrid: (Boolean) -> Unit,
    onTriggerSearchNow: () -> Unit,
    onStopSearch: () -> Unit,
    onLoadMoreSearch: () -> Unit,
    onPauseOperation: (OperationType) -> Unit,
    onResumeOperation: (OperationType) -> Unit,
    onStopOperation: (OperationType) -> Unit,
    onLoadFullFile: suspend (Long) -> FileIndexEntity?,
    onAskAgentAboutFile: (FileIndexEntity) -> Unit,
    onSafeTrashFile: (FileIndexEntity) -> Unit,
    // Agent props
    messages: List<AgentChatMessage>,
    pendingConfirmation: PendingActionBatch?,
    isAgentBusy: Boolean,
    llmTelemetry: LlmTelemetry,
    actionLogs: List<ActionLogEntity>,
    memoryRules: List<AgentMemoryEntity>,
    initialDraftPrompt: String,
    onConsumeDraftPrompt: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onPauseAgent: () -> Unit,
    onResumeAgent: () -> Unit,
    onStopAgent: () -> Unit,
    onApproveBatch: (Boolean) -> Unit,
    onUndoBatch: (String) -> Unit,
    onUpsertMemory: (String, String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearChat: () -> Unit,
    onOpenModelsTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Unified One-Tap Mode Toggle: Own Search | Agent Help
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ModeTogglePill(
                    label = "Search Own",
                    icon = Icons.Filled.Search,
                    selected = mode == SearchAgentMode.SEARCH,
                    onClick = { onSelectMode(SearchAgentMode.SEARCH) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("toggle_mode_search")
                )
                ModeTogglePill(
                    label = "Agent Help",
                    icon = Icons.Filled.Psychology,
                    selected = mode == SearchAgentMode.AGENT,
                    onClick = { onSelectMode(SearchAgentMode.AGENT) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("toggle_mode_agent")
                )
            }
        }

        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.weight(1f),
            label = "search_agent_switch"
        ) { activeMode ->
            when (activeMode) {
                SearchAgentMode.SEARCH -> {
                    SearchScreen(
                        state = searchState,
                        indexedCount = indexedCount,
                        embeddingTelemetry = embeddingTelemetry,
                        operationStates = operationStates,
                        onQueryChange = onSearchQueryChange,
                        onSelectCategory = onSelectSearchCategory,
                        onSelectDateRange = onSelectSearchDateRange,
                        onToggleHybrid = onToggleHybrid,
                        onTriggerSearchNow = onTriggerSearchNow,
                        onStopSearch = onStopSearch,
                        onLoadMore = onLoadMoreSearch,
                        onPauseOperation = onPauseOperation,
                        onResumeOperation = onResumeOperation,
                        onStopOperation = onStopOperation,
                        onLoadFullFile = onLoadFullFile,
                        onAskAgentAboutFile = { file ->
                            onAskAgentAboutFile(file)
                            onSelectMode(SearchAgentMode.AGENT)
                        },
                        onSafeTrashFile = onSafeTrashFile
                    )
                }

                SearchAgentMode.AGENT -> {
                    AgentScreen(
                        messages = messages,
                        pendingConfirmation = pendingConfirmation,
                        isAgentBusy = isAgentBusy,
                        agentOperation = operationStates[OperationType.AGENT_TASK]
                            ?: OperationProgress(type = OperationType.AGENT_TASK),
                        telemetry = llmTelemetry,
                        actionLogs = actionLogs,
                        memoryRules = memoryRules,
                        initialDraftPrompt = initialDraftPrompt,
                        onConsumeDraftPrompt = onConsumeDraftPrompt,
                        onSendPrompt = onSendPrompt,
                        onPauseAgent = onPauseAgent,
                        onResumeAgent = onResumeAgent,
                        onStopAgent = onStopAgent,
                        onApproveBatch = onApproveBatch,
                        onUndoBatch = onUndoBatch,
                        onUpsertMemory = onUpsertMemory,
                        onDeleteMemory = onDeleteMemory,
                        onClearChat = onClearChat,
                        onOpenModelsTab = onOpenModelsTab
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeTogglePill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val fgColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = fgColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fgColor
        )
    }
}
