package com.example

import com.example.ai.agent.AgentToolCall
import com.example.data.db.FtsQuerySanitizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
