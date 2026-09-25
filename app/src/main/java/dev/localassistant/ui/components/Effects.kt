package dev.localassistant.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.concurrent.atomic.AtomicReference

/** Runs [action] every time the screen comes back, e.g. after the user changed a system setting. */
@Composable
fun OnResume(action: () -> Unit) {
    val current = rememberUpdatedState(action)
    LifecycleResumeEffect(Unit) {
        current.value()
        onPauseOrDispose {}
    }
}

/**
 * Returns a function that asks for [permission] and then runs [then], whatever the answer:
 * the caller re-reads the state rather than trusting the result.
 */
@Composable
fun rememberPermissionRequest(permission: String, then: () -> Unit): () -> Unit {
    val after = rememberUpdatedState(then)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { after.value() }
    return { launcher.launch(permission) }
}

/**
 * Wraps a download so the notification permission is requested the first time one starts: the
 * progress notification is where a long download can be followed and cancelled.
 */
@Composable
fun rememberWithNotifications(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    // Survives recomposition while the permission dialog is up.
    val pending = remember { AtomicReference<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending.getAndSet(null)?.invoke()
    }
    return { download ->
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            download()
        } else {
            pending.set(download)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
