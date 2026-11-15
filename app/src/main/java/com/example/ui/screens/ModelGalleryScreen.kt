package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.engine.EmbeddingRuntimeStatus
import com.example.ai.engine.EmbeddingTelemetry
import com.example.ai.engine.EngineRuntimeStatus
import com.example.ai.engine.LlmTelemetry
import com.example.data.db.ModelRecordEntity
import com.example.operations.OperationProgress
import com.example.operations.OperationType
import com.example.ui.components.OperationControlBar
import java.util.Locale

@Composable
fun ModelGalleryScreen(
    models: List<ModelRecordEntity>,
    telemetry: LlmTelemetry,
    embeddingTelemetry: EmbeddingTelemetry,
    indexedCount: Int,
    embeddedCount: Int,
    isVectorizing: Boolean,
    vectorizeStatusMessage: String,
    operationStates: Map<OperationType, OperationProgress>,
    downloadProgress: Map<String, Int>,
    onDownloadModel: (ModelRecordEntity) -> Unit,
    onPauseDownload: (ModelRecordEntity) -> Unit,
    onResumeDownload: (ModelRecordEntity) -> Unit,
    onCancelDownload: (String) -> Unit,
    onLoadModel: (ModelRecordEntity) -> Unit,
    onUnloadModel: () -> Unit,
    onLoadEmbeddingModel: (ModelRecordEntity) -> Unit,
    onUnloadEmbeddingModel: () -> Unit,
    onBuildVectors: () -> Unit,
    onPauseOperation: (OperationType) -> Unit,
    onResumeOperation: (OperationType) -> Unit,
    onStopOperation: (OperationType) -> Unit,
    onDeleteModel: (ModelRecordEntity) -> Unit,
    onImportLocalUri: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportLocalUri(uri)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)
    ) {
        // Hero Banner inspired by Google AI Edge Gallery
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(MaterialTheme.shapes.large)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        MaterialTheme.shapes.large
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_edge_hero_1794649652645),
                    contentDescription = "On-device neural inference runtime banner",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x55070B14),
                                    Color(0xE6070B14)
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.align(Alignment.BottomStart)) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "100% AIR-GAPPED • LITERT-LM / MEDIAPIPE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Offline Edge Model Gallery",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Text(
                            text = "Weights stored privately in app filesDir. Zero cloud inference.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }
            }
        }

        // Real-Time LLM Telemetry Card
        item {
            TelemetryStripCard(
                telemetry = telemetry,
                onUnloadModel = onUnloadModel
            )
        }

        // EmbeddingGemma Vector Search Telemetry Card
        item {
            EmbeddingTelemetryCard(
                embeddingTelemetry = embeddingTelemetry,
                indexedCount = indexedCount,
                embeddedCount = embeddedCount,
                isVectorizing = isVectorizing,
                vectorizeStatusMessage = vectorizeStatusMessage,
                vectorizeOperation = operationStates[OperationType.VECTORIZE]
                    ?: OperationProgress(type = OperationType.VECTORIZE),
                onBuildVectors = onBuildVectors,
                onUnloadEmbeddingModel = onUnloadEmbeddingModel,
                onPauseVectorize = { onPauseOperation(OperationType.VECTORIZE) },
                onResumeVectorize = { onResumeOperation(OperationType.VECTORIZE) },
                onStopVectorize = { onStopOperation(OperationType.VECTORIZE) }
            )
        }

        // Import Existing .litertlm / .task File from Device
        item {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("import_model_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "Import local model", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import Local .litertlm or .task File into Sandbox")
            }
        }

        val embeddingModels = models.filter { it.modelRole == "EMBEDDING" }
        val llmModels = models.filter { it.modelRole != "EMBEDDING" }

        if (embeddingModels.isNotEmpty()) {
            item {
                Text(
                    text = "Embedding Models (Instant Hybrid Search)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(embeddingModels, key = { it.modelId }) { model ->
                ModelEntryCard(
                    model = model,
                    isLoaded = embeddingTelemetry.loadedModelId == model.modelId &&
                        (embeddingTelemetry.status == EmbeddingRuntimeStatus.READY ||
                            embeddingTelemetry.status == EmbeddingRuntimeStatus.EMBEDDING),
                    isLoading = embeddingTelemetry.loadedModelId == model.modelId &&
                        embeddingTelemetry.status == EmbeddingRuntimeStatus.LOADING,
                    progressPct = downloadProgress[model.modelId] ?: 0,
                    onDownload = { onDownloadModel(model) },
                    onPauseDownload = { onPauseDownload(model) },
                    onResumeDownload = { onResumeDownload(model) },
                    onCancel = { onCancelDownload(model.modelId) },
                    onLoad = { onLoadEmbeddingModel(model) },
                    onUnload = onUnloadEmbeddingModel,
                    onDelete = { onDeleteModel(model) }
                )
            }
        }

        item {
            Text(
                text = "Agent LLMs (Offline ReAct Brain)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // LLM Model Cards
        items(llmModels, key = { it.modelId }) { model ->
            ModelEntryCard(
                model = model,
                isLoaded = telemetry.loadedModelId == model.modelId && telemetry.status == EngineRuntimeStatus.READY,
                isLoading = telemetry.loadedModelId == model.modelId && telemetry.status == EngineRuntimeStatus.LOADING,
                progressPct = downloadProgress[model.modelId] ?: 0,
                onDownload = { onDownloadModel(model) },
                onPauseDownload = { onPauseDownload(model) },
                onResumeDownload = { onResumeDownload(model) },
                onCancel = { onCancelDownload(model.modelId) },
                onLoad = { onLoadModel(model) },
                onUnload = onUnloadModel,
                onDelete = { onDeleteModel(model) }
            )
        }
    }
}

@Composable
private fun EmbeddingTelemetryCard(
    embeddingTelemetry: EmbeddingTelemetry,
    indexedCount: Int,
    embeddedCount: Int,
    isVectorizing: Boolean,
    vectorizeStatusMessage: String,
    vectorizeOperation: OperationProgress,
    onBuildVectors: () -> Unit,
    onUnloadEmbeddingModel: () -> Unit,
    onPauseVectorize: () -> Unit,
    onResumeVectorize: () -> Unit,
    onStopVectorize: () -> Unit
) {
    val isReady = embeddingTelemetry.status == EmbeddingRuntimeStatus.READY ||
        embeddingTelemetry.status == EmbeddingRuntimeStatus.EMBEDDING

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Filled.Hub,
                        contentDescription = "EmbeddingGemma telemetry",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = embeddingTelemetry.loadedModelName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = embeddingTelemetry.statusNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (isReady) {
                    OutlinedButton(
                        onClick = onUnloadEmbeddingModel,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("unload_embedding_button")
                    ) {
                        Text("Unload")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryMetricPill(
                    label = "DIMS",
                    value = if (embeddingTelemetry.vectorDimensions > 0) "${embeddingTelemetry.vectorDimensions}-d" else "—",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricPill(
                    label = "VECTORS",
                    value = "$embeddedCount / $indexedCount",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricPill(
                    label = "LATENCY",
                    value = "${embeddingTelemetry.lastLatencyMs} ms",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricPill(
                    label = "DELEGATE",
                    value = embeddingTelemetry.activeDelegate.label,
                    modifier = Modifier.weight(1f)
                )
            }

            if (vectorizeOperation.isActiveOrPaused) {
                Spacer(modifier = Modifier.height(10.dp))
                OperationControlBar(
                    operation = vectorizeOperation,
                    onPause = onPauseVectorize,
                    onResume = onResumeVectorize,
                    onStop = onStopVectorize
                )
            }

            if (vectorizeStatusMessage.isNotBlank() && !vectorizeOperation.isActiveOrPaused) {
                Text(
                    text = vectorizeStatusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (isReady && !vectorizeOperation.isActiveOrPaused) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onBuildVectors,
                    enabled = !isVectorizing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("build_vectors_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Build Vectors for Indexed Files")
                }
            }
        }
    }
}

@Composable
private fun TelemetryStripCard(
    telemetry: LlmTelemetry,
    onUnloadModel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = "Runtime telemetry",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = telemetry.loadedModelName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = telemetry.statusNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (telemetry.status == EngineRuntimeStatus.READY) {
                    OutlinedButton(
                        onClick = onUnloadModel,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("unload_model_button")
                    ) {
                        Text("Unload")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryMetricPill(
                    label = "SPEED",
                    value = String.format(Locale.US, "%.1f tok/s", telemetry.tokensPerSecond),
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricPill(
                    label = "APP PSS RAM",
                    value = "${telemetry.appPssRamMb} MB",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricPill(
                    label = "FREE SYS RAM",
                    value = "${telemetry.systemAvailableRamMb} MB",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricPill(
                    label = "DELEGATE",
                    value = telemetry.activeDelegate.label,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ModelEntryCard(
    model: ModelRecordEntity,
    isLoaded: Boolean,
    isLoading: Boolean,
    progressPct: Int,
    onDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancel: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isLoaded) 1.5.dp else 1.dp,
                color = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${model.format} • ${model.expectedSizeBytes / (1024L * 1024L)} MB • Rec: ${model.recommendedBackend}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (isLoaded) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Active model",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (model.status == "DOWNLOADING" || model.status == "VERIFYING" || model.status == "PAUSED") {
                val computedPct = if (progressPct > 0) {
                    progressPct
                } else if (model.expectedSizeBytes > 0L) {
                    ((model.downloadedBytes * 100L) / model.expectedSizeBytes).toInt().coerceIn(0, 100)
                } else 0

                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (computedPct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (model.status == "PAUSED") {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Text(
                    text = when (model.status) {
                        "VERIFYING" -> "Verifying SHA-256 & integrity..."
                        "PAUSED" -> "Paused at $computedPct% (${model.downloadedBytes / (1024 * 1024)} MB saved)"
                        else -> "Downloading into private filesDir ($computedPct%)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (model.verifiedSha256.isNotBlank()) {
                Text(
                    text = "SHA-256: ${model.verifiedSha256.take(16)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (model.status) {
                    "READY" -> {
                        Button(
                            onClick = if (isLoaded) onUnload else onLoad,
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("load_model_${model.modelId}"),
                            colors = if (isLoaded) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            Icon(
                                imageVector = if (isLoaded) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                when {
                                    isLoading -> "Initializing..."
                                    isLoaded -> "Unload Model"
                                    else -> "Load into Engine"
                                }
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("delete_model_${model.modelId}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete model file",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    "DOWNLOADING", "VERIFYING" -> {
                        OutlinedButton(
                            onClick = onPauseDownload,
                            enabled = model.status == "DOWNLOADING",
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("pause_model_${model.modelId}")
                        ) {
                            Text("Pause")
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("cancel_model_${model.modelId}"),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop")
                        }
                    }
                    "PAUSED" -> {
                        Button(
                            onClick = onResumeDownload,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("resume_model_${model.modelId}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Resume model download",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume")
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("cancel_model_${model.modelId}"),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop model download",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop")
                        }
                    }
                    else -> {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("download_model_${model.modelId}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudDownload,
                                contentDescription = "Download model",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("One-Tap Download")
                        }
                    }
                }
            }
        }
    }
}
