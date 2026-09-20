package dev.localassistant.tools

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Searches the web through DuckDuckGo's HTML endpoint and returns titles, links and snippets.
 * Nothing is opened in a browser: only the text reaches the model.
 */
class WebSearchTool(private val listener: ToolActivityListener) : AssistantTool {
    override val name = "web_search"

    override fun describe() = """
        {"name":"web_search",
         "description":"Search the web for current facts you cannot know. Returns short snippets.",
         "parameters":{"type":"object","properties":{
           "query":{"type":"string","description":"In the user's language."}},
          "required":["query"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val query = arguments.optString("query").ifBlank { return error("missing query") }
        listener.onToolStarted(name, query)
        return runCatching { search(query) }
            .onFailure { Log.w(TAG, "Search failed", it) }
            .getOrElse { error(it.message ?: "search failed") }
    }

    private fun search(query: String): String {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val html = try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }

        val results = JSONArray()
        RESULT.findAll(html).take(MAX_RESULTS).forEach { match ->
            val (link, title) = match.destructured
            val snippet = SNIPPET.find(html, match.range.last)?.groupValues?.get(1).orEmpty()
            results.put(
                JSONObject()
                    .put("title", clean(title))
                    .put("url", decodeRedirect(link))
                    .put("snippet", clean(snippet)),
            )
        }
        return JSONObject().put("results", results).toString()
    }

    private fun decodeRedirect(href: String): String {
        val target = Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1) ?: return href
        return java.net.URLDecoder.decode(target, "UTF-8")
    }

    private fun clean(html: String) = html
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun error(message: String) = JSONObject().put("error", message).toString()

    private companion object {
        const val TAG = "WebSearchTool"
        const val MAX_RESULTS = 5
        const val USER_AGENT = "Mozilla/5.0 (Android) LocalAssistant"
        val RESULT = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val SNIPPET = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    }
}
