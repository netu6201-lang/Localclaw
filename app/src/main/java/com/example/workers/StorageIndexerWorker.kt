package com.example.workers

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.EdgeClawApplication
import com.example.ai.agent.SandboxGuardrails
import com.example.ai.engine.EmbeddingRuntimeStatus
import com.example.ai.engine.VectorMath
import com.example.data.db.EdgeClawDatabase
import com.example.data.db.FileIndexEntity
import com.example.data.preferences.AppPreferences
import com.example.operations.OperationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class StorageIndexerWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? EdgeClawApplication
        val db = EdgeClawDatabase.getInstance(applicationContext)
        val fileDao = db.fileIndexDao()
        val prefs = AppPreferences(applicationContext)
        val guardrails = SandboxGuardrails(applicationContext)
        val embeddingManager = app?.container?.embeddingEngineManager
        val extractor = app?.container?.mediaExtractor ?: MediaFileExtractor(applicationContext)
        val opControl = app?.container?.operationControlManager

        val isAutoTriggered = inputData.getBoolean(KEY_AUTO_TRIGGERED, false)
        val opType = OperationType.STORAGE_SCAN
        opControl?.start(
            type = opType,
            total = 0,
            detail = if (isAutoTriggered) "Auto-scanning new media..." else "Discovering local files..."
        )

        val discoveredPaths = LinkedHashMap<String, File>()

        // 1. Scan key public directories directly when MANAGE_EXTERNAL_STORAGE is active
        val extRoot = Environment.getExternalStorageDirectory()
        val candidateDirs = listOf(
            File(extRoot, "Download"),
            File(extRoot, "Documents"),
            File(extRoot, "Pictures"),
            File(extRoot, "DCIM"),
            File(extRoot, "Music"),
            File(extRoot, "Movies")
        )
        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown()
                .maxDepth(5)
                .onEnter { sub ->
                    val name = sub.name
                    !name.startsWith(".") &&
                        sub.absolutePath != guardrails.safeTrashRoot.absolutePath &&
                        !sub.absolutePath.contains("/Android/data") &&
                        !sub.absolutePath.contains("/Android/obb")
                }
                .filter { it.isFile && it.length() > 0L }
                .take(400)
                .forEach { file ->
                    discoveredPaths[file.absolutePath] = file
                }
        }

        // 2. Supplement with MediaStore query for images/video/audio
        scanMediaStore(discoveredPaths)

        val total = discoveredPaths.size.coerceAtLeast(1)
        var processed = 0
        val batch = mutableListOf<FileIndexEntity>()
        opControl?.updateProgress(opType, 0, total, "Indexing $total discovered file(s)...")

        try {
            for ((_, file) in discoveredPaths) {
                if (isStopped) break
                opControl?.checkpoint(opType)

                processed++
                opControl?.updateProgress(opType, processed, total, "Scanning ${file.name}")

                val embedTelemetry = embeddingManager?.telemetry?.value
                val canEmbedNow = embedTelemetry?.status == EmbeddingRuntimeStatus.READY
                val activeEmbedModelId = embedTelemetry?.loadedModelId.orEmpty()

                val existing = fileDao.getByPath(file.absolutePath)
                if (existing != null &&
                    existing.lastModifiedMs == file.lastModified() &&
                    existing.sizeBytes == file.length()
                ) {
                    if (canEmbedNow && activeEmbedModelId.isNotBlank() &&
                        (existing.embeddingVector == null || existing.embeddingModelId != activeEmbedModelId)
                    ) {
                        val imageBytes = if (existing.fileCategory == "IMAGE") {
                            extractor.loadDownsampledImageBytesFromFile(file)
                        } else {
                            null
                        }
                        val vec = embeddingManager?.embedDocument(
                            title = existing.fileName,
                            content = existing.extractedText,
                            imageBytes = imageBytes
                        )
                        if (vec != null && vec.isNotEmpty()) {
                            fileDao.updateFileEmbedding(
                                fileId = existing.id,
                                vector = VectorMath.floatArrayToBytes(vec),
                                modelId = activeEmbedModelId
                            )
                        }
                    }
                    continue
                }

                batch += extractor.extractFileEntity(file, existing, embeddingManager)

                if (batch.size >= 15) {
                    fileDao.upsertFiles(batch.toList())
                    batch.clear()
                    setProgress(workDataOf(KEY_INDEXED_COUNT to processed, KEY_TOTAL_COUNT to total))
                }
            }

            if (batch.isNotEmpty()) {
                fileDao.upsertFiles(batch.toList())
                batch.clear()
            }
            prefs.setLastIndexedMs(System.currentTimeMillis())

            if (prefs.settingsFlow.first().autoScanNewMedia) {
                app?.container?.mediaAutoScanScheduler?.armWorkManagerTrigger()
            }

            opControl?.complete(opType, "Indexed $processed / $total file(s)")
            Result.success(workDataOf(KEY_INDEXED_COUNT to processed, KEY_TOTAL_COUNT to total))
        } catch (_: CancellationException) {
            if (batch.isNotEmpty()) {
                runCatching { fileDao.upsertFiles(batch.toList()) }
            }
            opControl?.stop(opType, "Scan stopped ($processed / $total saved)")
            Result.failure()
        } catch (t: Throwable) {
            if (batch.isNotEmpty()) {
                runCatching { fileDao.upsertFiles(batch.toList()) }
            }
            opControl?.fail(opType, t.message ?: "Indexing error")
            Result.failure()
        }
    }

    private fun scanMediaStore(outMap: MutableMap<String, File>) {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )
        for (uri in uris) {
            runCatching {
                applicationContext.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataCol < 0) return@use
                    var count = 0
                    while (cursor.moveToNext() && count < 250) {
                        val path = cursor.getString(dataCol) ?: continue
                        val file = File(path)
                        if (file.exists() && file.isFile && !path.contains(".agent_trash")) {
                            outMap.putIfAbsent(file.absolutePath, file)
                            count++
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_INDEXED_COUNT = "indexed_count"
        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_AUTO_TRIGGERED = "auto_triggered"
        const val TAG_INDEXER_WORK = "edgeclaw_indexer"
        const val UNIQUE_INDEXER_WORK = "edgeclaw_storage_indexer"
    }
}
