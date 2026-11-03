package com.obsidian.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Offline neural voice for OBSIDIAN.
 * The first preparation downloads the quantized Supertonic 3 model (~145 MB).
 * Speech generation and playback then happen locally.
 */
class NeuralVoiceEngine(context: Context) {
    private val modelDirectory = File(context.applicationContext.filesDir, "models/supertonic3-int8")
    private var tts: OfflineTts? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    var isReady: Boolean = false
        private set

    private data class ModelAsset(val fileName: String, val minimumBytes: Long)

    private val assets = listOf(
        ModelAsset("duration_predictor.int8.onnx", 1_000_000L),
        ModelAsset("text_encoder.int8.onnx", 10_000_000L),
        ModelAsset("vector_estimator.int8.onnx", 30_000_000L),
        ModelAsset("vocoder.int8.onnx", 10_000_000L),
        ModelAsset("tts.json", 1_000L),
        ModelAsset("unicode_indexer.bin", 100_000L),
        ModelAsset("voice.bin", 100_000L)
    )

    suspend fun prepare(onProgress: (String) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            if (isReady) return@withContext

            if (!assets.all { asset ->
                    File(modelDirectory, asset.fileName).let {
                        it.exists() && it.length() >= asset.minimumBytes
                    }
                }
            ) {
                if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
                    error("Unable to create neural voice model folder")
                }

                assets.forEachIndexed { index, asset ->
                    val destination = File(modelDirectory, asset.fileName)
                    if (!destination.exists() || destination.length() < asset.minimumBytes) {
                        onProgress("DOWNLOADING VOICE MODEL ${index + 1}/${assets.size}")
                        downloadAsset(asset, destination, onProgress)
                    }
                }
            }

            val supertonic = OfflineTtsSupertonicModelConfig().apply {
                durationPredictor = File(modelDirectory, "duration_predictor.int8.onnx").absolutePath
                textEncoder = File(modelDirectory, "text_encoder.int8.onnx").absolutePath
                vectorEstimator = File(modelDirectory, "vector_estimator.int8.onnx").absolutePath
                vocoder = File(modelDirectory, "vocoder.int8.onnx").absolutePath
                ttsJson = File(modelDirectory, "tts.json").absolutePath
                unicodeIndexer = File(modelDirectory, "unicode_indexer.bin").absolutePath
                voiceStyle = File(modelDirectory, "voice.bin").absolutePath
            }

            val modelConfig = OfflineTtsModelConfig().apply {
                this.supertonic = supertonic
                numThreads = 2
                provider = "cpu"
            }
            val config = OfflineTtsConfig().apply {
                model = modelConfig
                maxNumSentences = 5
            }

            onProgress("LOADING NEURAL VOICE INTO MEMORY")
            val loadedEngine = OfflineTts(config = config)
            if (loadedEngine.numSpeakers() < 10) {
                loadedEngine.release()
                error("Neural voice model has an unexpected speaker count")
            }

            tts = loadedEngine
            isReady = true
        }
    }

    private fun downloadAsset(
        asset: ModelAsset,
        destination: File,
        onProgress: (String) -> Unit
    ) {
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()

        val url = URL(
            "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/${asset.fileName}"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OBSIDIAN-Android")
        }

        try {
            connection.connect()
            val response = connection.responseCode
            if (response !in 200..299) error("MODEL DOWNLOAD HTTP $response")

            val totalBytes = connection.contentLengthLong
            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReported = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastReported >= 2L * 1024L * 1024L) {
                            val progress = if (totalBytes > 0L) {
                                "DOWNLOADING ${asset.fileName} • ${(downloaded * 100 / totalBytes).toInt()}%"
                            } else {
                                "DOWNLOADING ${asset.fileName}"
                            }
                            onProgress(progress)
                            lastReported = downloaded
                        }
                    }
                }
            }

            if (totalBytes > 0L && partial.length() != totalBytes) {
                error("INCOMPLETE MODEL DOWNLOAD: ${asset.fileName}")
            }
            if (partial.length() < asset.minimumBytes) {
                error("INCOMPLETE MODEL FILE: ${asset.fileName}")
            }
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                error("CANNOT SAVE MODEL FILE: ${asset.fileName}")
            }
        } finally {
            connection.disconnect()
            if (partial.exists()) partial.delete()
        }
    }

    suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        val engine = tts ?: return@withContext false
        if (text.isBlank()) return@withContext false

        val generation = GenerationConfig().apply {
            sid = 6
            speed = 1.0f
            numSteps = 8
            extra = mapOf("lang" to "en")
        }
        val audio = engine.generateWithConfig(text.trim(), generation)
        if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
            return@withContext false
        }
        playAudio(audio.samples, audio.sampleRate)
    }

    private suspend fun playAudio(samples: FloatArray, sampleRate: Int): Boolean {
        stop()

        val minimumBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minimumBuffer, samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack = track
        val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        if (written != samples.size) {
            track.release()
            audioTrack = null
            error("AUDIO BUFFER WRITE FAILED")
        }

        return suspendCancellableCoroutine { continuation ->
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(player: AudioTrack) {
                        runCatching { player.stop() }
                        player.release()
                        if (audioTrack === player) audioTrack = null
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onPeriodicNotification(player: AudioTrack) = Unit
                }
            )
            track.setNotificationMarkerPosition(samples.size)
            continuation.invokeOnCancellation {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
                if (audioTrack === track) audioTrack = null
            }
            track.play()
        }
    }

    fun stop() {
        val current = audioTrack ?: return
        audioTrack = null
        runCatching { current.pause() }
        runCatching { current.flush() }
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    fun shutdown() {
        stop()
        tts?.release()
        tts = null
        isReady = false
    }
}
