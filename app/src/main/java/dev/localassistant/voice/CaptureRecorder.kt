package dev.localassistant.voice

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes captured utterances as 16 kHz WAV files so they can be listened to afterwards. */
class CaptureRecorder(private val directory: File) {

    fun save(samples: FloatArray) {
        runCatching {
            directory.mkdirs()
            val file = File(directory, "capture-${System.currentTimeMillis()}.wav")
            file.outputStream().use { out ->
                val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
                out.write(header(samples.size * 2))
                out.write(pcm.array())
            }
            Log.i(TAG, "Saved ${file.name} (${samples.size / 16}ms)")
        }
    }

    private fun header(dataBytes: Int): ByteArray {
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buffer.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        return buffer.array()
    }

    private companion object {
        const val TAG = "CaptureRecorder"
        const val SAMPLE_RATE = 16_000
    }
}
