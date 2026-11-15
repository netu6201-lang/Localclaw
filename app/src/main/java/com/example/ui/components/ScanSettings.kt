package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.preferences.ScanMode
import com.example.operations.OperationProgress
import com.example.operations.OperationType

/**
 * Clean, user-friendly ScanSettings Compose component allowing users to switch between:
 * - Manual Selection (individual media item via Android Photo Picker)
 * - Auto-Scan (automatic watcher for newly arrived photos, screenshots & videos)
 * - Batch Scan (multiple selected media items or full storage folder scan)
 */
@Composable
fun ScanSettings(
    selectedMode: ScanMode,
    isScanning: Boolean,
    operationStates: Map<OperationType, OperationProgress>,
    onSelectMode: (ScanMode) -> Unit,
    onScanSelectedMedia: (List<Uri>) -> Unit,
    onStartFullBatchScan: () -> Unit,
    onPauseOperation: (OperationType) -> Unit,
    onResumeOperation: (OperationType) -> Unit,
    onStopOperation: (OperationType) -> Unit,
    onRequestMediaPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val singleMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onScanSelectedMedia(listOf(uri))
        }
    }

    val multiMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onScanSelectedMedia(uris)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .testTag("scan_settings_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Media & File Scan Mode",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Choose how EdgeClaw scans your media and documents",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3-Mode Toggle Selector: Manual Selection | Auto-Scan | Batch Scan
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScanModeOptionTile(
                    mode = ScanMode.MANUAL,
                    title = "Manual Selection",
                    subtitle = "1 Media",
                    icon = Icons.Filled.PhotoLibrary,
                    selected = selectedMode == ScanMode.MANUAL,
                    onClick = { onSelectMode(ScanMode.MANUAL) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_mode_manual")
                )
                ScanModeOptionTile(
                    mode = ScanMode.AUTO_SCAN,
                    title = "Auto-Scan",
                    subtitle = "New Arrivals",
                    icon = Icons.Filled.AutoMode,
                    selected = selectedMode == ScanMode.AUTO_SCAN,
                    onClick = {
                        onRequestMediaPermissions()
                        onSelectMode(ScanMode.AUTO_SCAN)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_mode_auto")
                )
                ScanModeOptionTile(
                    mode = ScanMode.BATCH,
                    title = "Batch Scan",
                    subtitle = "Multi Files",
                    icon = Icons.Filled.Collections,
                    selected = selectedMode == ScanMode.BATCH,
                    onClick = { onSelectMode(ScanMode.BATCH) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_mode_batch")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Contextual Action Area for Selected Mode
            AnimatedContent(
                targetState = selectedMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "scan_mode_content"
            ) { mode ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        when (mode) {
                            ScanMode.MANUAL -> {
                                Text(
                                    text = "Manual Selection (Individual Media)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Pick one photo, screenshot, or video to run instant OCR and vector indexing without full-storage scanning.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        singleMediaPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .testTag("scan_single_media_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PhotoLibrary,
                                        contentDescription = "Select individual media",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Select Individual Media")
                                }
                            }

                            ScanMode.AUTO_SCAN -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Auto-scan active",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Auto-Scan Active for New Arrivals",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Automatically watches MediaStore and scans newly saved photos, screenshots, videos, and audio in the background.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        onRequestMediaPermissions()
                                        onStartFullBatchScan()
                                    },
                                    enabled = !isScanning,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .testTag("scan_arrivals_now_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Check new media now",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isScanning) "Scanning New Arrivals..." else "Check New Arrivals Now")
                                }
                            }

                            ScanMode.BATCH -> {
                                Text(
                                    text = "Batch Scan (Multiple Files)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Select up to 50 photos/videos at once or run a batch scan across Downloads, Documents, Pictures, and DCIM.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            multiMediaPicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .minimumInteractiveComponentSize()
                                            .testTag("scan_multiple_media_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Collections,
                                            contentDescription = "Pick multiple media files",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pick Multiple", maxLines = 1)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            onRequestMediaPermissions()
                                            onStartFullBatchScan()
                                        },
                                        enabled = !isScanning,
                                        modifier = Modifier
                                            .weight(1f)
                                            .minimumInteractiveComponentSize()
                                            .testTag("scan_storage_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.FolderCopy,
                                            contentDescription = "Batch scan all folders",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isScanning) "Running..." else "All Folders", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Active Pause / Resume / Stop Operation Bars inside ScanSettings
            listOf(OperationType.STORAGE_SCAN, OperationType.MEDIA_PICK_SCAN).forEach { opType ->
                val op = operationStates[opType]
                if (op != null && op.isActiveOrPaused) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OperationControlBar(
                        operation = op,
                        onPause = { onPauseOperation(opType) },
                        onResume = { onResumeOperation(opType) },
                        onStop = { onStopOperation(opType) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanModeOptionTile(
    mode: ScanMode,
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
