package dev.localassistant.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.localassistant.R
import dev.localassistant.ui.markdown.Markdown

/** Copies a reply as plain text; Android 13+ confirms the copy itself, so there is no toast. */
fun copyReply(context: Context, markdown: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), Markdown.plainText(markdown)))
}
