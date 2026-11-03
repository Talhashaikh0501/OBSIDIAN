package com.obsidian.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Uses an installed on-device Android TTS voice only.
 * Network-dependent voices are deliberately excluded.
 */
class OfflineVoiceEngine(context: Context) {
    private val appContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private val completionCallbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var status: String = "INITIALIZING OFFLINE VOICE"
        private set

    fun initialize(onReady: (Boolean, String) -> Unit) {
        if (textToSpeech != null) {
            onReady(isReady, status)
            return
        }

        textToSpeech = TextToSpeech(appContext) { result ->
            val engine = textToSpeech
            if (result != TextToSpeech.SUCCESS || engine == null) {
                status = "ANDROID TEXT-TO-SPEECH INITIALIZATION FAILED"
                onReady(false, status)
                return@TextToSpeech
            }

            val offlineEnglishVoices = engine.voices.orEmpty()
                .filter { voice ->
                    voice.locale.language == "en" &&
                        !voice.isNetworkConnectionRequired
                }

            val selectedVoice = offlineEnglishVoices
                .sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenByDescending { it.locale.country == "IN" }
                )
                .firstOrNull()

            if (selectedVoice == null) {
                status = "NO OFFLINE ENGLISH VOICE INSTALLED"
                onReady(false, status)
                return@TextToSpeech
            }

            engine.voice = selectedVoice
            engine.setPitch(0.78f)
            engine.setSpeechRate(0.88f)
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null) {
                            completionCallbacks.remove(utteranceId)?.invoke(true)
                        }
                    }

                    @Deprecated("Deprecated in Android")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null) {
                            completionCallbacks.remove(utteranceId)?.invoke(false)
                        }
                    }
                }
            )

            isReady = true
            status = "OFFLINE VOICE READY • ${selectedVoice.locale.displayName}"
            onReady(true, status)
        }
    }

    fun speak(text: String, onComplete: (Boolean) -> Unit = {}) {
        val engine = textToSpeech
        if (!isReady || engine == null || text.isBlank()) {
            onComplete(false)
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        completionCallbacks[utteranceId] = onComplete
        val result = engine.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            completionCallbacks.remove(utteranceId)?.invoke(false)
        }
    }

    fun stop() {
        textToSpeech?.stop()
        completionCallbacks.values.forEach { it(false) }
        completionCallbacks.clear()
    }

    fun shutdown() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isReady = false
    }
}
