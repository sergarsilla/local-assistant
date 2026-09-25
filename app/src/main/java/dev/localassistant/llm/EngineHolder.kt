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
    data class Ready(val model: String, val loadMillis: Long, val backend: String = "CPU") : EngineState
    data class Failed(val model: String, val message: String) : EngineState
}

/** What the user chose for acceleration, and whether the GPU turned out to be unusable. */
data class AccelerationState(val useGpu: Boolean, val gpuBroken: Boolean)

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

    private val _acceleration = MutableStateFlow(readAcceleration())
    val acceleration: StateFlow<AccelerationState> = _acceleration.asStateFlow()

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
        _state.value = EngineState.Ready(model.name, elapsed, engine.backendName)
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

    fun setUseGpu(enabled: Boolean) {
        // Turning it on again is also how the user retries after a crash was detected.
        prefs.edit().putBoolean(KEY_USE_GPU, enabled).putBoolean(KEY_GPU_BROKEN, false).apply()
        _acceleration.value = AccelerationState(useGpu = enabled, gpuBroken = false)
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
        ModelFormat.GGUF -> LlamaCppEngine.load(model.file, CONTEXT_SIZE, THREADS)
        ModelFormat.LITERT_LM -> loadLiteRt(model)
    }

    /**
     * The GPU decodes Gemma 4 E2B more than twice as fast as the CPU, but GPU drivers can also
     * kill the process outright instead of reporting an error. A marker written before the attempt
     * and removed after it tells the next start that the GPU crashed us, and it is not tried again
     * until the user asks for it.
     */
    private fun loadLiteRt(model: InstalledModel): LlmEngine {
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }
        val acceleration = _acceleration.value
        if (acceleration.useGpu && !acceleration.gpuBroken) {
            prefs.edit().putBoolean(KEY_GPU_ATTEMPT, true).commit()
            try {
                return LiteRtEngine.load(model.file, cache, Backend.GPU(), CONTEXT_SIZE)
            } catch (e: Throwable) {
                Log.w(TAG, "GPU backend unavailable, using the CPU", e)
                markGpuBroken()
            } finally {
                prefs.edit().remove(KEY_GPU_ATTEMPT).commit()
            }
        }
        return LiteRtEngine.load(model.file, cache, Backend.CPU(threadCount = THREADS), CONTEXT_SIZE)
    }

    private fun markGpuBroken() {
        prefs.edit().putBoolean(KEY_GPU_BROKEN, true).apply()
        _acceleration.value = _acceleration.value.copy(gpuBroken = true)
    }

    private fun readAcceleration(): AccelerationState {
        if (prefs.getBoolean(KEY_GPU_ATTEMPT, false)) {
            // The last GPU attempt never finished: the driver took the process down with it.
            Log.w(TAG, "The previous GPU start crashed; staying on the CPU")
            prefs.edit().remove(KEY_GPU_ATTEMPT).putBoolean(KEY_GPU_BROKEN, true).commit()
        }
        return AccelerationState(
            useGpu = prefs.getBoolean(KEY_USE_GPU, true),
            gpuBroken = prefs.getBoolean(KEY_GPU_BROKEN, false),
        )
    }

    companion object {
        private const val TAG = "EngineHolder"
        private const val CONTEXT_SIZE = 4096

        // Measured on a Tensor G5: 4 threads decode as fast as 6 and leave cores for the UI.
        private const val THREADS = 4
        private const val RELEASE_DELAY_MS = 30_000L
        private const val MIN_TOKENS_FOR_SPEED = 16
        private const val KEY_USE_GPU = "use_gpu"
        private const val KEY_GPU_BROKEN = "gpu_broken"
        private const val KEY_GPU_ATTEMPT = "gpu_attempt"
    }
}

class NoModelException : IllegalStateException("No model installed")
