package axis.agent

import axis.core.Sensitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI

sealed interface DeploymentResult {
    data class Found(val url: String) : DeploymentResult
    data object NoDeployment : DeploymentResult
    data object NotConfigured : DeploymentResult
    data class Failed(val reason: String) : DeploymentResult
}

fun interface DeploymentSource {
    suspend fun latestDeploymentUrl(repo: String): DeploymentResult
}

/** Events from the user's own calendar for the briefing. */
fun interface CalendarSource {
    fun todayEvents(): List<String>
}

/** Reads the newest deployment's public URL from GitHub using a token the user pasted. Read-only. */
class GitHubClient(
    private val token: Sensitive<String>,
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.github.com",
) : DeploymentSource {

    override suspend fun latestDeploymentUrl(repo: String): DeploymentResult = withContext(Dispatchers.IO) {
        if (!REPO.matches(repo)) return@withContext DeploymentResult.Failed("That doesn't look like owner/name.")
        try {
            val deployments = get("/repos/$repo/deployments?per_page=1") ?: return@withContext DeploymentResult.Failed("GitHub didn't accept the request. Check your token.")
            val id = deployments.jsonArray.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                ?: return@withContext DeploymentResult.NoDeployment
            val status = get("/repos/$repo/deployments/$id/statuses?per_page=1")?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@withContext DeploymentResult.NoDeployment
            val state = status["state"]?.jsonPrimitive?.contentOrNull
            if (state != "success") return@withContext DeploymentResult.Failed("The latest deployment is $state.")
            val url = status["environment_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: status["target_url"]?.jsonPrimitive?.contentOrNull
            if (url != null && isSafeHttpsUrl(url)) DeploymentResult.Found(url) else DeploymentResult.NoDeployment
        } catch (_: IOException) {
            DeploymentResult.Failed("Couldn't reach GitHub.")
        }
    }

    private fun get(path: String) = client.newCall(
        Request.Builder().url("${baseUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer ${token.reveal()}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").build(),
    ).execute().use { r -> if (r.isSuccessful) runCatching { Json.parseToJsonElement(r.body?.string().orEmpty()) }.getOrNull() else null }

    companion object {
        val REPO = Regex("""[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}""")

        /** https only, a real host, no embedded credentials, sane length. The user still sees the link before it is sent. */
        fun isSafeHttpsUrl(url: String): Boolean = url.length <= 500 && runCatching {
            val u = URI(url)
            u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null
        }.getOrDefault(false)
    }
}
