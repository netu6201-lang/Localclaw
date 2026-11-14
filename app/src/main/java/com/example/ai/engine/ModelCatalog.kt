package com.example.ai.engine

import android.content.Context
import com.example.data.db.ModelRecordEntity
import java.io.File

object ModelCatalog {
    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    val defaultModels: List<ModelRecordEntity> = listOf(
        ModelRecordEntity(
            modelId = "gemma-4-e2b-it-litertlm",
            displayName = "Gemma 4 E2B IT (LiteRT-LM)",
            description = "Flagship 2B-class quantized Gemma model for NPU/GPU/CPU tool calling and local file reasoning.",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            expectedSizeBytes = 2_600_000_000L,
            format = "LITERTLM",
            recommendedBackend = "GPU"
        ),
        ModelRecordEntity(
            modelId = "gemma-3n-e2b-it-litertlm",
            displayName = "Gemma 3n E2B IT (LiteRT-LM)",
            description = "Efficient mobile-first Gemma 3n 2B variant optimized for low-RAM devices and fast JSON output.",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            expectedSizeBytes = 1_850_000_000L,
            format = "LITERTLM",
            recommendedBackend = "GPU"
        ),
        ModelRecordEntity(
            modelId = "qwen3-0.6b-litertlm",
            displayName = "Qwen3 0.6B (Ultra-Fast LiteRT-LM)",
            description = "Compact 600MB quantized model ideal for quick testing, older phones, and sub-second CPU/GPU routing.",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            fileName = "Qwen3-0.6B.litertlm",
            expectedSizeBytes = 610_000_000L,
            format = "LITERTLM",
            recommendedBackend = "CPU"
        ),
        ModelRecordEntity(
            modelId = "gemma-2-2b-it-task",
            displayName = "Gemma 2 2B IT (MediaPipe .task)",
            description = "Classic MediaPipe GenAI .task bundle for GPU/CPU quantized Gemma 2B inference.",
            downloadUrl = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-gpu-int8.task",
            fileName = "gemma2-2b-it-gpu-int8.task",
            expectedSizeBytes = 1_900_000_000L,
            format = "TASK",
            recommendedBackend = "GPU"
        )
    )
}
