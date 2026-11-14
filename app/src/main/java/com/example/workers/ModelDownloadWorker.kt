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
import com.example.ai.engine.ModelCatalog
import com.example.data.db.EdgeClawDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val db = EdgeClawDatabase.getInstance(applicationContext)
        val modelDao = db.modelRecordDao()
        val record = modelDao.getByModelId(modelId) ?: return@withContext Result.failure()

        val targetDir = ModelCatalog.modelsDir(applicationContext)
        val partFile = File(targetDir, "${record.fileName}.part")
        val finalFile = File(targetDir, record.fileName)

        setForeground(createForegroundInfo("Downloading ${record.displayName}", 0))
        modelDao.updateDownloadState(
            modelId = modelId,
            status = "DOWNLOADING",
            downloadedBytes = 0L,
            localPath = finalFile.absolutePath
        )

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        return@withContext try {
            val request = Request.Builder()
                .url(record.downloadUrl)
                .header("User-Agent", "EdgeClaw-Android/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    modelDao.updateDownloadState(modelId, "FAILED", 0L, "")
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "HTTP ${response.code}: ${response.message}")
                    )
                }

                val body = response.body ?: run {
                    modelDao.updateDownloadState(modelId, "FAILED", 0L, "")
                    return@withContext Result.failure(workDataOf(KEY_ERROR to "Empty response body"))
                }

                val contentLength = body.contentLength().takeIf { it > 0L } ?: record.expectedSizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                var totalRead = 0L
                var lastNotifyTime = 0L

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) {
                                partFile.delete()
                                modelDao.updateDownloadState(modelId, "NOT_DOWNLOADED", 0L, "")
                                return@withContext Result.failure()
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            totalRead += read

                            val now = System.currentTimeMillis()
                            if (now - lastNotifyTime > 500L) {
                                lastNotifyTime = now
                                val pct = if (contentLength > 0L) {
                                    ((totalRead * 100L) / contentLength).toInt().coerceIn(0, 100)
                                } else 0
                                setProgress(workDataOf(KEY_PROGRESS to pct, KEY_BYTES_READ to totalRead))
                                setForeground(createForegroundInfo("Downloading ${record.displayName} ($pct%)", pct))
                                modelDao.updateDownloadState(modelId, "DOWNLOADING", totalRead, finalFile.absolutePath)
                            }
                        }
                        output.flush()
                    }
                }

                modelDao.updateDownloadState(modelId, "VERIFYING", totalRead, finalFile.absolutePath)
                setForeground(createForegroundInfo("Verifying ${record.displayName}", 100))

                if (totalRead < 1024L * 1024L) {
                    // Protect against HTML/error pages saved as model files
                    partFile.delete()
                    modelDao.updateDownloadState(modelId, "FAILED", 0L, "")
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "Downloaded file too small ($totalRead bytes); check URL or Hugging Face access.")
                    )
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                modelDao.updateDownloadState(
                    modelId = modelId,
                    status = "READY",
                    downloadedBytes = finalFile.length(),
                    localPath = finalFile.absolutePath,
                    sha256 = sha256Hex
                )
                Result.success(workDataOf(KEY_LOCAL_PATH to finalFile.absolutePath))
            }
        } catch (t: Throwable) {
            partFile.delete()
            modelDao.updateDownloadState(modelId, "FAILED", 0L, "")
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: "Download failed")))
        }
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
        private const val CHANNEL_ID = "edgeclaw_model_downloads"
        private const val NOTIFICATION_ID = 4001
    }
}
