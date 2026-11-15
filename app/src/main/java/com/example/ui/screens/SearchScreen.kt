package com.example.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ai.engine.EmbeddingRuntimeStatus
import com.example.ai.engine.EmbeddingTelemetry
import com.example.data.db.FileIndexEntity
import com.example.data.db.FileSearchListItem
import com.example.operations.OperationProgress
import com.example.operations.OperationType
import com.example.ui.components.OperationControlBar
import com.example.ui.state.SearchScreenUiState
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SEARCH_CATEGORIES = listOf("ALL", "IMAGE", "PDF", "DOCUMENT", "AUDIO", "VIDEO", "ARCHIVE")
private val DATE_RANGES = listOf(
    "ALL" to "Any time",
    "TODAY" to "Today",
    "LAST_7_DAYS" to "7 days",
    "LAST_30_DAYS" to "30 days"
)
private val QUICK_FTS_SUGGESTIONS = listOf("भूरी", "बिजली बिल", "रसीद", "invoice", "receipt", "screenshot", "pdf")

/**
 * High-performance Jetpack Compose Search Screen backed by paged Room FTS4 queries
 * and optional EmbeddingGemma cosine-similarity ranking. Designed for large datasets (10k+ files).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchScreenUiState,
    indexedCount: Int,
    embeddingTelemetry: EmbeddingTelemetry,
    operationStates: Map<OperationType, OperationProgress>,
    onQueryChange: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectDateRange: (String) -> Unit,
    onToggleHybrid: (Boolean) -> Unit,
    onTriggerSearchNow: () -> Unit,
    onStopSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onPauseOperation: (OperationType) -> Unit,
    onResumeOperation: (OperationType) -> Unit,
    onStopOperation: (OperationType) -> Unit,
    onLoadFullFile: suspend (Long) -> FileIndexEntity?,
    onAskAgentAboutFile: (FileIndexEntity) -> Unit,
    onSafeTrashFile: (FileIndexEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inspectedFile by remember { mutableStateOf<FileIndexEntity?>(null) }

    val embedderReady = embeddingTelemetry.status == EmbeddingRuntimeStatus.READY ||
        embeddingTelemetry.status == EmbeddingRuntimeStatus.EMBEDDING

    // Infinite scroll trigger when user scrolls within 8 items of the loaded end
    val shouldLoadMore by remember(state.results.size, state.hasMore, state.isLoadingMore, state.isSearching) {
        derivedStateOf {
            if (!state.hasMore || state.isLoadingMore || state.isSearching) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.results.isNotEmpty() && lastVisible >= state.results.size - 8
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "FTS4 Smart Search",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${state.totalMatches} match(es) • ${state.latencyMs} ms • $indexedCount total indexed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (state.isSearching || state.isLoadingMore) {
                OutlinedButton(
                    onClick = onStopSearch,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("search_stop_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop active search",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }

        // Show active background scan or vectorization Pause/Resume/Stop controls right inside Search too
        listOf(
            OperationType.STORAGE_SCAN,
            OperationType.MEDIA_PICK_SCAN,
            OperationType.VECTORIZE
        ).forEach { opType ->
            val op = operationStates[opType]
            if (op != null && op.isActiveOrPaused) {
                Spacer(modifier = Modifier.height(8.dp))
                OperationControlBar(
                    operation = op,
                    onPause = { onPauseOperation(opType) },
                    onResume = { onResumeOperation(opType) },
                    onStop = { onStopOperation(opType) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Debounced FTS4 + Semantic Query Input
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("fts_search_screen_input"),
            placeholder = {
                Text(
                    text = if (state.useHybrid && embedderReady) {
                        "Search English, Hinglish, or हिंदी (e.g. भूरी, बिल)..."
                    } else {
                        "Search English or हिंदी / Hinglish (e.g. भूरी, रसीद)..."
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Room FTS4 search")
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("search_clear_button")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search query")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onTriggerSearchNow() }),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        AnimatedVisibility(visible = state.isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Hybrid Mode + Date Filter Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            item {
                FilterChip(
                    selected = state.useHybrid && embedderReady,
                    enabled = embedderReady,
                    onClick = { onToggleHybrid(!state.useHybrid) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Hub,
                            contentDescription = "Hybrid EmbeddingGemma mode",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            if (embedderReady) "Hybrid AI + FTS4" else "FTS4 Only",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.testTag("search_hybrid_toggle")
                )
            }
            items(DATE_RANGES, key = { it.first }) { (key, label) ->
                FilterChip(
                    selected = state.dateRange == key,
                    onClick = { onSelectDateRange(key) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.testTag("search_date_${key.lowercase()}")
                )
            }
        }

        // Category Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(SEARCH_CATEGORIES, key = { it }) { category ->
                FilterChip(
                    selected = state.category == category,
                    onClick = { onSelectCategory(category) },
                    label = { Text(category, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("search_cat_${category.lowercase()}")
                )
            }
        }

        // Quick FTS Suggestions when query is empty
        AnimatedVisibility(visible = state.query.isBlank()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp)
            ) {
                items(QUICK_FTS_SUGGESTIONS, key = { it }) { term ->
                    SuggestionChip(
                        onClick = { onQueryChange(term) },
                        label = { Text(term, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (state.results.isEmpty() && !state.isSearching) {
            SearchEmptyCard(
                hasQuery = state.query.isNotBlank(),
                stoppedByUser = state.searchStoppedByUser,
                onRetry = onTriggerSearchNow
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("search_results_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(
                    items = state.results,
                    key = { it.id },
                    contentType = { it.fileCategory }
                ) { item ->
                    SearchProjectionRowCard(
                        item = item,
                        onClick = {
                            scope.launch {
                                inspectedFile = onLoadFullFile(item.id)
                            }
                        }
                    )
                }

                if (state.isLoadingMore) {
                    item(key = "loading_more_footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        }
                    }
                } else if (state.hasMore) {
                    item(key = "load_more_button") {
                        OutlinedButton(
                            onClick = onLoadMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("search_load_more_button")
                        ) {
                            Text("Load More (${state.results.size} of ${state.totalMatches})")
                        }
                    }
                }
            }
        }
    }

    inspectedFile?.let { file ->
        ModalBottomSheet(
            onDismissRequest = { inspectedFile = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = searchIconForCategory(file.fileCategory),
                        contentDescription = file.fileCategory,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.fileName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${file.fileCategory} • ${searchFormatBytes(file.sizeBytes)} • ${searchFormatDate(file.lastModifiedMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = file.filePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                )

                val imageModel = remember(file.filePath) {
                    when {
                        file.filePath.startsWith("content://") -> Uri.parse(file.filePath)
                        File(file.filePath).exists() -> File(file.filePath)
                        else -> null
                    }
                }
                if (file.fileCategory == "IMAGE" && imageModel != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    AsyncImage(
                        model = imageModel,
                        contentDescription = file.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Extracted OCR / Document Text",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = file.extractedText.ifBlank { "No embedded or OCR text detected in this file." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            inspectedFile = null
                            onAskAgentAboutFile(file)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("search_ask_agent_button")
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ask Agent")
                    }
                    OutlinedButton(
                        onClick = {
                            inspectedFile = null
                            onSafeTrashFile(file)
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("search_trash_button"),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Move to safe trash", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Trash")
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SearchProjectionRowCard(
    item: FileSearchListItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("search_result_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val thumbModel = remember(item.filePath, item.fileCategory) {
                if (item.fileCategory != "IMAGE") null
                else if (item.filePath.startsWith("content://")) Uri.parse(item.filePath)
                else File(item.filePath).takeIf { it.exists() }
            }
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = searchIconForCategory(item.fileCategory),
                            contentDescription = item.fileCategory,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.parentDir.substringAfterLast('/')} • ${searchFormatBytes(item.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.snippetPreview.isNotBlank()) {
                    Text(
                        text = item.snippetPreview.replace("\n", " ").trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = item.fileCategory,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (item.hasEmbedding) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "VEC",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyCard(
    hasQuery: Boolean,
    stoppedByUser: Boolean,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search vault",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = when {
                    stoppedByUser -> "Search Stopped"
                    hasQuery -> "No FTS4 Matches Found"
                    else -> "No Indexed Files Yet"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when {
                    stoppedByUser -> "Tap below to run the search query again."
                    hasQuery -> "Try shorter prefix keywords or enable EmbeddingGemma Hybrid search."
                    else -> "Use Explorer scan options (1 Media, Multiple Media, Full Storage, or Auto-Scan) to index files."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (stoppedByUser) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onRetry) {
                    Text("Run Search")
                }
            }
        }
    }
}

private fun searchIconForCategory(category: String): ImageVector = when (category) {
    "IMAGE" -> Icons.Filled.Image
    "PDF" -> Icons.Filled.PictureAsPdf
    "DOCUMENT" -> Icons.Filled.Description
    "AUDIO" -> Icons.Filled.AudioFile
    "VIDEO" -> Icons.Filled.VideoFile
    "ARCHIVE" -> Icons.Filled.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun searchFormatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun searchFormatDate(ms: Long): String {
    if (ms <= 0L) return "Unknown"
    return SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(ms))
}
