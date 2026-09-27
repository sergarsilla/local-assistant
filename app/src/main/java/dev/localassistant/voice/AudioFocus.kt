package dev.localassistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Asks the phone to quieten whatever else is playing while the assistant listens or speaks, and
 * to let it back up afterwards. Without this the reply is simply mixed on top of the music,
 * which is unusable in a car: the reply is buried, and the music is loud enough in the
 * microphone to spoil what the user says.
 *
 * Ducking rather than pausing is deliberate. A reply lasts a few seconds, so music should dip
 * and recover rather than stop; turn-by-turn navigation behaves the same way.
 *
 * Counted, because listening and speaking overlap at the end of a turn and each releases its
 * own hold.
 */
class AudioFocus(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .build()
    private var holds = 0

    @Synchronized
    fun hold() {
        if (holds++ == 0) manager?.requestAudioFocus(request)
    }

    @Synchronized
    fun release() {
        if (holds > 0 && --holds == 0) manager?.abandonAudioFocusRequest(request)
    }

    /** Drops every hold at once, for when a session ends while something is still speaking. */
    @Synchronized
    fun releaseAll() {
        if (holds > 0) {
            holds = 0
            manager?.abandonAudioFocusRequest(request)
        }
    }
}
