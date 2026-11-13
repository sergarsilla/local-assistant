package dev.localassistant.tools

enum class MapsScheme { NAVIGATION, GEO }

/** A null [packageName] leaves the intent open to the system default or its chooser. */
data class MapsRoute(val scheme: MapsScheme, val packageName: String?)

sealed interface MessageRoute {
    data object Sms : MessageRoute

    /** Opens the chat with this number, in international format without the plus. */
    data class WhatsAppChat(val digits: String) : MessageRoute

    /** WhatsApp asks who the text is for. */
    data object WhatsAppShare : MessageRoute
}

/** Decides which app and which kind of link an action goes to; no Android types, so it is testable. */
object AppRouting {
    /**
     * The first route that [resolves]. Turn-by-turn only goes to an app the user picked: without a
     * package it would always land on Google Maps, the one app sure to claim that scheme.
     */
    fun maps(navigate: Boolean, chosenPackage: String?, resolves: (MapsRoute) -> Boolean): MapsRoute? {
        val candidates = buildList {
            if (chosenPackage != null) {
                if (navigate) add(MapsRoute(MapsScheme.NAVIGATION, chosenPackage))
                add(MapsRoute(MapsScheme.GEO, chosenPackage))
            }
            add(MapsRoute(MapsScheme.GEO, null))
        }
        return candidates.firstOrNull(resolves)
    }

    /** [encodedQuery] must already be URI-encoded; [mode] is the scheme's one-letter travel mode. */
    fun mapsUri(scheme: MapsScheme, encodedQuery: String, mode: String) = when (scheme) {
        MapsScheme.NAVIGATION -> "google.navigation:q=$encodedQuery&mode=$mode"
        MapsScheme.GEO -> "geo:0,0?q=$encodedQuery"
    }

    fun message(app: MessagingApp, whatsAppInstalled: Boolean, number: String): MessageRoute {
        if (app != MessagingApp.WHATSAPP || !whatsAppInstalled) return MessageRoute.Sms
        val trimmed = number.trim()
        val digits = trimmed.filter(Char::isDigit)
        // A chat link needs the country code, and only a number written with a plus is sure to have it.
        return if (trimmed.startsWith("+") && digits.isNotEmpty()) MessageRoute.WhatsAppChat(digits) else MessageRoute.WhatsAppShare
    }

    /** [encodedText] must already be URI-encoded. */
    fun whatsAppChatUrl(digits: String, encodedText: String) = "https://wa.me/$digits?text=$encodedText"
}
