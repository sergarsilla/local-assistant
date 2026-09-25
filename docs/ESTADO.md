# Estado del proyecto — revisión del 25 de septiembre de 2026

Revisión completa del código (≈5.000 líneas propias), contrastada con el encargo inicial
(`AGENT_PROMPT.md`), más una investigación de qué hay en el mercado y qué pide la gente.

## 1. Resumen

La app tiene **todas las piezas** del encargo: se registra como asistente del sistema, abre un
panel sobre la app actual, reconoce y sintetiza voz offline, ejecuta GGUF (llama.cpp) y
`.litertlm` (LiteRT-LM), llama a herramientas y guarda historial. Pero la sensación de «muy
bugueada» tenía causas concretas, casi todas de **concurrencia y ciclo de vida** (recursos nativos
liberados mientras se usaban, peticiones descartadas en silencio, preferencias que no se
actualizaban) y un fallo de empaquetado que **rompía la voz en cualquier build que no fuera el
del PC original**. Esta revisión corrige 20 fallos, acelera la primera respuesta y añade el
dictado para los botones de micrófono de otras apps.

## 2. Estado frente al encargo inicial

| Fase / requisito del encargo | Estado antes | Estado ahora |
|---|---|---|
| 1. Investigar (asistentes, foros, modelos, motores) | Hecho en su día, sin documento en el repo | Resumen actualizado en la §5 |
| 2. Preguntar antes de diseñar | Hecho (no hay registro en el repo) | — |
| 3. Arquitectura justificada | Implícita en el código y el README | Resumida en la §4 |
| 4. Entorno en CachyOS | Hecho (SDK, NDK 27.1, JDK 17) | CI ahora compila en cada push |
| 5a. Aparece como asistente y abre con pulsación larga | ✅ `VoiceInteractionService` + sesión Compose, primer frame ~45 ms | ✅ |
| 5b. Voz → modelo → respuesta | ⚠️ Funcionaba en el PC de desarrollo; **roto en cualquier otro build** (faltaba el VAD) | ✅ |
| 6. Medir latencia, tok/s, RAM, batería | Parcial: arnés `BenchmarkActivity`, logs de prefill/tok/s | Igual + logs de backend y del pre-relleno; falta medir en el móvil tras los cambios (§7) |
| Panel encima sin salir de la app | ✅ | ✅ Temporizadores y alarmas ya no cierran el panel |
| Texto en streaming, voz opcional e interrumpible | ✅ con fallos (preguntas perdidas al interrumpir, cierres nativos) | ✅ |
| Entiende la pantalla cuando se lo pido | ⚠️ Solo con el chip y solo escribiendo; el volcado de pantalla acababa en el historial | ✅ También por voz («resúmelo», «¿qué pone aquí?»), sin ensuciar el historial. Captura (imagen) pendiente |
| Gestión de modelos sin pelearse con archivos | ✅ con fallos (la importación se cancelaba al cambiar de pestaña) | ✅ |
| No se cierra por memoria ni calienta en reposo | ✅ en reposo; ⚠️ posibles SIGSEGV al cerrar | ✅ |
| Offline; la red es opcional y la activo yo | ⚠️ La búsqueda web se activaba sola si había permiso de red | ✅ Interruptor explícito, apagado por defecto |
| Dictado offline para todo el móvil | ✅ `RecognitionService` (API `SpeechRecognizer`) | ✅ + `ACTION_RECOGNIZE_SPEECH` (botones de micro de otras apps) |
| Español e inglés | ✅ | ✅ + la voz se elige por el idioma real de la respuesta |

## 3. Fallos encontrados y corregidos

### Críticos (cierres o funciones inservibles)

1. **El VAD no estaba en el repositorio.** `.gitignore` excluye `*.onnx`, así que
   `app/src/main/assets/silero_vad.onnx` solo existía en el PC original (el `NOTICE` ya decía que
   iba incluido). Cualquier otro build —CI incluido— producía un APK cuyo micrófono lanzaba una
   excepción al primer uso: sin voz en el panel y sin dictado del sistema.
   → Añadido el modelo (644 KB, MIT) con una excepción en `.gitignore`.
