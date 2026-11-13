package dev.localassistant.commands

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Something the user asked for that needs no language model to understand. */
sealed interface Command {
    /** Run [tool] with [arguments] and, if it worked, say [confirmation]. */
    data class Action(val tool: String, val arguments: Map<String, Any>, val confirmation: String) : Command

    /** The answer is already known. */
    data class Answer(val text: String) : Command

    /** Search the web for [query] and let the model answer from what comes back. */
    data class Lookup(val query: String) : Command
}

/**
 * Recognises the handful of things people ask a phone assistant for all day - a timer, an alarm,
 * a route, a call, the time - straight from the words, in Spanish and English.
 *
 * A model small enough for a phone takes seconds to decide on a tool call and sometimes decides
 * wrong; these requests are regular enough to match exactly, so they are answered at once and
 * the model is kept for everything that is not. Anything this does not recognise with confidence
 * returns null and goes to the model as before.
 */
class CommandRouter {

    fun match(utterance: String, spanish: Boolean, now: LocalDateTime = LocalDateTime.now()): Command? {
        val said = Said.of(utterance) ?: return null
        return showOnMap(said, spanish)
            ?: lookup(said)
            ?: timer(said, spanish)
            ?: alarm(said, spanish, now)
            ?: navigate(said, spanish)
            ?: message(said, spanish)
            ?: call(said, spanish)
            ?: openApp(said, spanish)
            ?: clock(said, spanish, now)
            ?: sum(said, spanish)
    }

    private fun timer(said: Said, spanish: Boolean): Command? {
        val reminder = REMIND_IN.containsMatchIn(said.digits)
        if (!reminder && !TIMER_WORD.containsMatchIn(said.digits)) return null
        if (QUESTION.containsMatchIn(said.digits)) return null
        val seconds = SpokenText.seconds(said.digits)?.takeIf { it <= MAX_TIMER_SECONDS } ?: return null
        val length = SpokenText.spokenDuration(seconds, spanish)
        return Command.Action(
            "set_timer",
            mapOf("seconds" to seconds),
            if (spanish) "Temporizador de $length en marcha." else "Timer set for $length.",
        )
    }

    private fun alarm(said: Said, spanish: Boolean, now: LocalDateTime): Command? {
        if (!ALARM_WORD.containsMatchIn(said.digits) || QUESTION.containsMatchIn(said.digits)) return null
        val (hour, minute) = ClockTime.parse(said.digits, now) ?: return null
        val time = "%02d:%02d".format(hour, minute)
        return Command.Action(
            "set_alarm",
            mapOf("hour" to hour, "minute" to minute),
            if (spanish) "Alarma puesta a las $time." else "Alarm set for $time.",
        )
    }

    private fun navigate(said: Said, spanish: Boolean): Command? {
        val match = NAVIGATE.find(said.folded) ?: return null
        var destination = said.original(match.groups[1]!!.range)
        var mode = "driving"
        for ((suffix, travel) in TRAVEL_MODES) {
            val found = suffix.find(SpokenText.fold(destination)) ?: continue
            destination = destination.substring(0, found.range.first).trim()
            mode = travel
            break
        }
        if (destination.isBlank()) return null
        return Command.Action(
            "open_maps",
            mapOf("destination" to destination, "navigate" to true, "mode" to mode),
            if (spanish) "Abriendo la ruta a $destination." else "Opening directions to $destination.",
        )
    }

    private fun showOnMap(said: Said, spanish: Boolean): Command? {
        val match = SHOW_ON_MAP.find(said.folded) ?: return null
        val place = said.original(match.groups[1]!!.range)
        return Command.Action(
            "open_maps",
            mapOf("destination" to place, "navigate" to false),
            if (spanish) "Te enseño $place en el mapa." else "Showing $place on the map.",
        )
    }

    private fun lookup(said: Said): Command? {
        val match = LOOKUP.find(said.folded) ?: return null
        return Command.Lookup(said.original(match.groups[1]!!.range))
    }

