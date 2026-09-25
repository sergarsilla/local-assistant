package dev.localassistant.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession.AssistState
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
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
import dev.localassistant.llm.ChatSession
import dev.localassistant.voice.VoiceController
import dev.localassistant.ui.AssistantPanel
import dev.localassistant.ui.MainActivity

/**
 * The session window is not an Activity, so it has to provide the owners Compose expects.
 */
class AssistantSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private var showRequestedAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    val screen = ScreenContext()
    private val chat = ChatSession(
        holder = AssistantRuntime.engine,
        scope = AssistantRuntime.scope,
        tools = AssistantRuntime.tools,
        history = AssistantRuntime.history,
    ).apply { screenFor = screen::textFor }
    private val voice = VoiceController(
        context = context,
        chat = chat,
        holder = AssistantRuntime.speech,
        settings = AssistantRuntime.voiceSettings,
        scope = AssistantRuntime.scope,
    )

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onCreateContentView(): View {
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
        }
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@AssistantSession))
            setContent {
                AssistantPanel(
                    chat = chat,
                    voice = voice,
                    screen = screen,
                    engineState = AssistantRuntime.engine.state,
                    onDismiss = ::hide,
                    onOpenApp = ::openApp,
                )
            }
        }
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        if (state.isFocused) {
            screen.update(state.assistStructure)
            Log.i(AssistantService.TAG, "Screen text: ${screen.text.value?.length ?: 0} chars")
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        showRequestedAt = SystemClock.elapsedRealtime()
        super.onShow(args, showFlags)
        // Load the model and process the fixed start of the prompt while the user is still
        // typing or speaking.
        AssistantRuntime.engine.preload(chat::preface)
        voice.preload()
        // Sending the user to another app means getting out of their way; a timer does not.
        AssistantRuntime.tools.activityStarter = { intent, leavesPanel ->
            startAssistantActivity(intent)
            // Tools run on the model's thread; the session is only touched on the main one.
            if (leavesPanel) mainHandler.post { hide() }
        }
        if (AssistantRuntime.voiceSettings.state.value.listenOnOpen) voice.startListening()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Log.i(AssistantService.TAG, "Session shown, flags=$showFlags")
        logFirstFrame()
    }

    override fun onHide() {
        super.onHide()
        AssistantRuntime.tools.activityStarter = null
        screen.clear()
        chat.stop()
        voice.onHide()
        AssistantRuntime.engine.scheduleRelease()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        voice.close()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun openApp(tab: Int) {
        startAssistantActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab))
        hide()
    }

    private fun logFirstFrame() {
        val decor = window.window?.decorView ?: return
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                val elapsed = SystemClock.elapsedRealtime() - showRequestedAt
                Log.i(AssistantService.TAG, "Panel first frame after ${elapsed}ms")
                decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
    }
}
