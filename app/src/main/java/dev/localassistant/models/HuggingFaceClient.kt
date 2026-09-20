package dev.localassistant.models

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class RemoteRepo(val id: String, val downloads: Long)

/** Anonymous, read-only access to the public Hugging Face model API. */
object HuggingFaceClient {
    private const val API = "https://huggingface.co/api"

    suspend fun search(query: String): List<RemoteRepo> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val litertQuery = URLEncoder.encode("$query litertlm", "UTF-8")
        // LiteRT models come first: they are the ones that can use tools and web search.
        val official = fetchArray("$API/models?search=$q&author=litert-community&sort=downloads&direction=-1&limit=10")
        val litert = fetchArray("$API/models?search=$litertQuery&sort=downloads&direction=-1&limit=15")
        val gguf = fetchArray("$API/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=25")
        (official.repos() + litert.repos() + gguf.repos()).distinctBy { it.id }
    }

    /** Every file in the repository, including nested directories. */
    suspend fun allFiles(repo: String): List<RemoteModelFile> = withContext(Dispatchers.IO) {
        val tree = fetchArray("$API/models/$repo/tree/main?recursive=true")
        (0 until tree.length()).map { tree.getJSONObject(it) }
            .filter { it.optString("type") == "file" }
            .map { RemoteModelFile(repo, it.getString("path"), it.optLong("size")) }
    }

    /** Single-file model weights in the repo; split shards and vision projectors are left out. */
    suspend fun files(repo: String): List<RemoteModelFile> = withContext(Dispatchers.IO) {
        val tree = fetchArray("$API/models/$repo/tree/main?recursive=true")
        (0 until tree.length()).map { tree.getJSONObject(it) }
            .filter { it.optString("type") == "file" }
            .map { RemoteModelFile(repo, it.getString("path"), it.optLong("size")) }
            .filter { file ->
                ModelFormat.of(file.path) != null &&
                    !file.fileName.startsWith("mmproj") &&
                    !SHARD.containsMatchIn(file.fileName)
            }
            .sortedBy { it.sizeBytes }
    }

    private fun JSONArray.repos(): List<RemoteRepo> =
        (0 until length()).map { getJSONObject(it) }.map { RemoteRepo(it.getString("id"), it.optLong("downloads")) }

    private fun fetchArray(url: String): JSONArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode} for $url" }
            JSONArray(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private val SHARD = Regex("-\\d{5}-of-\\d{5}")
}
