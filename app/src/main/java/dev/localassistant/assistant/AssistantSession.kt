package dev.localassistant.assistant

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
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
import dev.localassistant.wake.WakeWordService
import dev.localassistant.ui.MainActivity
import dev.localassistant.ui.navigation.Destination
import dev.localassistant.ui.panel.PanelRoute
import dev.localassistant.ui.theme.AssistantTheme
import kotlinx.coroutines.launch

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
    ).apply { screenFor = screen::attachmentFor }
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
        return ComposeView(inAppLanguage(context)).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@AssistantSession))
            setContent {
                AssistantTheme {
                    PanelRoute(chat = chat, voice = voice, screen = screen, onDismiss = ::hide, onOpenApp = ::openApp)
                }
            }
        }
    }

    /**
     * The panel window is built by the system, so it is born with the system language even when
     * the user chose another one for this app. Activities get that choice applied for them;
     * this window has to ask for it.
     */
    private fun inAppLanguage(context: Context): Context {
        val locales = context.getSystemService(LocaleManager::class.java)
            ?.applicationLocales
            ?.takeIf { !it.isEmpty }
            ?: return context
        val configuration = Configuration(context.resources.configuration).apply { setLocales(locales) }
        return context.createConfigurationContext(configuration)
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        if (state.isFocused) {
            screen.update(state.assistStructure)
            Log.i(AssistantService.TAG, "Screen text: ${screen.text.value?.length ?: 0} chars")
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        // Scaling and compressing takes a few tens of milliseconds; the panel does not wait for it.
        AssistantRuntime.scope.launch { screen.updateScreenshot(screenshot) }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        showRequestedAt = SystemClock.elapsedRealtime()
        super.onShow(args, showFlags)
        args?.getLong(AssistantService.EXTRA_CONVERSATION_ID, NO_CONVERSATION)
            ?.takeIf { it != NO_CONVERSATION }
            ?.let(chat::resume)
        val byWakeWord = args?.getBoolean(AssistantService.EXTRA_WAKE_WORD, false) == true
        // The panel needs the microphone; the wake word spotter lets go of it until the panel closes.
        AssistantRuntime.wakeWord.setPaused(true)
        WakeWordService.syncWithSettings(context)
        // Loading the model and the voices saturates every core for over a second. Started here
        // they compete with the panel's own first frames, so they wait until it is on screen:
        // the user is still reading it, and nothing they can do needs the model this soon.
        afterFirstFrame {
            AssistantRuntime.engine.preload(chat::preface)
            voice.preload()
        }
        // Sending the user to another app means getting out of their way; a timer does not.
        AssistantRuntime.tools.activityStarter = { intent, leavesPanel ->
            startAssistantActivity(intent)
            // Tools run on the model's thread; the session is only touched on the main one.
            if (leavesPanel) mainHandler.post { hide() }
        }
        if (byWakeWord || AssistantRuntime.voiceSettings.state.value.listenOnOpen) voice.startListening()
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
        AssistantRuntime.wakeWord.setPaused(false)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        voice.close()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun openApp(destination: Destination) {
        startAssistantActivity(MainActivity.intent(context, destination))
        hide()
    }

    private companion object {
        const val NO_CONVERSATION = -1L
    }

    /** Runs [work] on the main thread once the panel has actually been drawn, and only once. */
    private fun afterFirstFrame(work: () -> Unit) {
        val decor = window.window?.decorView ?: return work()
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var done = false

            override fun onDraw() {
                if (done) return
                done = true
                decor.post {
                    decor.viewTreeObserver.removeOnDrawListener(this)
                    work()
                }
            }
        })
    }

    private fun logFirstFrame() = afterFirstFrame {
        Log.i(AssistantService.TAG, "Panel first frame after ${SystemClock.elapsedRealtime() - showRequestedAt}ms")
    }
}