2. **El modelo se podía liberar mientras generaba.** `EngineHolder.acquire()` devolvía el motor
   sin retenerlo; «Usar» o «Borrar» en Modelos, o el temporizador de liberación, llamaban a
   `nativeFree` con `nativeGenerate` en marcha → *use-after-free* (SIGSEGV). Además el panel y
   otros llamadores podían decodificar a la vez sobre el mismo `llama_context`.
   → `EngineHolder.use { }` retiene el motor durante toda la generación; liberar o cambiar de
   modelo espera a que termine.
3. **LiteRT-LM cerraba la conversación antes de que parase.** Tras `cancelProcess()` (asíncrono)
   la siguiente pregunta llamaba a `conversation.close()` con el turno nativo aún vivo.
   → Se usa la API de *callback*, que avisa cuando el turno cancelado se ha detenido de verdad, y
   se espera (máx. 3 s) antes de reconstruir.
4. **Las voces TTS se liberaban mientras sintetizaban.** Cada panel cargaba su voz y la liberaba
   en `onDestroy`, justo después de cerrar, aunque `tts.generate()` (no interrumpible) siguiera
   en curso → SIGSEGV al cerrar el panel hablando. Además cada apertura recargaba la voz (1–2 s
   con Kokoro).
   → `TtsVoices`: caché de proceso, síntesis bajo el mismo candado que la liberación, y
   liberación diferida de 30 s.
5. **El reconocedor se podía usar a la vez o liberar a mitad.** `SpeechHolder.acquire()` devolvía
   el reconocedor y la transcripción ocurría fuera del candado; el dictado de otra app y el panel
   podían decodificar simultáneamente sobre el mismo `OfflineRecognizer`. Si una carga fallaba,
   se volvía a entregar el reconocedor ya cerrado.
   → `SpeechHolder.transcribe()` transcribe bajo el candado.

### Funcionales

6. **«No hay ningún modelo de voz instalado» con Parakeet descargado.** La preferencia de STT se
   calculaba una sola vez al arrancar (por defecto Canary). Igual con las voces: una voz recién
   descargada no se usaba hasta pulsar «Usar».
   → Las elecciones se resuelven contra lo instalado en el momento de usarlas.
7. **Preguntas perdidas al interrumpir.** `ChatSession.send()` descartaba en silencio la pregunta
   si la respuesta anterior aún estaba terminando (la cancelación es asíncrona). En modo
   conversación, hablar encima de la respuesta perdía lo dicho.
   → `send()` cancela la respuesta en curso, espera a que acabe y entonces envía.
