package com.example

import com.example.ai.agent.AgentToolCall
import com.example.ai.engine.VectorMath
import com.example.data.db.FtsQuerySanitizer
import com.example.operations.OperationControlManager
import com.example.operations.OperationStatus
import com.example.operations.OperationType
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
}
