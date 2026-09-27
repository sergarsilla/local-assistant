package dev.localassistant.keyboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.localassistant.AssistantRuntime
import dev.localassistant.inAppLanguage
import dev.localassistant.ui.MainActivity
import dev.localassistant.ui.keyboard.VoiceKeyboard
import dev.localassistant.ui.keyboard.VoiceKeyboardActions
import dev.localassistant.ui.navigation.Destination
import dev.localassistant.ui.theme.AssistantTheme
import dev.localassistant.voice.Dictation
import dev.localassistant.voice.DictationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A voice keyboard: dictation into any text field, offline, with the assistant's recognizer.
 * Keyboards with a voice key (HeliBoard, FUTO…) switch to it through its "voice" subtype.
 */
class VoiceKeyboardService :
    InputMethodService(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dictation: Dictation

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        dictation = Dictation(this, scope, AssistantRuntime.speech, AssistantRuntime.voiceSettings)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateInputView(): View {
        // The keyboard window is not an Activity, so it provides the owners Compose expects.
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
        }
        return ComposeView(inAppLanguage()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@VoiceKeyboardService))
            setContent {
                AssistantTheme {
                    val state by dictation.state.collectAsState()
                    VoiceKeyboard(state, actions)
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        dictation.prepare()
        if (!restarting && AssistantRuntime.voiceSettings.state.value.keyboardListensOnOpen) startDictation()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dictation.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        scope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private val actions = object : VoiceKeyboardActions {
        override fun toggleDictation() {
            if (dictation.state.value is DictationState.Listening) dictation.finishSpeaking() else startDictation()
        }

        override fun type(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun backspace() {
            // A key event, unlike deleting text directly, also removes a selection.
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }

        override fun enter() {
            val options = currentInputEditorInfo?.imeOptions ?: 0
            val action = options and EditorInfo.IME_MASK_ACTION
            val hasAction = action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED &&
                options and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0
            if (hasAction) currentInputConnection?.performEditorAction(action) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }

        override fun switchKeyboard() {
            if (!switchToPreviousInputMethod()) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }

        override fun openSettings() {
            startActivity(MainActivity.intent(this@VoiceKeyboardService, Destination.SETTINGS))
        }
    }

    private fun startDictation() {
        val subtype = getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype
        dictation.start(subtype?.languageTag) { text -> commitDictated(text) }
    }

    /** Joins the dictated words to what is already written: a space before, and the right case. */
    private fun commitDictated(text: String) {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString().orEmpty()
        connection.commitText(DictationFormatter.join(before, text), 1)
    }

    private companion object {
        const val CONTEXT_CHARS = 3
    }
}