8. **El volcado de pantalla acababa en el historial** como si lo hubieras escrito tú (y como
   título de la conversación: «Text currently on the user's screen:»).
   → El mensaje guarda por separado lo que dijiste y lo que recibió el modelo.
9. **La pantalla solo se entendía escribiendo y con el chip.** Decir «resúmelo» no servía.
   → Se adjunta si el chip está activo **o** si la pregunta habla de la pantalla.
10. **El prompt de sistema decía «sin acceso a internet»** mientras ofrecía `web_search`, y no
    incluía la hora: «pon una alarma dentro de 20 minutos» o «mañana» dependían de la suerte.
    → Prompt coherente con las herramientas disponibles, respuestas breves (se leen en voz alta)
    y fecha/hora/zona en cada pregunta.
11. **Temporizadores y alarmas cerraban el panel**, así que no veías ni oías la confirmación.
    → Solo se aparta el panel cuando la acción abre otra app (apps, mapas, calendario, SMS).
12. **La búsqueda web se activaba sola** con el permiso de red, contra «nada debe salir del
    teléfono sin que yo lo decida».
    → Interruptor en Configuración, apagado por defecto.
13. **Aviso falso en el panel**: «Este modelo no puede usar acciones» con modelos GGUF, que ya
    llaman a herramientas desde `6316725`. → Eliminado.
14. **La importación de un modelo se cancelaba al cambiar de pestaña** (usaba el `scope` de la
    composición) y dejaba un error «Job was cancelled». → Corre en el `scope` de la app.
15. **Escrituras SQLite en el hilo principal** al enviar cada mensaje (incluida la primera
    apertura de la base de datos). → Movidas a `Dispatchers.IO`, en orden.
16. **Tras el último intento de herramientas no se pedía respuesta**: si el modelo agotaba las 4
    rondas llamando herramientas, la respuesta quedaba vacía. → La última ronda no ofrece
    herramientas y obliga a contestar.
17. **`VoicePack.byId` lanzaba excepción** con un id guardado que ya no existe (cierre al
    arrancar tras renombrar un paquete). → Devuelve `null`.
18. **`ModelDownloadService`** podía no llamar a `startForeground` con un id de paquete
    desconocido (cierre por *ForegroundServiceDidNotStartInTime*). → Corregido.

### Seguridad

19. **`read_web_page` leía cualquier URL**, incluidas `http://` y la red local, siguiendo
    redirecciones a ciegas y cargando la página entera en memoria. Un texto malicioso en una web
    podía inducir al modelo a leer `http://192.168.1.1/…`.
    → Solo HTTPS a hosts públicos, cada redirección se revalida, lectura limitada a 2 MB.
20. El `AndroidManifest` y los textos decían que la red solo se usa para descargas. → Corregido.

## 4. Mejoras de rendimiento y funciones nuevas

- **GPU en LiteRT-LM** (`Backend.GPU()`), con vuelta automática a CPU. Google publica
  ~52 tok/s para Gemma 4 E2B en GPU frente a los ~19–20 tok/s que la app medía en CPU. Como los
  drivers de GPU pueden matar el proceso en vez de lanzar una excepción, se escribe una marca
  antes del intento y se borra después: si al arrancar la marca sigue ahí, la GPU nos tumbó y no
  se reintenta hasta que lo pidas (interruptor en Configuración). La cabecera del panel muestra
  `· GPU` o `· CPU`.
- **Pre-relleno del prefacio al abrir el panel.** El prompt de sistema + definiciones de
  herramientas son ~900 tokens que antes se procesaban *después* de que terminaras de hablar.
  Ahora se procesan mientras hablas: en LiteRT-LM con `prefillPrefaceOnInit`, en llama.cpp
  decodificando el prefijo en la caché KV (la pregunta real reutiliza todo hasta donde empiezan
  tus palabras). Es la principal mejora de latencia hasta la primera palabra.
- **Prompt de sistema estable**: la hora va en cada pregunta, no en el prompt, para que ninguno
  de los dos motores tenga que reprocesarlo entre turnos.
- **Dictado para los botones de micrófono** (`RecognizeSpeechActivity`,
  `ACTION_RECOGNIZE_SPEECH`): sin la app de Google nada lo atiende en GrapheneOS, así que el
  micrófono de buscadores, mapas o notas no hacía nada. Es de lo más pedido en los foros.
- **Voz según el idioma de la respuesta**: si respondes o preguntas en inglés con el asistente en
  español, habla con una voz inglesa (si hay una instalada).
- **Voz precargada** al abrir el panel si «responder en voz alta» está activo.

### Arquitectura (sin cambios de fondo; se mantiene y está justificada)

- **Motores**: LiteRT-LM para `.litertlm` (GPU/NPU, herramientas nativas, Gemma 4 multimodal) y
  llama.cpp para GGUF (cualquier modelo de Hugging Face; KleidiAI en CPU; el Tensor G5 no tiene
  Adreno, así que su backend GPU no aplica). Una interfaz `LlmEngine` común.
- **STT**: sherpa-onnx con Parakeet TDT 0.6B v3 (detecta idioma) o Canary 180M; Silero VAD.
- **TTS**: sherpa-onnx con Piper o Kokoro.
- **Sistema**: `VoiceInteractionService` + `VoiceInteractionSession` (panel), `RecognitionService`
  (dictado vía API), `RecognizeSpeechActivity` (dictado vía intent), herramientas por intents
  estándar (reloj, calendario, SMS, mapas) lanzadas con `startAssistantActivity`.

## 5. Investigación: mercado, foros y qué cambia en el plan

> Nota: desde este entorno los foros de GrapheneOS, Reddit y la documentación de Google estaban
> bloqueados por la política de red; lo siguiente sale de los resultados del buscador y del código
> fuente de LiteRT-LM 0.17.1.

**Qué hay:**

- **Layla** (v6.1) ya puede ponerse como asistente por defecto y funcionar offline
  ([dev.to](https://dev.to/layla_network_ai/how-to-replace-google-gemini-with-a-local-llm-as-your-phones-default-assistant-1n8b)).
  Es la competencia directa: la diferencia de esta app es ser nativa, libre, sin Google y con
  dictado de sistema.
- **gos-ai** (Vosk + llama.cpp) es el proyecto más parecido para GrapheneOS; en modo local **no
  tiene herramientas, búsqueda ni visión**
  ([GitHub](https://github.com/mx37/gos-ai)). Esta app ya las tiene.
- **lori-voice-assistant**: palabra de activación con openWakeWord + Vosk, sin permiso de internet
  ([GitHub](https://github.com/DavideFantasia/lori-voice-assistant)).
- **Dicio**: temporizadores y llamadas con Vosk; las alarmas solo existen dentro de la app
  ([Saipien](https://saipien.org/dicio-privacy-first-on-device-android-assistant-for-timers-calls-and-media-with-limits/)).
  Esta app delega en el reloj del sistema, que es lo correcto.
- **ChatGPT y Perplexity** ya pueden ser el asistente de Android, y Gemini sustituye a Google
  Assistant en 2026 ([9to5Google](https://9to5google.com/2025/12/19/google-assistant-gemini-2026/),
  [GSMArena](https://m.gsmarena.com/chatgpt_can_now_replace_gemini_on_android-news-66958.php)).
- **Google AI Edge Gallery**: chat, *Ask Image*, *Audio Scribe* y *Mobile Actions* (llamada a
  funciones en el dispositivo)
  ([Google Developers Blog](https://developers.googleblog.com/on-device-function-calling-in-google-ai-edge-gallery/)).

**Qué pide la gente (GrapheneOS Discuss, r/GrapheneOS, issues de proyectos parecidos):**

1. **Dictado offline en todo el sistema** — hilos recurrentes de «speech to text» / «voice to
  text» ([1](https://discuss.grapheneos.org/d/13095-speech-to-text),
  [2](https://discuss.grapheneos.org/d/278-voice-to-text)). → Cubierto con `RecognitionService` y
  ahora también `ACTION_RECOGNIZE_SPEECH`. **Siguiente paso de más valor: un teclado de voz (IME)**
  para dictar en cualquier campo.
2. **Asistente de voz sin Google**, también en coche (Android Auto)
  ([1](https://discuss.grapheneos.org/d/4940-voice-assistant),
  [2](https://discuss.grapheneos.org/d/10414-android-auto-offline-voice-assistant)).
3. **Modelos offline que funcionen bien en GrapheneOS**
  ([hilo](https://discuss.grapheneos.org/d/22477-ai-model-for-offline-use-on-grapheneos)).
4. Palabra de activación sin gastar batería; entender pantalla/fotos; acciones reales del sistema.

**Motores y modelos:**

- LiteRT-LM en GPU: ~52 tok/s con Gemma 4 E2B y <1,5 GB de memoria activa
  ([dev.to](https://dev.to/samdude/gemma-4-on-android-tricks-for-faster-on-device-inference-3kj5),
  [Google](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)).
  La NPU puede tumbar el proceso por fragmentación de drivers: tratarla como experimental.
  LiteRT-LM 0.17 prefiere NPU→GPU→CPU en modo automático
  ([releases](https://github.com/google-ai-edge/LiteRT/releases)); la 0.17.1 trae además
  `Backend.GOOGLE_TENSOR` (NPU del Tensor) para modelos compilados para ella.
- Gemma 4 tiene **visión y audio nativos**; Qwen 3.5 destaca en multilingüe (0,8B en adelante)
  ([MindStudio](https://www.mindstudio.ai/blog/gemma-4-vs-qwen-3-5-open-weight-comparison)).

**Qué cambia del plan:** (a) la GPU pasa a ser la opción por defecto en LiteRT-LM — hecho;
(b) el dictado de sistema es el rasgo diferencial más pedido: completar con un IME de voz;
(c) usar la visión de Gemma 4 para «¿qué hay en mi pantalla?» con la captura del asistente;
(d) probar Qwen 3.5 en GGUF para español frente a Gemma 4 E2B.

## 6. Pendiente y próximos pasos (por prioridad)

1. **Verificar en el Pixel 10** (§7). Todo lo de arriba está revisado y la lógica no-Compose se
   compiló contra el framework de Android, pero aquí no hay SDK: el build completo lo hace el CI
   y la prueba real solo puede ser en el dispositivo.
2. **Teclado de voz (IME)** con el mismo STT: dictado en cualquier campo de texto.
3. **Captura de pantalla + Gemma 4 visión**: `onHandleScreenshot` + `Content.ImageBytes`
   (requiere `visionBackend` y más memoria; hacerlo opcional).
4. **Contactos para `send_message`**: hoy el destinatario es el texto literal («mamá»), así que
   la app de mensajes recibe un número inválido. Resolver nombres con `READ_CONTACTS` (permiso
   opcional) o abrir el selector de contactos.
5. **Continuar una conversación del historial** en el panel.
6. **Catálogo**: añadir Qwen 3.5 (GGUF) tras medirlo en español; los Kokoro ES y EN descargan el
   mismo repositorio dos veces (190 MB cada uno) — compartir directorio.
7. **Palabra de activación opcional** (openWakeWord en un servicio en primer plano), desactivada
   por defecto, con su coste de batería medido y visible.
8. **NPU del Tensor** (`Backend.GOOGLE_TENSOR`) como opción experimental cuando haya modelos
   compilados para ella.
9. **Tests**: `LanguageGuess`, la detección de «habla de la pantalla», `OpenAppTool.score` y
   `ReadPageTool` son lógica pura, fáciles de cubrir con tests JVM.

## 7. Cómo verificar en el móvil

```bash
./gradlew :app:installDebug
adb logcat -s EngineHolder LiteRtEngine LlamaCppEngine ChatSession VoiceController Speaker TtsVoices SpeechHolder RecognizeSpeech
```

Qué comprobar:

- **GPU**: al abrir el panel con Gemma 4 E2B `.litertlm`, `Loaded … on GPU in …ms` y la cabecera
  `· GPU`. Si el driver falla, `GPU backend unavailable, using the CPU` (o, si tumbó el proceso,
  `The previous GPU start crashed` en el siguiente arranque) y el aviso en Configuración.
- **Pre-relleno**: `Prepared the preface in …ms` / `Prepared N prompt tokens…` antes de que
  termines de hablar; luego `Reply: … prefill=…ms` debería bajar claramente en el primer turno.
- **tok/s**: la línea `Reply:` y el valor en la pestaña Modelos.
- **Voz**: dictar recién instalado sin tocar «Usar»; hablar encima de una respuesta en modo
  conversación (la nueva pregunta debe llegar); cerrar el panel mientras habla (sin cierre).
- **Pantalla**: con «Usar texto de la pantalla» activo, abrir sobre un artículo y decir
  «resúmelo».
- **Temporizador**: «pon un temporizador de 5 minutos» — el panel sigue abierto y confirma.
- **Dictado de otras apps**: pulsar el micrófono de una barra de búsqueda (la de un navegador o una
  app de notas) — debe salir el diálogo «Escuchando…».
- **Reposo**: `adb shell dumpsys meminfo dev.localassistant` 60 s después de cerrar el panel.
