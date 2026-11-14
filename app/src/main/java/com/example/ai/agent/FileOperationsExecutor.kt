package com.example.ai.agent

import com.example.data.db.ActionLogDao
import com.example.data.db.ActionLogEntity
import com.example.data.db.FileIndexDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class FileExecutionReport(
    val success: Boolean,
    val batchId: String,
    val message: String,
    val affectedCount: Int
)

class FileOperationsExecutor(
    private val guardrails: SandboxGuardrails,
    private val fileIndexDao: FileIndexDao,
    private val actionLogDao: ActionLogDao
) {

    suspend fun executeFileAction(
        operation: String,
        rawSources: List<String>,
        rawDestination: String,
        batchId: String = UUID.randomUUID().toString()
    ): FileExecutionReport = withContext(Dispatchers.IO) {
        val normalizedOp = operation.uppercase().trim()
        val destValidation = guardrails.validatePath(rawDestination)
        if (destValidation is SandboxGuardrails.ValidationResult.Blocked) {
            return@withContext FileExecutionReport(false, batchId, destValidation.reason, 0)
        }
        val destFile = (destValidation as SandboxGuardrails.ValidationResult.Allowed).canonicalFile

        if (normalizedOp == "CREATE_FOLDER") {
            val created = if (destFile.exists()) destFile.isDirectory else destFile.mkdirs()
            if (!created) {
                return@withContext FileExecutionReport(
                    false,
                    batchId,
                    "Could not create folder at ${destFile.absolutePath}",
                    0
                )
            }
            actionLogDao.insert(
                ActionLogEntity(
                    batchId = batchId,
                    operation = "CREATE_FOLDER",
                    sourcePath = "",
                    destinationPath = destFile.absolutePath,
                    summary = "Created folder ${destFile.name}"
                )
            )
            return@withContext FileExecutionReport(
                true,
                batchId,
                "Created folder: ${destFile.absolutePath}",
                1
            )
        }

        if (rawSources.isEmpty()) {
            return@withContext FileExecutionReport(false, batchId, "No source_paths provided.", 0)
        }

        val logs = mutableListOf<ActionLogEntity>()
        val errors = mutableListOf<String>()
        var count = 0

        for (rawSrc in rawSources) {
            val srcValidation = guardrails.validatePath(rawSrc)
            if (srcValidation is SandboxGuardrails.ValidationResult.Blocked) {
                errors += srcValidation.reason
                continue
            }
            val srcFile = (srcValidation as SandboxGuardrails.ValidationResult.Allowed).canonicalFile
            if (!srcFile.exists()) {
                errors += "Source not found: ${srcFile.absolutePath}"
                continue
            }

            when (normalizedOp) {
                "MOVE" -> {
                    val targetDir = if (destFile.isDirectory || !destFile.name.contains('.')) {
                        destFile.apply { if (!exists()) mkdirs() }
                    } else {
                        destFile.parentFile?.apply { if (!exists()) mkdirs() }
                    } ?: continue
                    val targetFile = uniqueTargetFile(File(targetDir, srcFile.name))
                    if (moveFileSafe(srcFile, targetFile)) {
                        fileIndexDao.updateFilePathAndTrashState(
                            oldPath = srcFile.absolutePath,
                            newPath = targetFile.absolutePath,
                            newName = targetFile.name,
                            newParent = targetFile.parent ?: "",
                            isTrashed = false
                        )
                        logs += ActionLogEntity(
                            batchId = batchId,
                            operation = "MOVE",
                            sourcePath = srcFile.absolutePath,
                            destinationPath = targetFile.absolutePath,
                            summary = "Moved ${srcFile.name} -> ${targetDir.name}/"
                        )
                        count++
                    } else {
                        errors += "Failed to move ${srcFile.name}"
                    }
                }

                "COPY" -> {
                    val targetDir = if (destFile.isDirectory || !destFile.name.contains('.')) {
                        destFile.apply { if (!exists()) mkdirs() }
                    } else {
                        destFile.parentFile?.apply { if (!exists()) mkdirs() }
                    } ?: continue
                    val targetFile = uniqueTargetFile(File(targetDir, srcFile.name))
                    val copied = runCatching {
                        srcFile.copyTo(targetFile, overwrite = false)
                        true
                    }.getOrDefault(false)
                    if (copied) {
                        val existing = fileIndexDao.getByPath(srcFile.absolutePath)
                        if (existing != null) {
                            fileIndexDao.upsertFile(
                                existing.copy(
                                    id = 0L,
                                    filePath = targetFile.absolutePath,
                                    fileName = targetFile.name,
                                    parentDir = targetFile.parent ?: "",
                                    lastModifiedMs = targetFile.lastModified()
                                )
                            )
                        }
                        logs += ActionLogEntity(
                            batchId = batchId,
                            operation = "COPY",
                            sourcePath = srcFile.absolutePath,
                            destinationPath = targetFile.absolutePath,
                            summary = "Copied ${srcFile.name} -> ${targetDir.name}/"
                        )
                        count++
                    } else {
                        errors += "Failed to copy ${srcFile.name}"
                    }
                }

                "RENAME" -> {
                    val targetFile = if (destFile.isDirectory) {
                        File(destFile, srcFile.name)
                    } else {
                        destFile.parentFile?.mkdirs()
                        destFile
                    }
                    val uniqueDest = uniqueTargetFile(targetFile)
                    if (moveFileSafe(srcFile, uniqueDest)) {
                        fileIndexDao.updateFilePathAndTrashState(
                            oldPath = srcFile.absolutePath,
                            newPath = uniqueDest.absolutePath,
                            newName = uniqueDest.name,
                            newParent = uniqueDest.parent ?: "",
                            isTrashed = false
                        )
                        logs += ActionLogEntity(
                            batchId = batchId,
                            operation = "RENAME",
                            sourcePath = srcFile.absolutePath,
                            destinationPath = uniqueDest.absolutePath,
                            summary = "Renamed ${srcFile.name} -> ${uniqueDest.name}"
                        )
                        count++
                    } else {
                        errors += "Failed to rename ${srcFile.name}"
                    }
                }

                else -> errors += "Unsupported file_action operation: $normalizedOp"
            }
        }

        if (logs.isNotEmpty()) actionLogDao.insertAll(logs)
        val summary = buildString {
            append("$normalizedOp completed for $count file(s).")
            if (errors.isNotEmpty()) append(" Errors: ${errors.joinToString("; ")}")
        }
        FileExecutionReport(count > 0, batchId, summary, count)
    }

    suspend fun executeSafeTrash(
        rawPaths: List<String>,
        batchId: String = UUID.randomUUID().toString()
    ): FileExecutionReport = withContext(Dispatchers.IO) {
        if (rawPaths.isEmpty()) {
            return@withContext FileExecutionReport(false, batchId, "No file_paths provided.", 0)
        }
        val trashDir = guardrails.safeTrashRoot.apply { if (!exists()) mkdirs() }
        val logs = mutableListOf<ActionLogEntity>()
        val errors = mutableListOf<String>()
        var count = 0

        for (rawPath in rawPaths) {
            val validation = guardrails.validatePath(rawPath)
            if (validation is SandboxGuardrails.ValidationResult.Blocked) {
                errors += validation.reason
                continue
            }
            val srcFile = (validation as SandboxGuardrails.ValidationResult.Allowed).canonicalFile
            if (!srcFile.exists()) {
                errors += "File missing: ${srcFile.absolutePath}"
                continue
            }
            val trashedFile = File(trashDir, "${System.currentTimeMillis()}_${srcFile.name}")
            if (moveFileSafe(srcFile, trashedFile)) {
                fileIndexDao.updateFilePathAndTrashState(
                    oldPath = srcFile.absolutePath,
                    newPath = trashedFile.absolutePath,
                    newName = srcFile.name,
                    newParent = trashDir.absolutePath,
                    isTrashed = true
                )
                logs += ActionLogEntity(
                    batchId = batchId,
                    operation = "SAFE_TRASH",
                    sourcePath = srcFile.absolutePath,
                    destinationPath = trashedFile.absolutePath,
                    summary = "Moved ${srcFile.name} to reversible .agent_trash"
                )
                count++
            } else {
                errors += "Failed to trash ${srcFile.name}"
            }
        }

        if (logs.isNotEmpty()) actionLogDao.insertAll(logs)
        val summary = buildString {
            append("Safely trashed $count file(s) to .agent_trash.")
            if (errors.isNotEmpty()) append(" Errors: ${errors.joinToString("; ")}")
        }
        FileExecutionReport(count > 0, batchId, summary, count)
    }

    suspend fun undoBatch(batchId: String): FileExecutionReport = withContext(Dispatchers.IO) {
        val activeLogs = actionLogDao.getActiveLogsForBatch(batchId)
        if (activeLogs.isEmpty()) {
            return@withContext FileExecutionReport(false, batchId, "Nothing left to undo for this batch.", 0)
        }
        var restored = 0
        for (log in activeLogs) {
            when (log.operation) {
                "MOVE", "RENAME", "SAFE_TRASH" -> {
                    val current = File(log.destinationPath)
                    val original = File(log.sourcePath)
                    original.parentFile?.mkdirs()
                    if (current.exists() && moveFileSafe(current, original)) {
                        fileIndexDao.updateFilePathAndTrashState(
                            oldPath = log.destinationPath,
                            newPath = original.absolutePath,
                            newName = original.name,
                            newParent = original.parent ?: "",
                            isTrashed = false
                        )
                        restored++
                    }
                }
                "COPY" -> {
                    val copied = File(log.destinationPath)
                    if (copied.exists() && copied.delete()) {
                        fileIndexDao.deleteByPath(log.destinationPath)
                        restored++
                    }
                }
                "CREATE_FOLDER" -> {
                    val folder = File(log.destinationPath)
                    if (folder.exists() && folder.isDirectory && (folder.listFiles()?.isEmpty() == true)) {
                        if (folder.delete()) restored++
                    }
                }
            }
        }
        actionLogDao.markBatchUndone(batchId)
        FileExecutionReport(restored > 0, batchId, "Undid $restored operation(s) in batch.", restored)
    }

    private fun moveFileSafe(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        return runCatching {
            src.copyTo(dest, overwrite = false)
            src.deleteRecursively()
        }.getOrDefault(false)
    }

    private fun uniqueTargetFile(candidate: File): File {
        if (!candidate.exists()) return candidate
        val name = candidate.nameWithoutExtension
        val ext = candidate.extension
        val parent = candidate.parentFile
        var idx = 1
        while (idx < 1000) {
            val suffixName = if (ext.isNotEmpty()) "${name}_$idx.$ext" else "${name}_$idx"
            val next = File(parent, suffixName)
            if (!next.exists()) return next
            idx++
        }
        return File(parent, "${System.currentTimeMillis()}_${candidate.name}")
    }
}
