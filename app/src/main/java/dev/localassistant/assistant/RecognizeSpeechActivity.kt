package dev.localassistant.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.localassistant.AssistantRuntime
import dev.localassistant.R
import dev.localassistant.voice.ListenEvent
import dev.localassistant.voice.Microphone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Answers `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, the intent behind the microphone button in
 * search bars, maps and note apps. Without Google's app nothing on GrapheneOS handles it, so those
 * buttons do nothing; this small dialog gives them the same offline recognizer as the assistant.
 */
class RecognizeSpeechActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stop = MutableStateFlow(false)
    private lateinit var status: TextView
    private lateinit var level: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        setContentView(layout())
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen() else finishWith(null)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun listen() {
        val prefs = AssistantRuntime.voiceSettings.state.value
        val pack = prefs.activeSpeechToText ?: run {
            status.setText(R.string.dictation_no_model)
            return
        }
        val language = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?.substringBefore('-')?.substringBefore('_')
            ?: prefs.language
        AssistantRuntime.speech.preload(pack)
        scope.launch {
            try {
                var samples: FloatArray? = null
                Microphone(this@RecognizeSpeechActivity).listen(stopRequested = stop).collect { event ->
                    when (event) {
                        is ListenEvent.Level -> level.progress = (event.rms * LEVEL_GAIN * 100).toInt().coerceIn(2, 100)
                        ListenEvent.SpeechStarted -> status.setText(R.string.panel_hearing)
                        is ListenEvent.Utterance -> samples = event.samples
                        ListenEvent.NoSpeech -> Unit
                    }
                }
                val captured = samples ?: return@launch finishWith(null)
                status.setText(R.string.panel_transcribing)
                level.isIndeterminate = true
                val text = AssistantRuntime.speech.transcribe(pack, captured, language)
                finishWith(text.takeIf { it.isNotBlank() })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Dictation failed", e)
                finishWith(null)
            } finally {
                AssistantRuntime.speech.scheduleRelease()
            }
        }
    }

    private fun finishWith(text: String?) {
        if (text == null) {
            setResult(RESULT_CANCELED)
        } else {
            setResult(
                RESULT_OK,
                Intent()
                    .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
                    .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f)),
            )
        }
        finish()
    }

    private fun layout(): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            setText(R.string.panel_listening)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val done = Button(this).apply {
            setText(R.string.dictation_done)
            // Ends the utterance now instead of waiting for the trailing silence.
            setOnClickListener { stop.value = true }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(status)
            addView(level)
            addView(done)
        }
    }

    private companion object {
        const val TAG = "RecognizeSpeech"
        const val REQUEST_MIC = 1
        const val LEVEL_GAIN = 6f
    }
}
