package com.example.workers

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.ai.agent.SandboxGuardrails
import com.example.data.db.EdgeClawDatabase
import com.example.data.db.FileIndexEntity
import com.example.data.preferences.AppPreferences
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class StorageIndexerWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(applicationContext)
        val db = EdgeClawDatabase.getInstance(applicationContext)
        val fileDao = db.fileIndexDao()
        val prefs = AppPreferences(applicationContext)
        val guardrails = SandboxGuardrails(applicationContext)

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

        for ((_, file) in discoveredPaths) {
            if (isStopped) break
            processed++

            val existing = fileDao.getByPath(file.absolutePath)
            if (existing != null && existing.lastModifiedMs == file.lastModified() && existing.sizeBytes == file.length()) {
                continue
            }

            val ext = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: guessMimeByExtension(ext)
            val category = categorize(ext, mime)

            val extractedText = when (category) {
                "IMAGE" -> runMlKitOcr(file)
                "PDF" -> extractPdfText(file)
                "DOCUMENT" -> extractPlainText(file)
                else -> ""
            }

            batch += FileIndexEntity(
                id = existing?.id ?: 0L,
                filePath = file.absolutePath,
                fileName = file.name,
                parentDir = file.parent ?: "",
                mimeType = mime,
                fileCategory = category,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified(),
                extractedText = extractedText.take(8000),
                isTrashed = false,
                indexedAtMs = System.currentTimeMillis()
            )

            if (batch.size >= 20) {
                fileDao.upsertFiles(batch.toList())
                batch.clear()
                setProgress(workDataOf(KEY_INDEXED_COUNT to processed, KEY_TOTAL_COUNT to total))
            }
        }

        if (batch.isNotEmpty()) {
            fileDao.upsertFiles(batch.toList())
        }
        prefs.setLastIndexedMs(System.currentTimeMillis())

        Result.success(workDataOf(KEY_INDEXED_COUNT to processed, KEY_TOTAL_COUNT to total))
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

    private suspend fun runMlKitOcr(imageFile: File): String {
        if (imageFile.length() > 15L * 1024L * 1024L) return ""
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            val sample = (maxDim / 1280).coerceAtLeast(1)
            BitmapFactory.decodeFile(
                imageFile.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull() ?: return ""

        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { cont ->
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        if (cont.isActive) cont.resume(visionText.text.trim())
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume("")
                    }
            }
        } catch (_: Throwable) {
            ""
        } finally {
            bitmap.recycle()
        }
    }

    private fun extractPdfText(pdfFile: File): String {
        if (pdfFile.length() > 25L * 1024L * 1024L) return ""
        return runCatching {
            PDDocument.load(pdfFile).use { doc ->
                val stripper = PDFTextStripper().apply {
                    startPage = 1
                    endPage = minOf(doc.numberOfPages, 8)
                }
                stripper.getText(doc).trim()
            }
        }.getOrDefault("")
    }

    private fun extractPlainText(file: File): String {
        if (file.length() > 512L * 1024L) return ""
        val ext = file.extension.lowercase()
        if (ext !in setOf("txt", "md", "csv", "json", "log", "xml", "yaml", "yml", "kt", "py")) {
            return ""
        }
        return runCatching { file.readText().take(6000) }.getOrDefault("")
    }

    private fun categorize(ext: String, mime: String): String = when {
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "bmp") -> "IMAGE"
        ext == "pdf" || mime == "application/pdf" -> "PDF"
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm") -> "VIDEO"
        mime.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "flac", "ogg") -> "AUDIO"
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> "ARCHIVE"
        ext in setOf("txt", "md", "csv", "json", "doc", "docx", "xls", "xlsx", "log", "xml") -> "DOCUMENT"
        else -> "OTHER"
    }

    private fun guessMimeByExtension(ext: String): String = when (ext) {
        "pdf" -> "application/pdf"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "txt", "log" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    companion object {
        const val KEY_INDEXED_COUNT = "indexed_count"
        const val KEY_TOTAL_COUNT = "total_count"
    }
}
