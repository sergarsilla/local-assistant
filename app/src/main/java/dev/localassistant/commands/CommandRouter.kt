package dev.localassistant.commands

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Something the user asked for that needs no language model to understand. */
sealed interface Command {
    /** Run [tool] with [arguments] and, if it worked, say [confirmation]. */
    data class Action(
        val tool: String,
        val arguments: Map<String, Any>,
        val confirmation: String,
        /** The name as said, which the tool may resolve to a contact or an app by another name. */
        val subject: String? = null,
    ) : Command

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
        // Actions before searches: "dile a Ana que tengo noticias" is a message, not the news.
        return showOnMap(said, spanish)
            ?: youtube(said, spanish)
            ?: timer(said, spanish)
            ?: alarm(said, spanish, now)
            ?: navigate(said, spanish)
            ?: message(said, spanish)
            ?: call(said, spanish)
            ?: openApp(said, spanish)
            ?: clock(said, spanish, now)
            ?: sum(said, spanish)
            ?: searches(said)
    }

    private fun searches(said: Said): Command? = lookup(said) ?: recent(said)

    /**
     * Removing or asking about an alarm is not a request for one, and the clock app sets it
     * without showing anything: "quita la alarma de las 7" used to set one for seven.
     */
    private fun undoesOrAsks(said: Said) = UNDO.containsMatchIn(said.digits) || QUESTION.containsMatchIn(said.digits)

    /**
     * A question about how things stand now. A model answers these from memory, fluently and
     * wrongly - every one tested named a winner for "the latest Grand Prix" - so they are
     * searched for. Last in the chain: the time and the date are answered before getting here.
     */
    private fun recent(said: Said): Command? =
        if (RECENT_QUESTION.containsMatchIn(said.folded)) Command.Lookup(said.text) else null

    private fun timer(said: Said, spanish: Boolean): Command? {
        val reminder = REMIND_IN.containsMatchIn(said.digits)
        if (!reminder && !TIMER_WORD.containsMatchIn(said.digits)) return null
        if (undoesOrAsks(said)) return null
        val seconds = SpokenText.seconds(said.digits)?.takeIf { it <= MAX_TIMER_SECONDS } ?: return null
        val length = SpokenText.spokenDuration(seconds, spanish)
        return Command.Action(
            "set_timer",
            mapOf("seconds" to seconds),
            if (spanish) "Temporizador de $length en marcha." else "Timer set for $length.",
        )
    }

    private fun alarm(said: Said, spanish: Boolean, now: LocalDateTime): Command? {
        if (!ALARM_WORD.containsMatchIn(said.digits) || undoesOrAsks(said)) return null
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
        if (destination.isBlank() || NOT_A_PLACE.containsMatchIn(SpokenText.fold(destination))) return null
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

    private fun youtube(said: Said, spanish: Boolean): Command? {
        val match = YOUTUBE.find(said.folded) ?: return null
        val query = said.original(match.groups[1]!!.range)
        return Command.Action(
            "search_youtube",
            mapOf("query" to query),
            if (spanish) "Buscando $query en YouTube." else "Searching YouTube for $query.",
        )
    }

    private fun lookup(said: Said): Command? {
        LOOKUP.find(said.folded)?.let { return Command.Lookup(said.original(it.groups[1]!!.range)) }
        // News and weather are never something a model can know, so asking for them is a search.
        return if (CURRENT_AFFAIRS.containsMatchIn(said.folded)) Command.Lookup(said.text) else null
    }

    private fun message(said: Said, spanish: Boolean): Command? {
        val match = MESSAGES.firstNotNullOfOrNull { it.find(said.folded) } ?: return null
        val recipient = said.original(match.groups[1]!!.range)
        val text = said.original(match.groups[2]!!.range).replaceFirstChar { it.uppercase() }
        if (!plausibleName(recipient)) return null
        return Command.Action(
            "send_message",
            mapOf("recipient" to recipient, "text" to text),
            if (spanish) "Mensaje para $recipient preparado. Confírmalo en pantalla." else "Message to $recipient is ready. Confirm it on screen.",
            subject = recipient,
        )
    }

    private fun call(said: Said, spanish: Boolean): Command? {
        val match = CALL.find(said.folded) ?: return null
        val recipient = said.original(match.groups[1]!!.range)
        if (!plausibleName(recipient)) return null
        return Command.Action(
            "call_contact",
            mapOf("recipient" to recipient),
            if (spanish) "Abro el teléfono para llamar a $recipient." else "Opening the dialer to call $recipient.",
            subject = recipient,
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
            subject = name,
        )
    }

    /** "me", "la atención", "him": the words after "call" or "tell" that are not a person. */
    private fun plausibleName(name: String): Boolean {
        val words = SpokenText.fold(name).split(' ')
        return words.size <= MAX_NAME_WORDS && words.first() !in NOT_A_NAME
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
        val text: String get() = original(folded.indices)

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
                """^(?:[\s¿¡"']|(?:oye|hey|ok|okay|vale|venga|ay|pues|bueno|a ver|eh|mira|anda|entonces|hola|buenas|hello|hi|""" +
                    """por favor|porfa|please|""" +
                    """(?:me )?puedes|(?:me )?podrias|can you|could you|quiero que|necesito que)[,\s]+)*""",
            )
            private val TRAILING = Regex("""(?:[\s,]+(?:por favor|porfa|please|gracias|thanks|thank you))*[\s?!.]*$""")
        }
    }

    private companion object {
        const val MAX_TIMER_SECONDS = 86_400
        const val MAX_NAME_WORDS = 4

        val QUESTION = Regex(
            """^(?:que|como|cuanto|cuanta|cuantas|cuantos|cuando|por que|cual|cuales|donde|tengo|tienes|hay|habia|""" +
                """si|dime si|sabes si|what|how|when|why|which|where|is|are|was|were|do|does|did|have|has|can i|if)\b""",
        )
        val UNDO = Regex(
            // "Para" stops something only as the first word: "una alarma para ver las noticias".
            """^(?:para|pausa)\b|""" +
                """\b(?:quita(?:me)?|borra(?:me)?|apaga(?:me)?|cancela(?:me)?|elimina(?:me)?|deten|desactiva|""" +
                """quitar|borrar|apagar|cancelar|eliminar|parar|pausar|desactivar|no suena|no ha sonado|""" +
                """cancel|delete|remove|stop|pause|turn off|switch off|dismiss|snooze|disable|didn't|did not|doesn't|won't)\b""",
        )
        val NOT_A_PLACE = Regex(
            """^(?:ser|estar|tener|hacer|dormir|fin de mes|final de mes|la luna|un acuerdo|una conclusion|""" +
                """be|sleep|the next|next|the moon|the end)\b|\b(?:nivel|level|cancion|song|capitulo|chapter|pantalla|screen)\b""",
        )
        val NOT_A_NAME = setOf(
            "me", "mi", "te", "nos", "le", "les", "la", "lo", "el", "atencion", "him", "her", "them", "us", "it", "you",
            "a", "an", "the", "my", "someone", "alguien",
        )
        val TIMER_WORD = Regex("""\b(?:temporizador|timer|cuenta atras|countdown)\b""")
        val REMIND_IN = Regex("""^(?:avisame|avisa|despiertame|wake me(?: up)?) (?:en|dentro de|in) \S""")
        val ALARM_WORD = Regex("""\b(?:alarma|alarm|despiertame|despiertanos|wake me)\b""")

        val NAVIGATE = Regex(
            """^(?:(?:abre|open) (?:el |los |the )?(?:mapas?|maps|google maps)(?: y| and|,)? )?""" +
                """(?:(?:llevame|llevanos|guiame|navega|navegar|(?:pon(?:me)? |dame )?(?:la |una )?ruta|""" +
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
        // A word for "now" alone is not enough - "la última letra del abecedario" does not change -
        // so it has to come with something that does.
        val RECENT_QUESTION = Regex(
            """^(?:quien|que|cual|cuales|cuanto|cuanta|cuantos|cuantas|como (?:va|van|quedo|quedaron|esta|estan)|donde|""" +
                """who|what|which|how much|how many|where)\b.*""" +
                """\b(?:ultim[oa]s?|actual|actuales|actualmente|ahora mismo|hoy|esta semana|este ano|este mes|reciente|""" +
                """recientes|recientemente|latest|last|current|currently|today|right now|this week|this year|this month)\b.*""" +
                """\b(?:partido|carrera|gran premio|resultado|resultados|clasificacion|liga|mundial|campeon|campeonato|""" +
                """elecciones|presidente|presidenta|ministro|gobierno|papa|precio|precios|cotizacion|bolsa|euribor|bitcoin|""" +
                """noticia|noticias|version|actualizacion|episodio|capitulo|temporada|estreno|album|disco|terremoto|huelga|""" +
                """match|game|race|grand prix|result|results|standings|league|champion|election|president|prime minister|""" +
                """pope|price|prices|stock|news|version|update|episode|season|release|album|earthquake|strike)\b""",
        )
        val YOUTUBE = Regex(
            """^(?:(?:abre|open) youtube (?:y|and) )?(?:busca(?:me)?|buscar|pon(?:me)?|reproduce|ensename|muestrame|""" +
                """search(?: for)?|find|look up|play|show me) (?:(?:un |el |a |the )?videos? (?:de |sobre |of |about )?)?(.+?) (?:en|on) youtube$""",
        )
        val CURRENT_AFFAIRS = Regex(
            """\b(?:noticias|titulares|news|headlines|pronostico|forecast|weather)\b|""" +
                """\bque tiempo (?:hace|hara|va a hacer|va a ser|hay|tendremos|nos espera)\b|""" +
                """\b(?:el )?tiempo (?:de |para |que hara )?(?:hoy|manana|esta semana|este fin de semana)\b|""" +
                """\b(?:va a|will it) (?:llover|nevar|rain|snow)\b|""" +
                """\b(?:temperatura|temperature) (?:en|de|hoy|manana|fuera|ahora|in|today|tomorrow|outside|now)\b""",
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
        // Spanish needs the "a": "llama la atención" is not a call.
        val CALL = Regex(
            """^(?:(?:llama(?:me)?|llamar|telefonea|haz una llamada) (?:a la |a |al )|(?:call|phone|dial) (?:to )?)(.+)$""",
        )
        val OPEN_APP = Regex(
            """^(?:abre(?:me)?|abrir|lanza|ejecuta|inicia|open|launch|start|run) """ +
                """(?:(?:la app|la aplicacion|the app|el|la|los|las|the) )*(.+?)(?: app)?$""",
        )
        val TIME_QUESTION = Regex(
            """^(?:que hora (?:es|tenemos)|dime la hora|what time is it|what's the time|what is the time)(?: ahora(?: mismo)?| now| right now)?$""",
        )
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
