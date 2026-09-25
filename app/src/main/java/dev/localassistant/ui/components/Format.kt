package dev.localassistant.ui.components

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** "2.6 GB", in the user's locale; "?" when the size is unknown. */
@Composable
fun formatSize(bytes: Long): String =
    if (bytes <= 0) "?" else Formatter.formatShortFileSize(LocalContext.current, bytes)
