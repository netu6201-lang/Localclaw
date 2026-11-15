package com.example.workers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.ai.engine.EmbeddingRuntimeStatus
import com.example.ai.engine.LocalEmbeddingEngineManager
import com.example.ai.engine.VectorMath
import com.example.data.db.FileIndexDao
import com.example.data.db.FileIndexEntity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * Unified local file & Photo Picker URI extractor:
 * runs ML Kit OCR on images, PDFBox text stripping on PDFs, UTF-8 text snippets on docs,
 * and optional EmbeddingGemma vector encoding.
 */
class MediaFileExtractor(private val context: Context) {

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    suspend fun extractFileEntity(
        file: File,
        existing: FileIndexEntity?,
        embeddingManager: LocalEmbeddingEngineManager?
    ): FileIndexEntity {
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: guessMimeByExtension(ext)
        val category = categorize(ext, mime)

        val extractedText = when (category) {
            "IMAGE" -> runMlKitOcrFromFile(file)
            "PDF" -> extractPdfTextFromFile(file)
            "DOCUMENT" -> extractPlainTextFromFile(file)
            else -> ""
        }

        val embedTelemetry = embeddingManager?.telemetry?.value
        val canEmbedNow = embedTelemetry?.status == EmbeddingRuntimeStatus.READY &&
            embedTelemetry.loadedModelId.isNotBlank()

        var vectorBytes: ByteArray? = null
        var vectorModelId = ""
        if (canEmbedNow) {
            val imageBytes = if (category == "IMAGE") loadDownsampledImageBytesFromFile(file) else null
            val vec = embeddingManager?.embedDocument(
                title = file.name,
                content = extractedText,
                imageBytes = imageBytes
            )
            if (vec != null && vec.isNotEmpty()) {
                vectorBytes = VectorMath.floatArrayToBytes(vec)
                vectorModelId = embedTelemetry?.loadedModelId.orEmpty()
            }
        }

        return FileIndexEntity(
            id = existing?.id ?: 0L,
            filePath = file.absolutePath,
            fileName = file.name,
            parentDir = file.parent ?: "",
            mimeType = mime,
            fileCategory = category,
            sizeBytes = file.length(),
            lastModifiedMs = file.lastModified(),
            extractedText = extractedText.take(8000),
            embeddingVector = vectorBytes,
            embeddingModelId = vectorModelId,
            isTrashed = false,
            indexedAtMs = System.currentTimeMillis()
        )
    }

    suspend fun indexSelectedUri(
        uri: Uri,
        fileDao: FileIndexDao,
        embeddingManager: LocalEmbeddingEngineManager?
    ): FileIndexEntity? {
        val resolvedDataPath = resolveMediaStorePath(uri)
        if (!resolvedDataPath.isNullOrBlank()) {
            val realFile = File(resolvedDataPath)
            if (realFile.exists() && realFile.isFile) {
                val existing = fileDao.getByPath(realFile.absolutePath)
                val entity = extractFileEntity(realFile, existing, embeddingManager)
                fileDao.upsertFile(entity)
                return entity
            }
        }

        // Photo Picker / SAF content:// URI path (zero-permission flow)
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val uriString = uri.toString()
        val (displayName, sizeBytes) = queryOpenableMeta(uri)
        val mime = context.contentResolver.getType(uri)
            ?: guessMimeByExtension(displayName.substringAfterLast('.', "").lowercase())
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val category = categorize(ext, mime)

        val extractedText = when (category) {
            "IMAGE" -> runMlKitOcrFromUri(uri)
            "PDF" -> extractPdfTextFromUri(uri)
            "DOCUMENT" -> extractPlainTextFromUri(uri)
            else -> ""
        }

        val embedTelemetry = embeddingManager?.telemetry?.value
        val canEmbedNow = embedTelemetry?.status == EmbeddingRuntimeStatus.READY &&
            embedTelemetry.loadedModelId.isNotBlank()

        var vectorBytes: ByteArray? = null
        var vectorModelId = ""
        if (canEmbedNow) {
            val imageBytes = if (category == "IMAGE") loadDownsampledImageBytesFromUri(uri) else null
            val vec = embeddingManager?.embedDocument(
                title = displayName,
                content = extractedText,
                imageBytes = imageBytes
            )
            if (vec != null && vec.isNotEmpty()) {
                vectorBytes = VectorMath.floatArrayToBytes(vec)
                vectorModelId = embedTelemetry?.loadedModelId.orEmpty()
            }
        }

        val existing = fileDao.getByPath(uriString)
        val entity = FileIndexEntity(
            id = existing?.id ?: 0L,
            filePath = uriString,
            fileName = displayName,
            parentDir = "Selected Media",
            mimeType = mime,
            fileCategory = category,
            sizeBytes = sizeBytes,
            lastModifiedMs = System.currentTimeMillis(),
            extractedText = extractedText.take(8000),
            embeddingVector = vectorBytes,
            embeddingModelId = vectorModelId,
            isTrashed = false,
            indexedAtMs = System.currentTimeMillis()
        )
        fileDao.upsertFile(entity)
        return entity
    }

