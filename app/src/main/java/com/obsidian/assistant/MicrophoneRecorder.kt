package com.obsidian.assistant

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

class MicrophoneRecorder {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SECONDS = 60
    }

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private val capturedAudio = ByteArrayOutputStream()

    @Synchronized
    fun start() {
        check(!recording) { "Microphone is already recording." }
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferSize > 0) { "This device cannot initialize audio recording." }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize * 2, 8_192)
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Microphone initialization failed."
        }

        synchronized(capturedAudio) { capturedAudio.reset() }
        audioRecord = recorder
        recording = true
        recorder.startRecording()

        worker = Thread {
            val buffer = ShortArray(2_048)
            var totalSamples = 0
            val maximumSamples = SAMPLE_RATE * MAX_SECONDS
            while (recording && totalSamples < maximumSamples) {
                val count = recorder.read(
                    buffer, 0, minOf(buffer.size, maximumSamples - totalSamples),
                    AudioRecord.READ_BLOCKING
                )
                if (count > 0) {
                    synchronized(capturedAudio) {
                        for (i in 0 until count) {
                            val sample = buffer[i].toInt()
                            capturedAudio.write(sample and 0xFF)
                            capturedAudio.write((sample shr 8) and 0xFF)
                        }
                    }
                    totalSamples += count
                } else if (count < 0) {
                    break
                }
            }
            recording = false
        }.apply {
            name = "ObsidianAudioCapture"
            start()
        }
    }

    @Synchronized
    fun stop(): FloatArray {
        recording = false
        val recorder = audioRecord
        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (_: IllegalStateException) {
            // Recording may already have reached its maximum duration.
        }
        try {
            worker?.join(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recorder?.release()
        audioRecord = null
        worker = null

        val bytes = synchronized(capturedAudio) { capturedAudio.toByteArray() }
        return FloatArray(bytes.size / 2) { index ->
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort().toFloat() / 32768.0f
        }
    }

    fun cancel() {
        try {
            if (audioRecord != null) stop()
        } catch (_: Exception) {
            recording = false
            audioRecord?.release()
            audioRecord = null
            worker = null
        }
    }
}
