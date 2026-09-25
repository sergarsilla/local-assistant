package dev.localassistant

import android.app.Application
import dev.localassistant.llm.EngineHolder
import dev.localassistant.models.ModelImporter
import dev.localassistant.models.ModelStore
import dev.localassistant.history.ConversationStore
import dev.localassistant.tools.ToolRegistry
import dev.localassistant.voice.CaptureRecorder
import dev.localassistant.voice.SpeechHolder
import dev.localassistant.voice.TtsVoices
import dev.localassistant.voice.VoicePacks
import dev.localassistant.voice.VoiceSettings
import dev.localassistant.wake.WakeWordSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide dependencies; the voice interaction service keeps this process alive. */
object AssistantRuntime {
    lateinit var models: ModelStore
        private set
    lateinit var engine: EngineHolder
        private set
    lateinit var importer: ModelImporter
        private set
    lateinit var speech: SpeechHolder
        private set
    lateinit var ttsVoices: TtsVoices
        private set
    lateinit var voiceSettings: VoiceSettings
        private set
    lateinit var tools: ToolRegistry
        private set
    lateinit var history: ConversationStore
        private set
    lateinit var wakeWord: WakeWordSettings
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Debug builds keep every captured utterance so the audio can be checked afterwards. */
    var captures: CaptureRecorder? = null
        private set

    fun init(app: Application) {
        models = ModelStore(app)
        engine = EngineHolder(app, models, scope)
        importer = ModelImporter(app, models)
        speech = SpeechHolder(app, scope)
        ttsVoices = TtsVoices(app, scope)
        voiceSettings = VoiceSettings(app)
        tools = ToolRegistry(app)
        history = ConversationStore(app)
        wakeWord = WakeWordSettings(app)
        VoicePacks.init(app)
        if (BuildConfig.DEBUG) {
            captures = CaptureRecorder(File(app.getExternalFilesDir("bench"), "captures"))
        }
    }
}

class LocalAssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AssistantRuntime.init(this)
    }
}
