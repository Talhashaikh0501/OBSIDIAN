package com.obsidian.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class OfflineSpeechEngine(context: Context) {
    private val modelsRoot = File(context.filesDir, "models")
    private val modelDir = File(modelsRoot, "hi-hinglish-apex")
    private val modelFiles = listOf(
        ModelFile("encoder.int8.onnx", 500_000_000L),
        ModelFile("decoder.int8.onnx", 300_000_000L),
        ModelFile("tokens.txt", 100_000L)
    )

    fun isModelReady(): Boolean = modelFiles.all { model ->
        File(modelDir, model.name).let { it.exists() && it.length() >= model.minimumBytes }
    }

    suspend fun prepare(onProgress: (String) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            modelDir.mkdirs()
            modelFiles.forEachIndexed { index, model ->
                val destination = File(modelDir, model.name)
                if (destination.exists() && destination.length() >= model.minimumBytes) {
                    onProgress("APEX MODEL ${index + 1}/3 READY")
                    return@forEachIndexed
                }

                destination.delete()
                val partial = File(modelDir, "${model.name}.part")
                partial.delete()
                onProgress("DOWNLOADING APEX MODEL ${index + 1}/3")

                val connection = (URL(
                    "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/hi-hinglish-apex/${model.name}"
                ).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 300_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "OBSIDIAN-Android")
                }

                try {
                    connection.connect()
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException(
                            "Apex download failed for ${model.name}: HTTP ${connection.responseCode} ${connection.responseMessage}"
                        )
                    }
                    connection.inputStream.use { input ->
                        partial.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    if (partial.length() < model.minimumBytes) {
                        throw IllegalStateException("Downloaded Apex file is incomplete: ${model.name}")
                    }
                    if (!partial.renameTo(destination)) {
                        throw IllegalStateException("Could not save Apex model file: ${model.name}")
                    }
                } finally {
                    connection.disconnect()
                    partial.delete()
                }
            }

            check(isModelReady()) { "Apex model validation failed. Existing voice models were kept." }

            // Remove older models only after every Apex file has downloaded and passed validation.
            listOf("whisper-tiny", "whisper-small").forEach { legacyName ->
                File(modelsRoot, legacyName).deleteRecursively()
            }
            onProgress("APEX READY • OLD MODELS CLEANED")
        }
    }

    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.IO) {
        require(samples.size >= 8_000) { "Please record at least half a second of speech." }
        check(isModelReady()) { "Apex voice model is not installed. Connect once and prepare it." }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                    language = "hi",
                    task = "transcribe"
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "whisper"
            ),
            decodingMethod = "greedy_search"
        )

        val recognizer = OfflineRecognizer(config = config)
        try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate = 16_000)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }

    private data class ModelFile(val name: String, val minimumBytes: Long)
}
