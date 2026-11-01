package axis.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Gemini REST adapter. The key travels only in the x-goog-api-key header, never in the URL. */
class GeminiBackend(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    /** Tried in order. Quotas are per model, and a model can be overloaded or retired, so the next one covers for it. */
    private val models: List<String> = DEFAULT_MODELS,
) : LlmBackend {

    override suspend fun generate(key: KeyEntry, request: LlmRequest): CallResult =
        runLadder(models) { callModel(key, request, it) }

    private suspend fun callModel(key: KeyEntry, request: LlmRequest, model: String): CallResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", request.prompt) }) })
                })
            })
        }.toString().toRequestBody(JSON)

        val http = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key.key.reveal())
            .post(body)
            .build()

        try {
            client.newCall(http).execute().use { response ->
                val text = response.body?.string().orEmpty()
                classify(response.code, response.header("Retry-After"), text)
            }
        } catch (_: IOException) {
            CallResult.Transient
        }
    }

    internal fun classify(code: Int, retryAfterHeader: String?, body: String): CallResult = when {
        code in 200..299 -> parseSuccess(body)
        code == 429 -> CallResult.RateLimited(retryAfterHeader?.toLongOrNull()?.times(1000) ?: parseRetryDelayMs(body))
        code == 401 || code == 403 -> CallResult.AuthFailed
        code == 400 && body.contains("API_KEY_INVALID") -> CallResult.AuthFailed
        code in 500..599 || code == 408 -> CallResult.Transient
        else -> CallResult.Transient
    }

    private fun parseSuccess(body: String): CallResult {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return CallResult.Transient
        root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
            ?.let { return CallResult.Blocked(it) }
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return CallResult.Transient
        val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("").orEmpty()
        if (text.isEmpty() && finish in BLOCKING_FINISH) return CallResult.Blocked(finish!!)
        return if (text.isEmpty()) CallResult.Transient else CallResult.Ok(text)
    }

    /** Google reports the wait as a RetryInfo detail like "13s" or "0.5s" inside the error body. */
    private fun parseRetryDelayMs(body: String): Long? {
        val details = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("details") as? JsonArray
        }.getOrNull() ?: return null
        val delay = details.firstNotNullOfOrNull { (it as? JsonObject)?.get("retryDelay") as? JsonPrimitive }?.contentOrNull
            ?: return null
        val seconds = delay.removeSuffix("s").toDoubleOrNull() ?: return null
        return (seconds * 1000).toLong()
    }

    companion object {
        val DEFAULT_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")
        private val JSON = "application/json".toMediaType()
        private val BLOCKING_FINISH = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
