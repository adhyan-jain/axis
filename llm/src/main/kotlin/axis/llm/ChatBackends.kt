package axis.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val JSON_MEDIA = "application/json".toMediaType()

/** Shared status handling for chat-style APIs: 429 rate limit, 401/403 bad key, 5xx and 529 transient. */
private fun classifyHttp(code: Int, retryAfter: String?, onSuccess: () -> CallResult): CallResult = when {
    code in 200..299 -> onSuccess()
    code == 429 -> CallResult.RateLimited(retryAfter?.toLongOrNull()?.times(1000))
    code == 401 || code == 403 -> CallResult.AuthFailed
    else -> CallResult.Transient
}

private suspend fun post(client: OkHttpClient, request: Request, parse: (code: Int, retryAfter: String?, body: String) -> CallResult): CallResult =
    withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { r -> parse(r.code, r.header("Retry-After"), r.body?.string().orEmpty()) }
        } catch (_: IOException) {
            CallResult.Transient
        }
    }

/** OpenAI and Groq share the chat-completions format. Bearer auth; the key never goes in the URL. */
class OpenAiCompatibleBackend(
    private val baseUrl: String,
    private val models: List<String>,
    private val client: OkHttpClient = GeminiBackend.defaultClient(),
) : LlmBackend {

    override suspend fun generate(key: KeyEntry, request: LlmRequest): CallResult = runLadder(models) { model ->
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", request.prompt) }) })
        }.toString().toRequestBody(JSON_MEDIA)
        val http = Request.Builder().url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${key.key.reveal()}").post(body).build()
        post(client, http) { code, retry, text -> classifyHttp(code, retry) { parse(text) } }
    }

    private fun parse(body: String): CallResult {
        val choice = runCatching { Json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject }
            .getOrNull() ?: return CallResult.Transient
        val text = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "content_filter") return CallResult.Blocked("content_filter")
        return if (text.isEmpty()) CallResult.Transient else CallResult.Ok(text)
    }

    companion object {
        // Model names change often; verify against the provider's current list and override per app settings.
        fun openAi(models: List<String> = listOf("gpt-4o-mini")) = OpenAiCompatibleBackend("https://api.openai.com/v1", models)
        fun groq(models: List<String> = listOf("llama-3.3-70b-versatile")) = OpenAiCompatibleBackend("https://api.groq.com/openai/v1", models)
    }
}

class ClaudeBackend(
    private val baseUrl: String = "https://api.anthropic.com",
    private val models: List<String> = listOf("claude-haiku-5-5", "claude-sonnet-5-5"),
    private val maxTokens: Int = 1024,
    private val client: OkHttpClient = GeminiBackend.defaultClient(),
) : LlmBackend {

    override suspend fun generate(key: KeyEntry, request: LlmRequest): CallResult = runLadder(models) { model ->
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", request.prompt) }) })
        }.toString().toRequestBody(JSON_MEDIA)
        val http = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/messages")
            .header("x-api-key", key.key.reveal()).header("anthropic-version", "2023-06-01").post(body).build()
        post(client, http) { code, retry, text -> classifyHttp(code, retry) { parse(text) } }
    }

    private fun parse(body: String): CallResult {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return CallResult.Transient
        if (root["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") return CallResult.Blocked("refusal")
        val text = root["content"]?.jsonArray?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("").orEmpty()
        return if (text.isEmpty()) CallResult.Transient else CallResult.Ok(text)
    }
}

/** Sends each key to the adapter for its provider, so one pool can cascade across Gemini, Groq, OpenAI and Claude. */
class MultiProviderBackend(private val backends: Map<Provider, LlmBackend>) : LlmBackend {
    override suspend fun generate(key: KeyEntry, request: LlmRequest): CallResult =
        backends[key.provider]?.generate(key, request) ?: CallResult.Transient

    companion object {
        fun standard() = MultiProviderBackend(mapOf(
            Provider.GEMINI to GeminiBackend(),
            Provider.GROQ to OpenAiCompatibleBackend.groq(),
            Provider.OPENAI to OpenAiCompatibleBackend.openAi(),
            Provider.CLAUDE to ClaudeBackend(),
        ))
    }
}