    fun loadDownsampledImageBytesFromFile(imageFile: File): ByteArray? {
        if (!imageFile.exists() || imageFile.length() > 15L * 1024L * 1024L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            val sample = (maxDim / 384).coerceAtLeast(1)
            val bitmap = BitmapFactory.decodeFile(
                imageFile.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            compressBitmapToJpegBytes(bitmap)
        }.getOrNull()
    }

    private fun loadDownsampledImageBytesFromUri(uri: Uri): ByteArray? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            val sample = (maxDim / 384).coerceAtLeast(1)
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            } ?: return null
            compressBitmapToJpegBytes(bitmap)
        }.getOrNull()
    }

    private fun compressBitmapToJpegBytes(bitmap: Bitmap): ByteArray? {
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                val bytes = out.toByteArray()
                if (bytes.size <= 512 * 1024) bytes else null
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun runMlKitOcrFromFile(imageFile: File): String {
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
            recognizeText(inputImage)
        } catch (_: Throwable) {
            ""
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun runMlKitOcrFromUri(uri: Uri): String {
        return try {
            val inputImage = InputImage.fromFilePath(context, uri)
            recognizeText(inputImage)
        } catch (_: Throwable) {
            ""
        }
    }

    private suspend fun recognizeText(inputImage: InputImage): String {
        return suspendCancellableCoroutine { cont ->
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(visionText.text.trim())
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("")
                }
        }
    }

    private fun extractPdfTextFromFile(pdfFile: File): String {
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

    private fun extractPdfTextFromUri(uri: Uri): String {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { doc ->
                    val stripper = PDFTextStripper().apply {
                        startPage = 1
                        endPage = minOf(doc.numberOfPages, 8)
                    }
                    stripper.getText(doc).trim()
                }
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun extractPlainTextFromFile(file: File): String {
        if (file.length() > 512L * 1024L) return ""
        val ext = file.extension.lowercase()
        if (ext !in setOf("txt", "md", "csv", "json", "log", "xml", "yaml", "yml", "kt", "py")) {
            return ""
        }
        return runCatching { file.readText().take(6000) }.getOrDefault("")
    }

    private fun extractPlainTextFromUri(uri: Uri): String {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.readText().take(6000)
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun resolveMediaStorePath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    private fun queryOpenableMeta(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "media_${System.currentTimeMillis()}"
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        name = cursor.getString(nameIdx)
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        return name to size
    }

    fun categorize(ext: String, mime: String): String = when {
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "bmp") -> "IMAGE"
        ext == "pdf" || mime == "application/pdf" -> "PDF"
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm") -> "VIDEO"
        mime.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "flac", "ogg") -> "AUDIO"
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> "ARCHIVE"
        ext in setOf("txt", "md", "csv", "json", "doc", "docx", "xls", "xlsx", "log", "xml") -> "DOCUMENT"
        else -> "OTHER"
    }

    fun guessMimeByExtension(ext: String): String = when (ext) {
        "pdf" -> "application/pdf"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "txt", "log" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}
