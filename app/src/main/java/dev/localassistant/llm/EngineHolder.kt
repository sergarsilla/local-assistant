package dev.localassistant.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import dev.localassistant.models.InstalledModel
import dev.localassistant.models.ModelFormat
import dev.localassistant.models.ModelStore
import dev.localassistant.tools.AssistantTool
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface EngineState {
    data object NoModel : EngineState
    data object Idle : EngineState
    data class Loading(val model: String) : EngineState
    data class Ready(
        val model: String,
        val loadMillis: Long,
        val backend: String = "CPU",
        val readsImages: Boolean = false,
    ) : EngineState
    data class Failed(val model: String, val message: String) : EngineState
}

/** Where LiteRT-LM models run. GGUF models always use the CPU. */
enum class Accelerator {
    CPU,

    /** The GPU, falling back to the CPU. */
    GPU,

    /** Experimental: the Tensor NPU, which needs models compiled for it; falls back like [GPU]. */
    NPU,
}

/** How models are loaded, and which accelerators turned out to be unusable on this phone. */
data class EnginePreferences(
    val accelerator: Accelerator = Accelerator.CPU,
    val gpuBroken: Boolean = false,
    val npuBroken: Boolean = false,
    /** Loads the vision encoder too, so screenshots can be understood; costs memory. */
    val understandImages: Boolean = false,
)

/**
 * Keeps at most one model in memory: loaded when the assistant opens and released shortly after
 * it closes, so an idle phone does not hold gigabytes of RAM.
 *
 * Every use of the engine goes through [use], which holds it exclusively: a generation can never
 * run while the model is being swapped or freed, and two callers (the panel and the system
 * dictation, say) never drive the same native context at once.
 */
