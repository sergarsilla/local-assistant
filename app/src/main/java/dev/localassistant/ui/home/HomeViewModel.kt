package dev.localassistant.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localassistant.AssistantRuntime
import dev.localassistant.assistant.AssistantService
import dev.localassistant.models.Catalog
import dev.localassistant.models.ModelDownloadService
import dev.localassistant.models.Storage
import dev.localassistant.models.TransferState
import dev.localassistant.models.Transfers
import dev.localassistant.system.SystemStatus
import dev.localassistant.voice.VoicePack
import dev.localassistant.voice.VoicePacks
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

enum class StepId { ASSISTANT, MICROPHONE, SPEECH_MODEL, LANGUAGE_MODEL, VOICE, SCREEN, KEYBOARD }

sealed interface StepState {
    data object Done : StepState
    data object Todo : StepState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : StepState {
        val fraction: Float get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
    }
    data class Failed(val message: String) : StepState
}

data class SetupStep(val id: StepId, val state: StepState, val sizeBytes: Long = 0)

data class HomeUiState(
    val required: List<SetupStep> = emptyList(),
    val optional: List<SetupStep> = emptyList(),
    val activeModel: String? = null,
) {
    val requiredDone: Int get() = required.count { it.state == StepState.Done }
    val ready: Boolean get() = required.isNotEmpty() && requiredDone == required.size
}

sealed interface HomeEvent {
    data class NotEnoughSpace(val neededBytes: Long) : HomeEvent
    data object NotTheAssistant : HomeEvent
}

/** First-run checklist: what is missing for the assistant to work, each with one action to fix it. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = AssistantRuntime
    private val system = MutableStateFlow(SystemStatus.read(application))
    private val events = Channel<HomeEvent>(Channel.BUFFERED)
    val messages: Flow<HomeEvent> = events.receiveAsFlow()

    val state: StateFlow<HomeUiState> = combine(
        system,
        runtime.models.installed,
        VoicePacks.installed,
        Transfers.state,
        runtime.voiceSettings.state,
    ) { status, models, _, transfers, voice ->
        val defaultVoice = defaultVoiceFor(voice.language)
        HomeUiState(
            required = listOf(
                SetupStep(StepId.ASSISTANT, doneIf(status.isAssistant)),
                SetupStep(StepId.MICROPHONE, doneIf(status.micGranted)),
                SetupStep(
                    StepId.SPEECH_MODEL,
                    downloadStep(voice.activeSpeechToText != null, transfers[VoicePack.STT_PARAKEET.id]),
                    VoicePack.STT_PARAKEET.approxBytes,
                ),
                SetupStep(
                    StepId.LANGUAGE_MODEL,
                    downloadStep(models.isNotEmpty(), transfers[Catalog.default.file.fileName]),
                    Catalog.default.file.sizeBytes,
                ),
            ),
            optional = listOf(
                SetupStep(
                    StepId.VOICE,
                    downloadStep(voice.voiceFor(voice.language) != null, transfers[defaultVoice.id]),
                    defaultVoice.approxBytes,
                ),
                SetupStep(StepId.SCREEN, doneIf(status.screenTextEnabled)),
                SetupStep(StepId.KEYBOARD, doneIf(status.keyboardEnabled)),
            ),
            activeModel = runtime.models.active?.name,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    /** Re-reads permissions and settings the user may have changed elsewhere. */
    fun refresh() {
        system.value = SystemStatus.read(getApplication())
    }

    fun downloadSpeechModel() = downloadPack(VoicePack.STT_PARAKEET)

    fun downloadVoice() = downloadPack(defaultVoiceFor(runtime.voiceSettings.state.value.language))

    fun downloadLanguageModel() {
        val file = Catalog.default.file
        if (!Storage.fits(file.sizeBytes, system.value.freeBytes)) {
            events.trySend(HomeEvent.NotEnoughSpace(file.sizeBytes))
            return
        }
        ModelDownloadService.start(getApplication(), file)
    }

    fun retry(step: StepId) {
        val key = when (step) {
            StepId.SPEECH_MODEL -> VoicePack.STT_PARAKEET.id
            StepId.LANGUAGE_MODEL -> Catalog.default.file.fileName
            StepId.VOICE -> defaultVoiceFor(runtime.voiceSettings.state.value.language).id
            else -> return
        }
        ModelDownloadService.retry(getApplication(), key)
    }

    fun tryAssistant() {
        if (!AssistantService.open()) events.trySend(HomeEvent.NotTheAssistant)
    }

    private fun downloadPack(pack: VoicePack) {
        if (!Storage.fits(pack.approxBytes, system.value.freeBytes)) {
            events.trySend(HomeEvent.NotEnoughSpace(pack.approxBytes))
            return
        }
        ModelDownloadService.startPack(getApplication(), pack)
    }

    private fun doneIf(done: Boolean): StepState = if (done) StepState.Done else StepState.Todo

    private fun downloadStep(installed: Boolean, transfer: TransferState?): StepState = when {
        installed -> StepState.Done
        transfer is TransferState.Running -> StepState.Downloading(transfer.doneBytes, transfer.totalBytes)
        transfer is TransferState.Failed -> StepState.Failed(transfer.message)
        else -> StepState.Todo
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        fun defaultVoiceFor(language: String) = if (language == "en") VoicePack.TTS_EN_LESSAC else VoicePack.TTS_ES_MIRO
    }
}
