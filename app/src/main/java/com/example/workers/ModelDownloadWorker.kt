package com.example.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.EdgeClawApplication
import com.example.ai.engine.ModelCatalog
import com.example.data.db.EdgeClawDatabase
import com.example.operations.OperationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val app = applicationContext as? EdgeClawApplication
        val opControl = app?.container?.operationControlManager
        val db = EdgeClawDatabase.getInstance(applicationContext)
        val modelDao = db.modelRecordDao()
        val record = modelDao.getByModelId(modelId) ?: return@withContext Result.failure()

        val targetDir = ModelCatalog.modelsDir(applicationContext)
        val partFile = File(targetDir, "${record.fileName}.part")
        val finalFile = File(targetDir, record.fileName)

        val existingPartBytes = if (partFile.exists()) partFile.length() else 0L
        val opType = OperationType.MODEL_DOWNLOAD
        opControl?.start(
            type = opType,
            total = 100,
            detail = "Downloading ${record.displayName}",
            subjectId = modelId
        )

        setForeground(createForegroundInfo("Downloading ${record.displayName}", 0))
        modelDao.updateDownloadState(
            modelId = modelId,
            status = "DOWNLOADING",
            downloadedBytes = existingPartBytes,
            localPath = finalFile.absolutePath
        )

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        return@withContext try {
            val requestBuilder = Request.Builder()
                .url(record.downloadUrl)
                .header("User-Agent", "EdgeClaw-Android/1.0")
            if (existingPartBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingPartBytes-")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 416) {
                    modelDao.updateDownloadState(modelId, "FAILED", 0L, "")
                    opControl?.fail(opType, "HTTP ${response.code}: ${response.message}")
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "HTTP ${response.code}: ${response.message}")
                    )
                }

                val isResumedPartial = response.code == 206 && existingPartBytes > 0L
                val appendMode = isResumedPartial
                var totalRead = if (isResumedPartial) existingPartBytes else 0L

                val body = response.body ?: run {
                    modelDao.updateDownloadState(modelId, "FAILED", 0L, "")
                    opControl?.fail(opType, "Empty response body")
                    return@withContext Result.failure(workDataOf(KEY_ERROR to "Empty response body"))
                }

                val remainingLength = body.contentLength()
                val contentLength = when {
                    isResumedPartial && remainingLength > 0L -> existingPartBytes + remainingLength
                    remainingLength > 0L -> remainingLength
                    else -> record.expectedSizeBytes
                }

                var lastNotifyTime = 0L

                body.byteStream().use { input ->
                    FileOutputStream(partFile, appendMode).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            // Pause support: preserve .part file and exit cleanly until resumed
                            if (opControl?.isPaused(opType) == true) {
                                output.flush()
                                val pct = computePercent(totalRead, contentLength)
                                setProgress(workDataOf(KEY_PROGRESS to pct, KEY_BYTES_READ to totalRead))
                                modelDao.updateDownloadState(
                                    modelId = modelId,
                                    status = "PAUSED",
                                    downloadedBytes = totalRead,
                                    localPath = finalFile.absolutePath
                                )
                                opControl.pause(opType, "Paused ${record.displayName} at $pct%")
                                return@withContext Result.success(
                                    workDataOf(KEY_PAUSED to true, KEY_PROGRESS to pct)
                                )
                            }

                            // Stop support: delete .part file and abort
                            if (isStopped || opControl?.isStopped(opType) == true) {
                                output.flush()
                                partFile.delete()
                                modelDao.updateDownloadState(modelId, "NOT_DOWNLOADED", 0L, "")
                                opControl?.stop(opType, "Stopped downloading ${record.displayName}")
                                return@withContext Result.failure()
                            }

                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            totalRead += read

                            val now = System.currentTimeMillis()
                            if (now - lastNotifyTime > 500L) {
                                lastNotifyTime = now
                                val pct = computePercent(totalRead, contentLength)
                                setProgress(workDataOf(KEY_PROGRESS to pct, KEY_BYTES_READ to totalRead))
                                setForeground(createForegroundInfo("Downloading ${record.displayName} ($pct%)", pct))
                                opControl?.updateProgress(
                                    type = opType,
                                    processed = pct,
                                    total = 100,
                                    detail = "${record.displayName} ($pct%)"
                                )
                                modelDao.updateDownloadState(modelId, "DOWNLOADING", totalRead, finalFile.absolutePath)
                            }
                        }
                        output.flush()
                    }
                }

                modelDao.updateDownloadState(modelId, "VERIFYING", totalRead, finalFile.absolutePath)
                setForeground(createForegroundInfo("Verifying ${record.displayName}", 100))
                opControl?.updateProgress(opType, 100, 100, "Verifying SHA-256 for ${record.displayName}")

                if (totalRead < 1024L * 1024L) {
                    partFile.delete()
                    modelDao.updateDownloadState(modelId, "FAILED", 0L, "")
                    opControl?.fail(opType, "Downloaded file too small ($totalRead bytes)")
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "Downloaded file too small ($totalRead bytes); check URL or Hugging Face access.")
                    )
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                val sha256Hex = computeFileSha256(finalFile)
                modelDao.updateDownloadState(
                    modelId = modelId,
                    status = "READY",
                    downloadedBytes = finalFile.length(),
                    localPath = finalFile.absolutePath,
                    sha256 = sha256Hex
                )
                opControl?.complete(opType, "Ready: ${record.displayName}")
                Result.success(workDataOf(KEY_LOCAL_PATH to finalFile.absolutePath))
            }
        } catch (t: Throwable) {
            // Preserve partial file only if paused; clean up on network/IO failure
            if (opControl?.isPaused(opType) == true) {
                val bytes = if (partFile.exists()) partFile.length() else 0L
                val pct = computePercent(bytes, record.expectedSizeBytes)
                modelDao.updateDownloadState(modelId, "PAUSED", bytes, finalFile.absolutePath)
                Result.success(workDataOf(KEY_PAUSED to true, KEY_PROGRESS to pct))
            } else {
                partFile.delete()
                modelDao.updateDownloadState(modelId, "FAILED", 0L, "")
                opControl?.fail(opType, t.message ?: "Download failed")
                Result.failure(workDataOf(KEY_ERROR to (t.message ?: "Download failed")))
            }
        }
    }

    private fun computePercent(read: Long, total: Long): Int =
        if (total > 0L) ((read * 100L) / total).toInt().coerceIn(0, 100) else 0

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r == -1) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offline Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("EdgeClaw Model Manager")
            .setContentText(title)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_READ = "bytes_read"
        const val KEY_LOCAL_PATH = "local_path"
        const val KEY_ERROR = "error"
        const val KEY_PAUSED = "paused"
        private const val CHANNEL_ID = "edgeclaw_model_downloads"
        private const val NOTIFICATION_ID = 4001
    }
}
