package dev.localassistant.system

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.StatFs
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import dev.localassistant.keyboard.VoiceKeyboardService

/** A snapshot of the phone settings the app depends on; re-read whenever a screen resumes. */
data class SystemStatus(
    val isAssistant: Boolean = false,
    val micGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val screenTextEnabled: Boolean = false,
    val screenshotEnabled: Boolean = false,
    val keyboardEnabled: Boolean = false,
    val networkAllowed: Boolean = true,
    val freeBytes: Long = Long.MAX_VALUE,
) {
    companion object {
        fun read(context: Context): SystemStatus {
            fun granted(permission: String) =
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            val resolver = context.contentResolver
            return SystemStatus(
                isAssistant = context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
                micGranted = granted(Manifest.permission.RECORD_AUDIO),
                contactsGranted = granted(Manifest.permission.READ_CONTACTS),
                notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS),
                // Not public API constants, but stable for years and what Settings itself writes.
                screenTextEnabled = Settings.Secure.getInt(resolver, "assist_structure_enabled", 1) == 1,
                screenshotEnabled = Settings.Secure.getInt(resolver, "assist_screenshot_enabled", 1) == 1,
                keyboardEnabled = keyboardEnabled(context),
                networkAllowed = granted(Manifest.permission.INTERNET),
                freeBytes = StatFs(context.filesDir.path).availableBytes,
            )
        }

        private fun keyboardEnabled(context: Context): Boolean {
            val ours = ComponentName(context, VoiceKeyboardService::class.java)
            return context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.any { it.component == ours } == true
        }

        /** Where the user picks the digital assistant app. */
        val assistantSettings = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

        /** Assist & voice input, where screen text and screenshots are switched on. */
        val assistSettings = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

        val keyboardSettings = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)

        fun appDetails(context: Context) = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        )
    }
}
