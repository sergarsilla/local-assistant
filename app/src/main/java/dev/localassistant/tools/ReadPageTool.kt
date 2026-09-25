package dev.localassistant.tools

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import org.json.JSONObject

/** Fetches one page and hands the model its readable text, without opening a browser. */
class ReadPageTool(private val listener: ToolActivityListener) : AssistantTool {
    override val name = "read_web_page"

    override fun describe() = """
        {"name":"read_web_page",
         "description":"Read a page found with web_search when its snippet is not enough.",
         "parameters":{"type":"object","properties":{
           "url":{"type":"string"}},
          "required":["url"]}}
    """.trimIndent()

    override fun run(arguments: JSONObject): String {
        val url = arguments.optString("url")
        if (!url.startsWith("https://")) return JSONObject().put("error", "only https pages can be read").toString()
        listener.onToolStarted(name, url)
        return runCatching { JSONObject().put("text", readableText(fetch(url)).take(MAX_CHARS)).toString() }
            .getOrElse { JSONObject().put("error", it.message ?: "fetch failed").toString() }
    }

    /**
     * Only public HTTPS pages, and every redirect is checked again: text from a web page must not
     * be able to steer the model into reading the router's admin page or anything else on the
     * local network.
     */
    private fun fetch(start: String): String {
        var url = URL(start)
        repeat(MAX_REDIRECTS + 1) {
            check(url.protocol == "https" && isPublicHost(url.host)) { "only public https pages can be read" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LocalAssistant")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: error("HTTP $code without a target")
                    url = URL(url, location)
                    return@repeat
                }
                check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
                return readCapped(connection.inputStream.bufferedReader())
            } finally {
                connection.disconnect()
            }
        }
        error("too many redirects")
    }

    private fun isPublicHost(host: String): Boolean = runCatching {
        InetAddress.getAllByName(host).none {
            it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress ||
                // IPv6 unique local addresses (fc00::/7) are not covered by isSiteLocalAddress.
                (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
        }
    }.getOrDefault(false)

    private fun readCapped(reader: java.io.Reader): String {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (out.length < MAX_HTML_CHARS) {
            val read = reader.read(buffer)
            if (read < 0) break
            out.append(buffer, 0, read)
        }
        return out.toString()
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
        const val MAX_HTML_CHARS = 2_000_000
        const val MAX_REDIRECTS = 4
    }
}
