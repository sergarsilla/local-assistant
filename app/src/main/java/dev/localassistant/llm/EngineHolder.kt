package dev.localassistant.llm

import android.content.Context
import android.util.Log
import dev.localassistant.models.InstalledModel
import dev.localassistant.models.ModelFormat
import dev.localassistant.models.ModelStore
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
    data class Ready(val model: String, val loadMillis: Long) : EngineState
    data class Failed(val model: String, val message: String) : EngineState
}

/**
 * Keeps at most one model in memory: loaded when the assistant opens and released shortly after
 * it closes, so an idle phone does not hold gigabytes of RAM.
 */
class EngineHolder(
    private val context: Context,
    private val store: ModelStore,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var loaded: Pair<String, LlmEngine>? = null
    private var releaseJob: Job? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    fun preload() {
        cancelRelease()
        scope.launch { runCatching { acquire() } }
    }

    /** Returns the engine for the active model, loading or swapping it if needed. */
    suspend fun acquire(): LlmEngine = mutex.withLock {
        cancelRelease()
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
        Log.i(TAG, "Loaded ${model.name} in ${elapsed}ms")
        loaded = model.name to engine
        _state.value = EngineState.Ready(model.name, elapsed)
        engine
    }

    fun scheduleRelease(delayMillis: Long = RELEASE_DELAY_MS) {
        cancelRelease()
        releaseJob = scope.launch {
            delay(delayMillis)
            mutex.withLock { unloadLocked() }
        }
    }

    fun releaseNow() {
        cancelRelease()
        scope.launch { mutex.withLock { unloadLocked() } }
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
            engine.close()
            Log.i(TAG, "Released $name")
        }
        loaded = null
        _state.value = EngineState.Idle
    }

    private fun load(model: InstalledModel): LlmEngine = when (model.format) {
        ModelFormat.GGUF -> LlamaCppEngine.load(model.file, CONTEXT_SIZE, THREADS)
        ModelFormat.LITERT_LM -> LiteRtEngine.load(
            model.file,
            File(context.cacheDir, "litertlm").apply { mkdirs() },
            THREADS,
            CONTEXT_SIZE,
        )
    }

    companion object {
        private const val TAG = "EngineHolder"
        private const val CONTEXT_SIZE = 4096

        // Measured on a Tensor G5: 4 threads decode as fast as 6 and leave cores for the UI.
        private const val THREADS = 4
        private const val RELEASE_DELAY_MS = 30_000L
        private const val MIN_TOKENS_FOR_SPEED = 16
    }
}

class NoModelException : IllegalStateException("No model installed")
