package dev.localassistant.commands

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {
    private val router = CommandRouter()
    private val morning = LocalDateTime.of(2026, 11, 13, 9, 30)

    private fun es(text: String, now: LocalDateTime = morning) = router.match(text, spanish = true, now = now)
    private fun en(text: String, now: LocalDateTime = morning) = router.match(text, spanish = false, now = now)

    private fun action(command: Command?): Command.Action {
        assertTrue("expected an action, got $command", command is Command.Action)
        return command as Command.Action
    }

    private fun answer(command: Command?): String {
        assertTrue("expected an answer, got $command", command is Command.Answer)
        return (command as Command.Answer).text
    }

    @Test
    fun `timers in both languages, with digits or words`() {
        val cases = mapOf(
            "Pon un temporizador de 3 minutos" to 180,
            "pon un temporizador de tres minutos" to 180,
            "Temporizador de diez minutos, por favor" to 600,
            "ponme un temporizador de media hora" to 1800,
            "pon un temporizador de una hora y media" to 5400,
            "temporizador de 1 hora y 20 minutos" to 4800,
            "pon un temporizador de treinta y cinco segundos" to 35,
            "avísame en 5 minutos" to 300,
            "oye, ¿puedes poner un temporizador de 2 minutos?" to 120,
        )
        for ((said, seconds) in cases) {
            val command = action(es(said))
            assertEquals(said, "set_timer", command.tool)
            assertEquals(said, seconds, command.arguments["seconds"])
        }
        assertEquals(300, action(en("Set a timer for five minutes")).arguments["seconds"])
        assertEquals(90, action(en("timer for 1 minute and 30 seconds")).arguments["seconds"])
        assertEquals(1500, action(en("set a twenty-five minute timer")).arguments["seconds"])
    }

    @Test
    fun `alarms pick the next time the clock shows that hour`() {
        fun time(command: Command?) = action(command).let {
            assertEquals("set_alarm", it.tool)
            it.arguments["hour"] to it.arguments["minute"]
        }
        assertEquals(7 to 30, time(es("Pon una alarma a las 7 y media de la mañana")))
        assertEquals(7 to 30, time(es("Pon una alarma a las 7:30", now = LocalDateTime.of(2026, 11, 13, 23, 0))))
        assertEquals(19 to 0, time(es("pon una alarma a las siete")))
        assertEquals(7 to 0, time(es("despiértame a las siete", now = LocalDateTime.of(2026, 11, 13, 22, 0))))
        assertEquals(18 to 45, time(es("alarma a las 7 menos cuarto de la tarde")))
        assertEquals(22 to 0, time(es("pon la alarma a las 10 de la noche")))
        assertEquals(0 to 0, time(es("alarma a las 12 de la noche")))
        assertEquals(15 to 5, time(es("alarma a las 15:05")))
        assertEquals(6 to 45, time(en("set an alarm for 6:45 am")))
        assertEquals(20 to 0, time(en("wake me up at 8pm")))
        assertEquals(7 to 30, time(en("set an alarm for half past 7 in the morning")))
    }

    @Test
    fun `navigation keeps the place as it was said`() {
        val sol = action(es("Llévame a la Puerta del Sol de Madrid"))
        assertEquals("open_maps", sol.tool)
        assertEquals("la Puerta del Sol de Madrid", sol.arguments["destination"])
        assertEquals(true, sol.arguments["navigate"])
        assertEquals("driving", sol.arguments["mode"])

        assertEquals("Atocha", action(es("¿Cómo llego a Atocha andando?")).arguments["destination"])
        assertEquals("walking", action(es("¿Cómo llego a Atocha andando?")).arguments["mode"])
        assertEquals("aeropuerto", action(es("ruta al aeropuerto")).arguments["destination"])
        assertEquals("casa de Ana", action(es("navega hasta casa de Ana en coche")).arguments["destination"])
        assertEquals("Heathrow", action(en("Navigate to Heathrow")).arguments["destination"])
        assertEquals("Santander", action(es("ay, pues llévame a Santander.")).arguments["destination"])
        assertEquals("Santander", action(es("Abre el mapa y llévame a Santander.")).arguments["destination"])
        assertEquals("la carretera de Ugena y Yeles", action(es("Llévame a la carretera de Ugena y Yeles.")).arguments["destination"])
        assertEquals("transit", action(en("directions to the British Museum by bus")).arguments["mode"])

        val shown = action(es("Enséñame Toledo en el mapa"))
        assertEquals("Toledo", shown.arguments["destination"])
        assertEquals(false, shown.arguments["navigate"])
    }

    @Test
    fun `calls, messages and apps`() {
        assertEquals("mamá", action(es("Llama a mamá")).arguments["recipient"])
        assertEquals("Ana García", action(es("llama a Ana García por favor")).arguments["recipient"])
        assertEquals("600 11 22 33", action(es("llama al 600 11 22 33")).arguments["recipient"])
        assertEquals("Dad", action(en("Call Dad")).arguments["recipient"])

        val message = action(es("Envía un mensaje a Ana diciendo que llego tarde"))
        assertEquals("send_message", message.tool)
        assertEquals("Ana", message.arguments["recipient"])
        assertEquals("Llego tarde", message.arguments["text"])
        assertEquals("Compre pan", action(es("dile a Javi que compre pan")).arguments["text"])
        assertEquals("I'm on my way", action(en("Text Sam saying I'm on my way")).arguments["text"])

        assertEquals("Spotify", action(es("Abre Spotify")).arguments["name"])
        assertEquals("cámara", action(es("abre la cámara")).arguments["name"])
        assertEquals("Signal", action(en("open the Signal app")).arguments["name"])
        assertEquals("Telegram", action(es("Abre Telegram.")).arguments["name"])
        val video = action(es("Búscame cómo hacer hula hoop en YouTube"))
        assertEquals("search_youtube", video.tool)
        assertEquals("cómo hacer hula hoop", video.arguments["query"])
        assertEquals("lofi", action(en("play lofi on YouTube")).arguments["query"])
        assertNull(es("Ábreme YouTube y dime qué tengo en el feed principal"))
    }

    @Test
    fun `sums are worked out rather than guessed`() {
        assertEquals("El resultado es 391.", answer(es("¿Cuánto es 17 por 23?")))
        assertEquals("El resultado es 391.", answer(es("cuánto es diecisiete por veintitrés")))
        assertEquals("El resultado es 30.", answer(es("cuanto es el 15 por ciento de 200")))
        assertEquals("El resultado es 2,5.", answer(es("cuánto es 10 dividido entre 4")))
        assertEquals("El resultado es 12.", answer(es("raíz cuadrada de 144")))
        assertEquals("El resultado es 14.", answer(es("cuánto es 2 más 3 por 4")))
        assertEquals("That's 391.", answer(en("What is 17 times 23?")))
        assertEquals("That's 2.5.", answer(en("what's 10 divided by 4")))
        assertEquals("That's 49.", answer(en("7 squared")))
    }

    @Test
    fun `the clock is read, not asked of the model`() {
        assertEquals("Son las 9:30.", answer(es("¿Qué hora es?")))
        assertEquals("Es la 1:05.", answer(es("que hora es", now = LocalDateTime.of(2026, 11, 13, 1, 5))))
        assertEquals("Hoy es viernes, 13 de noviembre de 2026.", answer(es("¿Qué día es hoy?")))
        assertEquals("It's 9:30.", answer(en("What time is it?")))
    }

    @Test
    fun `an explicit search carries only the query`() {
        assertEquals(Command.Lookup("quién ganó el último Gran Premio"), es("Busca en internet quién ganó el último Gran Premio"))
        assertEquals(Command.Lookup("el tiempo en Madrid"), es("busca el tiempo en Madrid en internet"))
        assertEquals(Command.Lookup("pixel 10 battery life"), en("search for pixel 10 battery life"))
        assertEquals(Command.Lookup("Cuáles son las noticias principales de hoy"), es("¿Cuáles son las noticias principales de hoy?"))
        assertEquals(Command.Lookup("qué tiempo hace en Toledo"), es("Oye, ¿qué tiempo hace en Toledo?"))
        assertEquals(Command.Lookup("What's the weather tomorrow"), en("What's the weather tomorrow?"))
        assertEquals(Command.Lookup("qué tiempo va a ser mañana"), es("Hola, ¿qué tiempo va a ser mañana?"))
    }

    @Test
    fun `anything that is not plainly a command goes to the model`() {
        val questions = listOf(
            "¿Qué es un temporizador?",
            "¿Cómo funciona una alarma de incendios?",
            "Explícame por qué el cielo es azul",
            "¿Cuánto tarda en cocerse un huevo?",
            "¿Qué hora es en Tokio?",
            "Llama la atención lo caro que está todo",
            "¿Quién inventó la imprenta?",
            "Escribe un poema sobre el mar",
            "Abre",
            "cuánto es",
            "hola",
            "Hola, ¿qué tal?",
            "¿Qué tiempo tarda en cocerse un huevo?",
            "",
            "What is the capital of Iceland?",
            "How does a timer work?",
            "Tell me a joke",
        )
        for (question in questions) assertNull(question, es(question) ?: en(question))
    }
}
