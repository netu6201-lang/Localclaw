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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ai.engine.EmbeddingRuntimeStatus
import com.example.ai.engine.EmbeddingTelemetry
import com.example.data.db.FileIndexEntity
import com.example.operations.OperationProgress
import com.example.operations.OperationType
import com.example.ui.components.OperationControlBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CATEGORIES = listOf("ALL", "IMAGE", "PDF", "DOCUMENT", "AUDIO", "VIDEO", "ARCHIVE")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    files: List<FileIndexEntity>,
    indexedCount: Int,
    embeddedCount: Int,
    embeddingTelemetry: EmbeddingTelemetry,
    searchQuery: String,
    selectedCategory: String,
    isIndexing: Boolean,
    indexStatusMessage: String,
    operationStates: Map<OperationType, OperationProgress>,
    onSearchQueryChange: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
    onStartIndexing: () -> Unit,
    onOpenSettings: () -> Unit,
    onPauseOperation: (OperationType) -> Unit,
    onResumeOperation: (OperationType) -> Unit,
    onStopOperation: (OperationType) -> Unit,
    onRequestMediaPermissions: () -> Unit,
    onAskAgentAboutFile: (FileIndexEntity) -> Unit,
    onSafeTrashFile: (FileIndexEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var inspectedFile by remember { mutableStateOf<FileIndexEntity?>(null) }
    val isHybridActive = embeddingTelemetry.status == EmbeddingRuntimeStatus.READY ||
        embeddingTelemetry.status == EmbeddingRuntimeStatus.EMBEDDING

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Clean Header + Quick Scan & Scan Settings Shortcut
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Local Vault",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "$indexedCount files • $embeddedCount vectorized",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("explorer_scan_settings_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "Open scan settings",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan Mode")
                }
                Button(
                    onClick = {
                        onRequestMediaPermissions()
                        onStartIndexing()
                    },
                    enabled = !isIndexing,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("explorer_quick_scan_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Run quick storage scan",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isIndexing) "Scanning..." else "Scan")
                }
            }
        }

        // Active Pause / Resume / Stop Operation Bars for Storage Scan & Selected Media Scan
        listOf(OperationType.STORAGE_SCAN, OperationType.MEDIA_PICK_SCAN).forEach { opType ->
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

        AnimatedVisibility(visible = !isIndexing && indexStatusMessage.isNotBlank()) {
            Text(
                text = indexStatusMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Instant Hybrid / FTS4 Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("explorer_search_input"),
            placeholder = {
                Text(
                    text = if (isHybridActive) {
                        "Hybrid search (semantic meaning + OCR + PDFs)..."
                    } else {
                        "Instant FTS4 search (OCR text, PDFs, filenames)..."
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Search files")
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Active Search Engine Mode Pill
        Surface(
            color = if (isHybridActive) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Hub,
                    contentDescription = "Search mode",
                    tint = if (isHybridActive) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isHybridActive) {
                        "EmbeddingGemma Hybrid (${embeddingTelemetry.vectorDimensions}-dim) + FTS4"
                    } else {
                        "FTS4 Keyword Index • Load EmbeddingGemma in Models tab for semantic search"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHybridActive) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(CATEGORIES) { category ->
                val selected = selectedCategory == category
                FilterChip(
                    selected = selected,
                    onClick = { onSelectCategory(category) },
                    label = {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("category_chip_${category.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (files.isEmpty()) {
            EmptyExplorerCard(
                hasQuery = searchQuery.isNotBlank(),
                onScanClick = {
                    onRequestMediaPermissions()
                    onStartIndexing()
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(files, key = { it.filePath }) { file ->
                    FileIndexRowCard(
                        file = file,
                        onClick = { inspectedFile = file }
                    )
                }
            }
        }
    }

    // File Inspector Bottom Sheet
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
                        imageVector = iconForCategory(file.fileCategory),
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
                            text = "${file.fileCategory} • ${formatBytes(file.sizeBytes)} • ${formatDate(file.lastModifiedMs)}",
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
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
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
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = file.extractedText.ifBlank {
                            "No embedded or OCR text detected in this file."
                        },
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
                            .testTag("ask_agent_about_file_button")
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
                            .testTag("safe_trash_file_button"),
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
private fun FileIndexRowCard(
    file: FileIndexEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onClick)
            .testTag("file_card_${file.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val thumbModel = remember(file.filePath, file.fileCategory) {
                if (file.fileCategory != "IMAGE") null
                else if (file.filePath.startsWith("content://")) Uri.parse(file.filePath)
                else File(file.filePath).takeIf { it.exists() }
            }
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = file.fileName,
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
                            imageVector = iconForCategory(file.fileCategory),
                            contentDescription = file.fileCategory,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${file.parentDir.substringAfterLast('/')} • ${formatBytes(file.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.extractedText.isNotBlank()) {
                    Text(
                        text = file.extractedText.replace("\n", " ").trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = file.fileCategory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyExplorerCard(
    hasQuery: Boolean,
    onScanClick: () -> Unit
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
                imageVector = Icons.Filled.Security,
                contentDescription = "Offline Vault",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (hasQuery) "No Matching Files Found" else "Local Index Ready",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (hasQuery) {
                    "Try a different keyword or run Scan & OCR to index newly added documents and screenshots."
                } else {
                    "Tap Scan & OCR to index MediaStore photos, PDFs, and Downloads locally with ML Kit OCR."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasQuery) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(onClick = onScanClick) {
                    Text("Scan & Index Device Storage")
                }
            }
        }
    }
}

private fun iconForCategory(category: String): ImageVector = when (category) {
    "IMAGE" -> Icons.Filled.Image
    "PDF" -> Icons.Filled.PictureAsPdf
    "DOCUMENT" -> Icons.Filled.Description
    "AUDIO" -> Icons.Filled.AudioFile
    "VIDEO" -> Icons.Filled.VideoFile
    "ARCHIVE" -> Icons.Filled.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun formatDate(ms: Long): String {
    if (ms <= 0L) return "Unknown"
    return SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(ms))
}
