package com.example.workers

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Watches MediaStore URIs (Images, Video, Audio) via both a live ContentObserver and
 * WorkManager ContentUriTriggers so newly captured/downloaded media is scanned automatically.
 */
class MediaAutoScanScheduler(private val context: Context) {

    private val workManager: WorkManager?
        get() = runCatching { WorkManager.getInstance(context) }.getOrNull()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isObserverRegistered = false

    private val debounceRunnable = Runnable {
        enqueueIncrementalAutoScan()
    }

    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            mainHandler.removeCallbacks(debounceRunnable)
            mainHandler.postDelayed(debounceRunnable, 1800L)
        }
    }

    fun setAutoScanEnabled(enabled: Boolean) {
        if (enabled) {
            registerObserver()
            armWorkManagerTrigger()
        } else {
            unregisterObserver()
            workManager?.cancelUniqueWork(UNIQUE_AUTO_TRIGGER_WORK)
        }
    }

    fun armWorkManagerTrigger() {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(2L, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(10L, TimeUnit.SECONDS)
            .build()

        val request = OneTimeWorkRequestBuilder<StorageIndexerWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(StorageIndexerWorker.KEY_AUTO_TRIGGERED to true))
            .addTag(TAG_AUTO_SCAN_WORK)
            .build()

        workManager?.enqueueUniqueWork(
            UNIQUE_AUTO_TRIGGER_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun enqueueIncrementalAutoScan() {
        val request = OneTimeWorkRequestBuilder<StorageIndexerWorker>()
            .setInputData(workDataOf(StorageIndexerWorker.KEY_AUTO_TRIGGERED to true))
            .addTag(StorageIndexerWorker.TAG_INDEXER_WORK)
            .build()

        workManager?.enqueueUniqueWork(
            StorageIndexerWorker.UNIQUE_INDEXER_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun registerObserver() {
        if (isObserverRegistered) return
        val resolver = context.contentResolver
        runCatching {
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
            isObserverRegistered = true
        }
    }

    private fun unregisterObserver() {
        if (!isObserverRegistered) return
        runCatching {
            context.contentResolver.unregisterContentObserver(mediaObserver)
        }
        mainHandler.removeCallbacks(debounceRunnable)
        isObserverRegistered = false
    }

    companion object {
        const val UNIQUE_AUTO_TRIGGER_WORK = "edgeclaw_media_auto_trigger"
        const val TAG_AUTO_SCAN_WORK = "edgeclaw_auto_scan"
    }
}
