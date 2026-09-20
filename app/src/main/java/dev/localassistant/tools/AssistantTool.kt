package dev.localassistant.tools

import org.json.JSONObject

/** Something the model can call mid-answer, described to it with a JSON schema. */
interface AssistantTool {
    val name: String

    /** OpenAPI-style description: {"name":…, "description":…, "parameters":{…}}. */
    fun describe(): String

    /** Runs the tool and returns a compact JSON result for the model. */
    fun run(arguments: JSONObject): String
}

/** Reports what a tool is doing so the panel can show it. */
fun interface ToolActivityListener {
    fun onToolStarted(name: String, summary: String)
}
