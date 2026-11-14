package com.nanote.app.core.ai

import com.nanote.app.core.data.SettingsStore
import com.nanote.app.util.AppLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// ---------------------------------------------------------------------------
// OpenAI-compatible chat-completions DTOs (kotlinx.serialization)
// ---------------------------------------------------------------------------

@Serializable
data class Message(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double = 0.3
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: Message = Message(role = "assistant", content = "")
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList()
)

/**
 * Thin client for an OpenAI-compatible chat provider configured in Settings.
 * The OkHttpClient instance is cached; a new call is built per request.
 */
class AiService @Inject constructor(
    private val settings: SettingsStore
) {

    companion object {
        private const val TAG = "AiService"
        private const val TIMEOUT_SECONDS = 60L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS + 30L, TimeUnit.SECONDS)
        .build()

    /**
     * Runs a chat completion against the configured provider.
     * Any failure (missing config, network, HTTP error, empty answer)
     * is surfaced as [Result.failure] with a human-readable message.
     */
    suspend fun ask(systemPrompt: String, userPrompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = settings.aiConfig.value
                if (config.apiKey.isBlank() || config.baseUrl.isBlank()) {
                    error("AI provider is not configured. Add a base URL and API key in Settings > AI.")
                }

                // baseUrl may or may not end with "/v1" — keep normalization simple.
                val url = config.baseUrl.trimEnd('/') + "/chat/completions"

                val payload = json.encodeToString(
                    ChatRequest(
                        model = config.model,
                        messages = listOf(
                            Message(role = "system", content = systemPrompt),
                            Message(role = "user", content = userPrompt)
                        )
                    )
                )

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("AI provider returned HTTP ${response.code}: ${bodyText.take(200)}")
                    }
                    val parsed = json.decodeFromString<ChatResponse>(bodyText)
                    val content = parsed.choices.firstOrNull()?.message?.content?.trim()
                    if (content.isNullOrEmpty()) {
                        error("AI provider returned an empty response.")
                    }
                    content
                }
            }
                .onFailure { t ->
                    AppLog.e(TAG, "chat completion failed: ${t.message}", t)
                }
                .recoverCatching { t ->
                    val message = when (t) {
                        is IOException ->
                            "Network error while contacting the AI provider: ${t.message ?: "no details"}"
                        else -> t.message ?: "AI request failed"
                    }
                    throw IllegalStateException(message, t)
                }
        }

    /** Fires a minimal request to verify the provider configuration. */
    suspend fun testConnection(): Boolean {
        val result = ask("Reply with OK only.", "ping")
        AppLog.d(TAG, "testConnection success=${result.isSuccess}")
        return result.isSuccess
    }

    /** Translates [text] to [targetLang]; the reply contains only the translation. */
    suspend fun translate(text: String, targetLang: String): Result<String> =
        ask(
            systemPrompt = "You are a translator. Translate the user text to $targetLang. Reply with translation only.",
            userPrompt = text
        )
}
