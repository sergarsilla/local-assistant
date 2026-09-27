package dev.localassistant

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration

/**
 * The same context, speaking the language chosen for this app.
 *
 * Android applies a per-app language to activities on their behalf, but the assistant's windows
 * are built from services: the panel, the voice keyboard and the wake word's notification are
 * all born with the phone's language instead. They ask for the app's own.
 */
fun Context.inAppLanguage(): Context {
    val locales = getSystemService(LocaleManager::class.java)
        ?.applicationLocales
        ?.takeIf { !it.isEmpty }
        ?: return this
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocales(locales) })
}
