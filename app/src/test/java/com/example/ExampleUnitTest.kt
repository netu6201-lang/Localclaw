package com.example

import com.example.ai.agent.AgentToolCall
import com.example.ai.agent.UpdateAppSettingsArgs
import com.example.ai.engine.VectorMath
import com.example.data.db.FtsQuerySanitizer
import com.example.operations.OperationControlManager
import com.example.operations.OperationStatus
import com.example.operations.OperationType
import com.example.search.HindiTransliterator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun ftsSanitizer_stripsSpecialCharsAndAddsPrefixWildcards() {
        val result = FtsQuerySanitizer.toSafeFtsQuery("electricity-bill (2026) / PDF!")
        assertEquals("electricity* bill* 2026* pdf*", result)
    }

    @Test
    fun toolCallJson_parsesGemmaToolOutputCleanly() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val payload = """
            {"thought":"Find bills","tool":"search_files","args":{"query":"electricity","file_type":"PDF"}}
        """.trimIndent()
        val parsed = json.decodeFromString<AgentToolCall>(payload)
        assertNotNull(parsed)
        assertEquals("search_files", parsed.tool)
        assertEquals("Find bills", parsed.thought)
    }

    @Test
    fun vectorMath_roundTripBytesAndCosineSimilarity() {
        val original = floatArrayOf(0.5f, -0.25f, 0.75f, 0.0f)
        val encoded = VectorMath.floatArrayToBytes(original)
        val decoded = VectorMath.bytesToFloatArray(encoded)
        assertArrayEquals(original, decoded, 1e-6f)

        val identicalSim = VectorMath.cosineSimilarity(original, decoded)
        assertEquals(1.0f, identicalSim, 1e-5f)

        val orthogonal = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
        val orthoSim = VectorMath.cosineSimilarity(original, orthogonal)
        assertTrue(orthoSim == 0.0f)
    }

    @Test
    fun operationControlManager_pauseResumeAndStopWorkCooperatively() = runBlocking {
        val manager = OperationControlManager()
        manager.start(OperationType.STORAGE_SCAN, total = 10, detail = "Scanning")
        assertEquals(OperationStatus.RUNNING, manager.getState(OperationType.STORAGE_SCAN).status)

        manager.pause(OperationType.STORAGE_SCAN)
        assertEquals(OperationStatus.PAUSED, manager.getState(OperationType.STORAGE_SCAN).status)

        var resumed = false
        val waiter = async {
            manager.checkpoint(OperationType.STORAGE_SCAN)
            resumed = true
        }
        yield()
        assertTrue(!resumed)

        manager.resume(OperationType.STORAGE_SCAN)
        waiter.await()
        assertTrue(resumed)
        assertEquals(OperationStatus.RUNNING, manager.getState(OperationType.STORAGE_SCAN).status)

        manager.stop(OperationType.STORAGE_SCAN)
        assertEquals(OperationStatus.STOPPED, manager.getState(OperationType.STORAGE_SCAN).status)
        var stoppedThrew = false
        try {
            manager.checkpoint(OperationType.STORAGE_SCAN)
        } catch (_: CancellationException) {
            stoppedThrew = true
        }
        assertTrue(stoppedThrew)
    }

    @Test
    fun hindiTransliterator_matchesBhuriVariantsAndHindiConceptsAcrossScripts() {
        assertEquals("bhuri", HindiTransliterator.transliterateDevanagariToRoman("भूरी"))
        assertEquals("bhuri", HindiTransliterator.transliterateDevanagariToRoman("भुरी"))

        val expandedLong = HindiTransliterator.expandQueryTokens("भूरी")
        val expandedShort = HindiTransliterator.expandQueryTokens("भुरी")
        assertTrue(expandedLong.contains("bhuri"))
        assertTrue(expandedShort.contains("bhuri"))

        // User writes Hindi "भूरी" or "भुरी", file is named in English "Bhuri_Certificate.pdf"
        assertTrue(
            HindiTransliterator.scoreCrossScriptMatch(
                rawQuery = "भूरी",
                fileName = "Bhuri_Certificate.pdf",
                extractedText = "",
                filePath = "/storage/emulated/0/Download/Bhuri_Certificate.pdf"
            ) > 0f
        )
        assertTrue(
            HindiTransliterator.scoreCrossScriptMatch(
                rawQuery = "भुरी",
                fileName = "Bhoori_Photo.jpg",
                extractedText = "",
                filePath = "/storage/emulated/0/Pictures/Bhoori_Photo.jpg"
            ) > 0f
        )

        // User writes English/Hinglish "bhuri", file has Devanagari name/OCR "भूरी_रसीद.jpg"
        assertTrue(
            HindiTransliterator.scoreCrossScriptMatch(
                rawQuery = "bhuri",
                fileName = "भूरी_रसीद.jpg",
                extractedText = "",
                filePath = "/storage/emulated/0/Pictures/भूरी_रसीद.jpg"
            ) > 0f
        )

        // User writes Hindi "बिजली बिल", matches English OCR "Electricity Bill March"
        assertTrue(
            HindiTransliterator.scoreCrossScriptMatch(
                rawQuery = "बिजली बिल",
                fileName = "scan_001.jpg",
                extractedText = "Electricity Bill March 2026",
                filePath = "/storage/emulated/0/Pictures/scan_001.jpg"
            ) > 0f
        )

        val ftsOr = FtsQuerySanitizer.toCrossScriptFtsQuery("भूरी")
        assertTrue(ftsOr.contains("bhuri*"))
        assertTrue(ftsOr.contains("OR"))
    }

    @Test
    fun updateAppSettingsArgs_parsesOllamaAndScanMode() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val payload = """
            {"scan_mode":"AUTO_SCAN","llm_provider_mode":"OLLAMA","ollama_base_url":"http://127.0.0.1:11434","ollama_model_name":"gemma3:4b"}
        """.trimIndent()
        val parsed = json.decodeFromString<UpdateAppSettingsArgs>(payload)
        assertEquals("AUTO_SCAN", parsed.scanMode)
        assertEquals("OLLAMA", parsed.llmProviderMode)
        assertEquals("http://127.0.0.1:11434", parsed.ollamaBaseUrl)
        assertEquals("gemma3:4b", parsed.ollamaModelName)
    }
}
