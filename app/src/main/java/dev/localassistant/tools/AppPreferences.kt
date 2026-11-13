package dev.localassistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MessagingApp { SMS, WHATSAPP }

/** Which apps the tools hand work to. Nothing is chosen until the user does, so Android decides. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)
    private val _mapsPackage = MutableStateFlow(prefs.getString(KEY_MAPS, null))
    private val _messagingApp = MutableStateFlow(
        MessagingApp.entries.firstOrNull { it.name == prefs.getString(KEY_MESSAGING, null) } ?: MessagingApp.SMS,
    )

    /** Null leaves the choice to the system default or its chooser. */
    val mapsPackage: StateFlow<String?> = _mapsPackage.asStateFlow()
    val messagingApp: StateFlow<MessagingApp> = _messagingApp.asStateFlow()

    fun setMapsPackage(packageName: String?) {
        prefs.edit().putString(KEY_MAPS, packageName).apply()
        _mapsPackage.value = packageName
    }

    fun setMessagingApp(app: MessagingApp) {
        prefs.edit().putString(KEY_MESSAGING, app.name).apply()
        _messagingApp.value = app
    }

    private companion object {
        const val KEY_MAPS = "maps_package"
        const val KEY_MESSAGING = "messaging_app"
    }
}

data class AppChoice(val packageName: String, val label: String)

/** What is installed, as far as the manifest's `<queries>` lets this app see. */
class InstalledApps(private val context: Context) {
    /** Every app that shows a `geo:` location, by name. */
    fun mapsApps(): List<AppChoice> {
        val packages = context.packageManager
        return packages
            .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), PackageManager.MATCH_ALL)
            .map { AppChoice(it.activityInfo.packageName, it.loadLabel(packages).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun whatsAppInstalled() = runCatching { context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0) }.isSuccess

    fun handles(intent: Intent) = intent.resolveActivity(context.packageManager) != null
}

const val WHATSAPP_PACKAGE = "com.whatsapp"
