package dev.localassistant

/**
 * What the user said, or what came back for them, as it may appear in the system log. The log
 * outlives the app's own history and its delete button, so a release build writes only how long
 * the text was; a debug build keeps the words, which is what the bench reads.
 */
fun loggable(text: String): String = if (BuildConfig.DEBUG) "'$text'" else "${text.length} chars"