    private fun message(said: Said, spanish: Boolean): Command? {
        val match = MESSAGES.firstNotNullOfOrNull { it.find(said.folded) } ?: return null
        val recipient = said.original(match.groups[1]!!.range)
        val text = said.original(match.groups[2]!!.range).replaceFirstChar { it.uppercase() }
        if (recipient.split(' ').size > MAX_NAME_WORDS) return null
        return Command.Action(
            "send_message",
            mapOf("recipient" to recipient, "text" to text),
            if (spanish) "Mensaje para $recipient preparado. Confírmalo en pantalla." else "Message to $recipient is ready. Confirm it on screen.",
        )
    }

    private fun call(said: Said, spanish: Boolean): Command? {
        val match = CALL.find(said.folded) ?: return null
        val recipient = said.original(match.groups[1]!!.range)
        if (recipient.split(' ').size > MAX_NAME_WORDS) return null
        return Command.Action(
            "call_contact",
            mapOf("recipient" to recipient),
            if (spanish) "Abro el teléfono para llamar a $recipient." else "Opening the dialer to call $recipient.",
        )
    }

    private fun openApp(said: Said, spanish: Boolean): Command? {
        val match = OPEN_APP.find(said.folded) ?: return null
        val name = said.original(match.groups[1]!!.range)
        if (name.split(' ').size > MAX_NAME_WORDS) return null
        return Command.Action(
            "open_app",
            mapOf("name" to name),
            if (spanish) "Abriendo $name." else "Opening $name.",
        )
    }

    private fun clock(said: Said, spanish: Boolean, now: LocalDateTime): Command? = when {
        TIME_QUESTION.containsMatchIn(said.folded) -> {
            val time = now.format(DateTimeFormatter.ofPattern("H:mm"))
            Command.Answer(if (spanish) (if (now.hour == 1) "Es la $time." else "Son las $time.") else "It's $time.")
        }
        DATE_QUESTION.containsMatchIn(said.folded) -> {
            val locale = if (spanish) Locale("es", "ES") else Locale.ENGLISH
            val pattern = if (spanish) "EEEE, d 'de' MMMM 'de' yyyy" else "EEEE, d MMMM yyyy"
            val date = now.format(DateTimeFormatter.ofPattern(pattern, locale))
            Command.Answer(if (spanish) "Hoy es $date." else "Today is $date.")
        }
        else -> null
    }

    private fun sum(said: Said, spanish: Boolean): Command? {
        val expression = said.digits.replace(SUM_PREFIX, "")
        val value = Arithmetic.evaluate(expression) ?: return null
        val result = Arithmetic.format(value, spanish)
        return Command.Answer(if (spanish) "El resultado es $result." else "That's $result.")
    }

    /**
     * What was said, without the courtesy around it. [folded] lines up character by character
     * with the part of the original it came from, so names and places keep their own spelling.
     */
    private class Said(private val source: String, private val offset: Int, val folded: String) {
        val digits: String = SpokenText.digitize(folded)

        fun original(range: IntRange) = source.substring(offset + range.first, offset + range.last + 1).trim()

        companion object {
            fun of(utterance: String): Said? {
                val folded = SpokenText.fold(utterance)
                val start = LEADING.find(folded)?.range?.last?.plus(1) ?: 0
                val end = TRAILING.find(folded, start)?.range?.first ?: folded.length
                if (end <= start) return null
                return Said(utterance, start, folded.substring(start, end))
            }

            private val LEADING = Regex(
                """^(?:[\s¿¡"']|(?:oye|hey|ok|okay|vale|venga|por favor|porfa|please|""" +
                    """(?:me )?puedes|(?:me )?podrias|can you|could you|quiero que|necesito que)[,\s]+)*""",
            )
            private val TRAILING = Regex("""(?:[\s,]+(?:por favor|porfa|please|gracias|thanks|thank you))*[\s?!.]*$""")
        }
    }