class EngineHolder(
    private val context: Context,
    private val store: ModelStore,
    private val scope: CoroutineScope,
) {
    /** Held for the whole of a generation; always taken before [stateMutex]. */
    private val useMutex = Mutex()

    /** Guards [loaded] and [state]. */
    private val stateMutex = Mutex()
    private var loaded: Pair<String, LlmEngine>? = null

    @Volatile
    private var releaseJob: Job? = null

    private val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _preferences = MutableStateFlow(readPreferences())
    val preferences: StateFlow<EnginePreferences> = _preferences.asStateFlow()

    /**
     * Loads the model and processes the fixed start of the prompt while the user is still
     * speaking or typing. [preface] supplies that start: the system message and the tools.
     */
    fun preload(preface: (() -> Pair<List<ChatMessage>, List<AssistantTool>>)? = null) {
        cancelRelease()
        scope.launch {
            runCatching {
                use { engine ->
                    val (messages, tools) = preface?.invoke() ?: return@use
                    engine.prepare(messages, if (engine.supportsTools) tools else emptyList())
                }
            }.onFailure { if (it !is NoModelException) Log.w(TAG, "Preload failed", it) }
        }
    }

    /** Runs [block] with the active model loaded, holding it exclusively until the block returns. */
    suspend fun <T> use(block: suspend (LlmEngine) -> T): T = useMutex.withLock {
        cancelRelease()
        block(acquireLocked())
    }

    private suspend fun acquireLocked(): LlmEngine = stateMutex.withLock {
        val model = store.active ?: run {
            _state.value = EngineState.NoModel
            throw NoModelException()
        }
        loaded?.let { (name, engine) -> if (name == model.name) return engine }
        unloadLocked()
        _state.value = EngineState.Loading(model.name)
        val started = System.currentTimeMillis()
        val engine = try {
            withContext(Dispatchers.IO) { load(model) }
        } catch (e: Throwable) {
            _state.value = EngineState.Failed(model.name, e.message ?: e.javaClass.simpleName)
            throw e
        }
        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "Loaded ${model.name} on ${engine.backendName} in ${elapsed}ms")
        loaded = model.name to engine
        _state.value = EngineState.Ready(model.name, elapsed, engine.backendName, engine.supportsImages)
        engine
    }

    fun scheduleRelease(delayMillis: Long = RELEASE_DELAY_MS) {
        cancelRelease()
        releaseJob = scope.launch {
            delay(delayMillis)
            // Waits for a generation still running to finish instead of freeing it underneath.
            useMutex.withLock { stateMutex.withLock { unloadLocked() } }
        }
    }

    fun releaseNow() {
        cancelRelease()
        scope.launch { useMutex.withLock { stateMutex.withLock { unloadLocked() } } }
    }

    /** Choosing an accelerator again is also how the user retries one that crashed before. */
    /**
     * Android caps how much memory one app may hold, and a model whose weights plus conversation
     * go over it is not killed but throttled: the kernel takes its pages away and fetches them
     * back as it works. Measured on a Tensor G5, a 4B model holding 4096 tokens answered in
     * minutes; the same model holding 2048 answered at 7.6 tok/s. Which models this hits is not
     * something the app can tell from their size, so it is offered as a choice.
     */
    fun setContextSize(name: String, tokens: Int) {
        store.setContext(name, tokens)
        if (loaded?.first == name) releaseNow()
    }

    fun setAccelerator(accelerator: Accelerator) {
        prefs.edit()
            .putString(KEY_ACCELERATOR, accelerator.name)
            .putBoolean(KEY_GPU_BROKEN, false)
            .putBoolean(KEY_NPU_BROKEN, false)
            .apply()
        _preferences.value = _preferences.value.copy(accelerator = accelerator, gpuBroken = false, npuBroken = false)
        releaseNow()
    }

    fun setUnderstandImages(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IMAGES, enabled).apply()
        _preferences.value = _preferences.value.copy(understandImages = enabled)
        releaseNow()
    }

    fun recordSpeed(stats: GenerationStats) {
        val name = (state.value as? EngineState.Ready)?.model ?: return
        if (stats.generatedTokens >= MIN_TOKENS_FOR_SPEED) store.recordSpeed(name, stats.tokensPerSecond)
    }

    private fun cancelRelease() {
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun unloadLocked() {
        loaded?.let { (name, engine) ->
            runCatching { engine.close() }.onFailure { Log.w(TAG, "Closing $name failed", it) }
            Log.i(TAG, "Released $name")
        }
        loaded = null
        _state.value = EngineState.Idle
    }

    private fun load(model: InstalledModel): LlmEngine = when (model.format) {
        ModelFormat.GGUF -> LlamaCppEngine.load(model.file, model.contextTokens ?: CONTEXT_SIZE, THREADS)
        ModelFormat.LITERT_LM -> loadLiteRt(model)
    }

    /**
     * The CPU is the default because it is what answers fastest. Measured on a Tensor G5 with
     * Gemma 4 E2B: the CPU decodes 18.3 tok/s against the GPU's 6.7, and loads in 1s against 9s
     * warm and 59s cold. The GPU only wins on prefill (550 against 185 tok/s), which pays off
     * with very long prompts, so it stays an explicit choice.
     *
     * GPU and NPU drivers can also kill the process outright instead of reporting an error. A
     * marker naming the backend is written before each attempt and removed after it; if it is
     * still there on the next start, that backend crashed us and is not tried again until the
     * user picks it anew.
     */
    private fun loadLiteRt(model: InstalledModel): LlmEngine {
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }
        val preferences = _preferences.value
        val candidates = buildList {
            if (preferences.accelerator == Accelerator.NPU && !preferences.npuBroken) {
                add(Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir))
            }
            if (preferences.accelerator != Accelerator.CPU && !preferences.gpuBroken) add(Backend.GPU())
        }
        for (backend in candidates) {
            prefs.edit().putString(KEY_ATTEMPT, backend.name).commit()
            try {
                return loadLiteRtOn(model, cache, backend, preferences.understandImages)
            } catch (e: Throwable) {
                Log.w(TAG, "${backend.name} backend unavailable", e)
                markBroken(backend.name)
            } finally {
                prefs.edit().remove(KEY_ATTEMPT).commit()
            }
        }
        return loadLiteRtOn(model, cache, Backend.CPU(threadCount = THREADS), preferences.understandImages)
    }

    /** Models without a vision encoder refuse to load with one, so that is retried without. */
    private fun loadLiteRtOn(model: InstalledModel, cache: File, backend: Backend, images: Boolean): LlmEngine {
        if (images) {
            try {
                return LiteRtEngine.load(model.file, cache, backend, CONTEXT_SIZE, visionBackend = backend)
            } catch (e: Throwable) {
                Log.w(TAG, "${model.name} cannot read images on ${backend.name}; loading it for text only", e)
            }
        }
        return LiteRtEngine.load(model.file, cache, backend, CONTEXT_SIZE, visionBackend = null)
    }

    private fun markBroken(backend: String) {
        when (backend) {
            BACKEND_NPU -> {
                prefs.edit().putBoolean(KEY_NPU_BROKEN, true).apply()
                _preferences.value = _preferences.value.copy(npuBroken = true)
            }
            else -> {
                prefs.edit().putBoolean(KEY_GPU_BROKEN, true).apply()
                _preferences.value = _preferences.value.copy(gpuBroken = true)
            }
        }
    }

    private fun readPreferences(): EnginePreferences {
        prefs.getString(KEY_ATTEMPT, null)?.let { crashed ->
            // The last attempt on this backend never finished: the driver took the process down.
            Log.w(TAG, "The previous start on $crashed crashed; it will not be tried again")
            val key = if (crashed == BACKEND_NPU) KEY_NPU_BROKEN else KEY_GPU_BROKEN
            prefs.edit().remove(KEY_ATTEMPT).putBoolean(key, true).commit()
        }
        return EnginePreferences(
            accelerator = prefs.getString(KEY_ACCELERATOR, null)
                ?.let { name -> Accelerator.entries.firstOrNull { it.name == name } }
                ?: Accelerator.CPU,
            gpuBroken = prefs.getBoolean(KEY_GPU_BROKEN, false),
            npuBroken = prefs.getBoolean(KEY_NPU_BROKEN, false),
            understandImages = prefs.getBoolean(KEY_IMAGES, false),
        )
    }

    companion object {
        private const val TAG = "EngineHolder"
        const val CONTEXT_SIZE = 4096

        /** What a model can be given to hold, largest first. */
        val CONTEXT_CHOICES = listOf(4096, 2048, 1024)

        // Measured on a Tensor G5: 4 threads decode as fast as 6 and leave cores for the UI.
        private const val THREADS = 4
        private const val RELEASE_DELAY_MS = 30_000L
        private const val MIN_TOKENS_FOR_SPEED = 16
        private const val KEY_ACCELERATOR = "accelerator"
        private const val KEY_GPU_BROKEN = "gpu_broken"
        private const val KEY_NPU_BROKEN = "npu_broken"
        private const val KEY_ATTEMPT = "backend_attempt"
        private const val KEY_IMAGES = "understand_images"
        private const val BACKEND_NPU = "NPU"
    }
}

class NoModelException : IllegalStateException("No model installed")
