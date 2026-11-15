package com.example.ai.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

@Serializable
data class OllamaChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

/**
 * Lightweight OkHttp streaming client for Ollama HTTP API (`/api/tags` and `/api/chat`).
 * Works with local Termux/localhost (`http://127.0.0.1:11434`), Android Emulator (`http://10.0.2.2:11434`),
 * LAN desktop servers (`http://192.168.x.x:11434`), or remote HTTPS Ollama endpoints.
 */
class OllamaApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchAvailableModels(
        baseUrl: String,
        apiKey: String = ""
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = baseUrl.trim().trimEnd('/')
            require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
                "Ollama URL must start with http:// or https://"
            }
            val requestBuilder = Request.Builder()
                .url("$normalized/api/tags")
                .get()
            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Ollama HTTP ${response.code}: ${response.message}")
                }
                val rawBody = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(rawBody).jsonObject
                val models = root["models"]?.jsonArray.orEmpty()
                    .mapNotNull { item ->
                        item.jsonObject["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    }
                models
            }
        }
    }

    fun streamChat(
        baseUrl: String,
        model: String,
        apiKey: String = "",
        messages: List<OllamaChatMessage>,
        temperature: Float = 0.25f,
        maxTokens: Int = 1024
    ): Flow<String> = callbackFlow {
        val normalized = baseUrl.trim().trimEnd('/')
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            close(IllegalArgumentException("Invalid Ollama Base URL: $baseUrl"))
            return@callbackFlow
        }
        if (model.isBlank()) {
            close(IllegalArgumentException("Ollama model name is empty"))
            return@callbackFlow
        }

        val payload = buildJsonObject {
            put("model", model.trim())
            put("stream", true)
            put(
                "messages",
                JsonArray(
                    messages.map { msg ->
                        buildJsonObject {
                            put("role", msg.role)
                            put("content", msg.content)
                        }
                    }
                )
            )
            put(
                "options",
                buildJsonObject {
                    put("temperature", temperature)
                    put("num_predict", maxTokens)
                }
            )
        }.toString()

        val reqBuilder = Request.Builder()
            .url("$normalized/api/chat")
            .post(payload.toRequestBody(jsonMediaType))
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
        }

        var activeCall: Call? = null
        try {
            val call = client.newCall(reqBuilder.build())
            activeCall = call
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errText = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    error("Ollama HTTP ${response.code}: ${errText.ifBlank { response.message }}")
                }
                val bodyStream = response.body?.byteStream()
                    ?: error("Empty streaming response from Ollama")
                BufferedReader(InputStreamReader(bodyStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val obj: JsonObject = runCatching {
                            json.parseToJsonElement(line).jsonObject
                        }.getOrNull() ?: continue

                        val apiError = (obj["error"] as? JsonPrimitive)?.content
                        if (!apiError.isNullOrBlank()) {
                            error("Ollama error: $apiError")
                        }

                        val contentChunk = obj["message"]?.jsonObject
                            ?.get("content")?.jsonPrimitive?.content
                        if (!contentChunk.isNullOrEmpty()) {
                            trySend(contentChunk)
                        }

                        val done = (obj["done"] as? JsonPrimitive)?.booleanOrNull == true
                        if (done) break
                    }
                }
            }
            close()
        } catch (t: Throwable) {
            close(t)
        }

        awaitClose {
            runCatching { activeCall?.cancel() }
        }
    }.flowOn(Dispatchers.IO)
}