    private companion object {
        const val MAX_TIMER_SECONDS = 86_400
        const val MAX_NAME_WORDS = 4

        val QUESTION = Regex("""^(?:que|como|cuanto|cuanta|cuando|por que|what|how|when|why)\b""")
        val TIMER_WORD = Regex("""\b(?:temporizador|timer|cuenta atras|countdown)\b""")
        val REMIND_IN = Regex("""^(?:avisame|avisa|despiertame|wake me(?: up)?) (?:en|dentro de|in) \S""")
        val ALARM_WORD = Regex("""\b(?:alarma|alarm|despiertame|despiertanos|wake me)\b""")

        val NAVIGATE = Regex(
            """^(?:(?:llevame|llevanos|guiame|navega|navegar|(?:pon(?:me)? |dame )?(?:la |una )?ruta|""" +
                """como (?:llego|llegar|voy|ir|se va|puedo (?:ir|llegar))|(?:dame )?indicaciones)""" +
                """ (?:(?:hasta|hacia|para ir a|para llegar a|para|en direccion a|a|al) )+""" +
                """|(?:navigate|take me|drive(?: me)?|(?:give me |get )?directions|route|how (?:do|can) i get|how to get) """ +
                """(?:to |towards ))(.+)$""",
        )
        val TRAVEL_MODES = listOf(
            Regex(""" (?:andando|caminando|a pie|walking|on foot|by foot)$""") to "walking",
            Regex(""" (?:en bici(?:cleta)?|by bike|by bicycle|cycling)$""") to "bicycling",
            Regex(""" (?:en transporte publico|en metro|en autobus|en bus|by transit|by public transport|by bus)$""") to "transit",
            Regex(""" (?:en coche|conduciendo|by car|driving)$""") to "driving",
        )
        val SHOW_ON_MAP = Regex(
            """^(?:ensename|muestrame|muestra|busca(?:me)?|show me|show|find) (.+?) """ +
                """(?:en (?:el |los )?(?:mapas?|maps|google maps)|(?:on|in) (?:the )?(?:map|maps))$""",
        )
        val LOOKUP = Regex(
            """^(?:busca(?:me)?|buscar|googlea|search(?: the web)?(?: for)?|look up|google)""" +
                """(?: en internet| en la web| en google| on the web| online)? (.+?)""" +
                """(?: en internet| en la web| en google| on the web| online)?$""",
        )
        val MESSAGES = listOf(
            Regex(
                """^(?:envia(?:le)?|manda(?:le)?|escribe(?:le)?|pon(?:le)?) (?:un |una )?(?:mensaje|sms|whatsapp|wasap|guasap|texto) """ +
                    """(?:a |al |para )(.+?)(?: diciendo(?:le)? que | diciendo(?:le)? | que diga | que ponga | con el texto |: |, )(.+)$""",
            ),
            Regex("""^(?:dile|escribe(?:le)?) (?:a |al )(.+?) que (.+)$"""),
            Regex(
                """^(?:send|write)(?: an?)? (?:message|text|sms|whatsapp)(?: message)? to (.+?)""" +
                    """(?: saying that | saying | that says |: |, )(.+)$""",
            ),
            Regex("""^(?:text|message|tell) (.+?) (?:saying that |saying |that )(.+)$"""),
        )
        val CALL = Regex("""^(?:llama|llamar|telefonea|haz una llamada|call|phone|ring|dial) (?:a la |a |al |to )?(.+)$""")
        val OPEN_APP = Regex(
            """^(?:abre(?:me)?|abrir|lanza|ejecuta|inicia|open|launch|start|run) """ +
                """(?:(?:la app|la aplicacion|the app|el|la|los|las|the) )*(.+?)(?: app)?$""",
        )
        val TIME_QUESTION = Regex("""^(?:que hora (?:es|tenemos)|dime la hora|what time is it|what's the time|what is the time)$""")
        val DATE_QUESTION = Regex(
            """^(?:que dia es(?: hoy)?|a que (?:dia )?estamos(?: hoy)?|que fecha es(?: hoy)?|""" +
                """what day is it(?: today)?|what's the date(?: today)?|what is the date(?: today)?|what's today's date)$""",
        )
        val SUM_PREFIX = Regex(
            """^(?:cuanto (?:es|son|da|dan|hacen|vale)|calcula(?:me)?|dime cuanto es|""" +
                """what is|what's|whats|calculate|compute|how much is) """,
        )
    }
}
