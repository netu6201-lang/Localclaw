package com.example.ai.agent

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Hardcoded Sandbox Protection preventing the autonomous agent from reading/modifying
 * OS system paths, app-private data (Android/data), OBBs, or its own model binaries.
 */
class SandboxGuardrails(private val context: Context) {

    private val protectedPrefixes: List<String> by lazy {
        val extRoot = Environment.getExternalStorageDirectory().canonicalPath
        listOf(
            "/system",
            "/vendor",
            "/product",
            "/apex",
            "/proc",
            "/sys",
            "/dev",
            "/data",
            "/root",
            context.filesDir.canonicalPath,
            context.cacheDir.canonicalPath,
            "$extRoot/Android/data",
            "$extRoot/Android/obb",
            "$extRoot/Android/media"
        ).map { it.trimEnd('/') }
    }

    val safeTrashRoot: File
        get() = File(
            Environment.getExternalStorageDirectory(),
            "EdgeClaw/.agent_trash"
        )

    sealed class ValidationResult {
        data class Allowed(val canonicalFile: File) : ValidationResult()
        data class Blocked(val reason: String) : ValidationResult()
    }

    fun validatePath(rawPath: String, allowTrashRoot: Boolean = false): ValidationResult {
        if (rawPath.isBlank()) {
            return ValidationResult.Blocked("Empty file path is not allowed.")
        }
        return try {
            val expanded = if (rawPath.startsWith("/storage") || rawPath.startsWith("/sdcard")) {
                rawPath
            } else if (rawPath.startsWith("/")) {
                // Allow relative-to-external-storage shorthand like "/Documents/Bills"
                val extRoot = Environment.getExternalStorageDirectory().canonicalPath
                val asDirect = File(rawPath)
                if (asDirect.exists()) rawPath else "$extRoot$rawPath"
            } else {
                "${Environment.getExternalStorageDirectory().canonicalPath}/$rawPath"
            }

            val canonical = File(expanded).canonicalFile
            val canonicalPath = canonical.path

            val extRoot = Environment.getExternalStorageDirectory().canonicalPath
            if (!canonicalPath.startsWith(extRoot)) {
                return ValidationResult.Blocked(
                    "Sandbox violation: Path '$canonicalPath' is outside user storage ($extRoot)."
                )
            }

            for (prefix in protectedPrefixes) {
                if (canonicalPath == prefix || canonicalPath.startsWith("$prefix/")) {
                    return ValidationResult.Blocked(
                        "Sandbox violation: Access to protected directory '$prefix' is strictly blocked."
                    )
                }
            }

            if (!allowTrashRoot) {
                val trashCanonical = safeTrashRoot.canonicalPath
                if (canonicalPath == trashCanonical || canonicalPath.startsWith("$trashCanonical/")) {
                    return ValidationResult.Blocked(
                        "Protected trash sandbox: Use Restore from History to recover files from .agent_trash."
                    )
                }
            }

            if (canonicalPath.endsWith(".litertlm", ignoreCase = true) ||
                canonicalPath.endsWith(".task", ignoreCase = true)
            ) {
                return ValidationResult.Blocked(
                    "Model protection: Edge AI model weights cannot be moved or trashed by the agent."
                )
            }

            ValidationResult.Allowed(canonical)
        } catch (e: Exception) {
            ValidationResult.Blocked("Invalid path '$rawPath': ${e.message}")
        }
    }
}
