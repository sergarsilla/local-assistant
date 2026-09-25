package dev.localassistant.ui.settings

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localassistant.AssistantRuntime
import dev.localassistant.llm.Accelerator
import dev.localassistant.llm.EnginePreferences
import dev.localassistant.models.ModelDownloadService
import dev.localassistant.models.Storage
import dev.localassistant.models.TransferState
import dev.localassistant.models.Transfers
import dev.localassistant.system.SystemStatus
import dev.localassistant.voice.PackKind
import dev.localassistant.voice.VoicePack
import dev.localassistant.voice.VoicePacks
import dev.localassistant.voice.VoicePreferences
import dev.localassistant.wake.WakePhrase
import dev.localassistant.wake.WakeWordPreferences
import dev.localassistant.wake.WakeWordService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

data class PackUi(val pack: VoicePack, val installed: Boolean, val inUse: Boolean, val transfer: TransferState?)

data class SettingsUiState(
    val status: SystemStatus = SystemStatus(),
    val voice: VoicePreferences? = null,
    val speechPacks: List<PackUi> = emptyList(),
    val voicesEs: List<PackUi> = emptyList(),
    val voicesEn: List<PackUi> = emptyList(),
    val engine: EnginePreferences = EnginePreferences(),
    val webSearch: Boolean = false,
    val saveHistory: Boolean = true,
    val wakeWord: WakeWordPreferences = WakeWordPreferences(),
    /** "" follows the system; otherwise "es" or "en". */
    val appLanguage: String = "",
    val pendingPackDelete: VoicePack? = null,
)

sealed interface SettingsEvent {
    data class NotEnoughSpace(val neededBytes: Long) : SettingsEvent
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = AssistantRuntime
    private val system = MutableStateFlow(SystemStatus.read(application))
    private val appLanguage = MutableStateFlow(readAppLanguage())
    private val pendingPackDelete = MutableStateFlow<VoicePack?>(null)
    private val events = Channel<SettingsEvent>(Channel.BUFFERED)
    val messages: Flow<SettingsEvent> = events.receiveAsFlow()

    private val voiceState = combine(
        runtime.voiceSettings.state,
        VoicePacks.installed,
        Transfers.state,
        pendingPackDelete,
    ) { voice, installed, transfers, deleting ->
        fun ui(pack: VoicePack, inUse: Boolean) = PackUi(pack, pack in installed, inUse, transfers[pack.id])
        SettingsUiState(
            voice = voice,
            speechPacks = VoicePack.entries.filter { it.kind == PackKind.SPEECH_TO_TEXT }
                .map { ui(it, voice.activeSpeechToText == it) },
            voicesEs = VoicePack.voicesFor("es").map { ui(it, voice.voiceFor("es") == it) },
            voicesEn = VoicePack.voicesFor("en").map { ui(it, voice.voiceFor("en") == it) },
            pendingPackDelete = deleting,
        )
    }

    val state: StateFlow<SettingsUiState> = combine(
        voiceState,
        combine(system, runtime.engine.preferences, runtime.tools.webSearch, runtime.history.enabled) { a, b, c, d ->
            Quad(a, b, c, d)
        },
        runtime.wakeWord.state,
        appLanguage,
    ) { voice, (status, engine, webSearch, saveHistory), wake, language ->
        voice.copy(
            status = status,
            engine = engine,
            webSearch = webSearch,
            saveHistory = saveHistory,
            wakeWord = wake,
            appLanguage = language,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun refresh() {
        system.value = SystemStatus.read(getApplication())
        appLanguage.value = readAppLanguage()
    }

    fun setListenOnOpen(value: Boolean) = runtime.voiceSettings.update { it.copy(listenOnOpen = value) }

    fun setSpeakReplies(value: Boolean) = runtime.voiceSettings.update { it.copy(speakReplies = value) }

    fun setKeyboardListens(value: Boolean) = runtime.voiceSettings.update { it.copy(keyboardListensOnOpen = value) }

    fun downloadPack(pack: VoicePack) {
        if (!Storage.fits(pack.approxBytes, system.value.freeBytes)) {
            events.trySend(SettingsEvent.NotEnoughSpace(pack.approxBytes))
            return
        }
        ModelDownloadService.startPack(getApplication(), pack)
    }

    fun cancelPackDownload(pack: VoicePack) {
        ModelDownloadService.cancel(getApplication(), pack.id)
        Transfers.clear(pack.id)
    }

    fun usePack(pack: VoicePack) = runtime.voiceSettings.update { prefs ->
        when {
            pack.kind == PackKind.SPEECH_TO_TEXT -> prefs.copy(speechToText = pack)
            pack.language != null -> prefs.copy(voices = prefs.voices + (pack.language to pack))
            else -> prefs
        }
    }

    fun requestPackDelete(pack: VoicePack) {
        pendingPackDelete.value = pack
    }

    fun cancelPackDelete() {
        pendingPackDelete.value = null
    }

    fun confirmPackDelete() {
        val pack = pendingPackDelete.value ?: return
        pendingPackDelete.value = null
        VoicePacks.delete(pack)
        refresh()
    }

    fun setAccelerator(accelerator: Accelerator) = runtime.engine.setAccelerator(accelerator)

    fun setUnderstandImages(value: Boolean) = runtime.engine.setUnderstandImages(value)

    fun setWebSearch(value: Boolean) = runtime.tools.setWebSearch(value)

    fun setSaveHistory(value: Boolean) = runtime.history.setEnabled(value)

    fun setWakeWord(enabled: Boolean) {
        runtime.wakeWord.update { it.copy(enabled = enabled) }
        WakeWordService.syncWithSettings(getApplication())
    }

    fun setWakePhrase(phrase: WakePhrase) = runtime.wakeWord.update { it.copy(phrase = phrase) }

    fun setAppLanguage(tag: String) {
        getApplication<Application>().getSystemService(LocaleManager::class.java)?.applicationLocales =
            LocaleList.forLanguageTags(tag)
        appLanguage.value = tag
    }

    private fun readAppLanguage(): String =
        getApplication<Application>().getSystemService(LocaleManager::class.java)
            ?.applicationLocales?.toLanguageTags().orEmpty()

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
