package dev.localassistant.tools

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Fetches one page and hands the model its readable text, without opening a browser. */
class ReadPageTool(private val listener: ToolActivityListener) : AssistantTool {
    override val name = "read_web_page"

    override fun describe() = """
        {"name":"read_web_page",
         "description":"Read the main text of a web page found with web_search, when the snippets are not enough.",
         "parameters":{"type":"object","properties":{
           "url":{"type":"string","description":"Full https URL of the page."}},
          "required":["url"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val url = arguments.optString("url")
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return JSONObject().put("error", "invalid url").toString()
        }
        listener.onToolStarted(name, url)
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LocalAssistant")
            }
            val html = try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
                connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }
            JSONObject().put("text", readableText(html).take(MAX_CHARS)).toString()
        }.getOrElse { JSONObject().put("error", it.message ?: "fetch failed").toString() }
    }

    private fun readableText(html: String) = html
        .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?is)<(nav|footer|header|aside)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val MAX_CHARS = 4000
    }
}
