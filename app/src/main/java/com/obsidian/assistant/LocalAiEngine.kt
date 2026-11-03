package com.obsidian.assistant

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fully local Qwen3 brain. The GGUF is downloaded once; inference runs on CPU.
 * Keep the Android TTS engine separate so voice remains available if the LLM fails.
 */
class LocalAiEngine(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, "models/qwen3")
    private val modelFile = File(modelDirectory, "Qwen3-1.7B-Q4_K_M.gguf")
    private var model: LlamaModel? = null

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastInferenceDurationMs: Long = 0L
        private set

    @Volatile
    var lastTokensPerSecond: Float = 0f
        private set

    fun isModelDownloaded(): Boolean =
        modelFile.exists() && modelFile.length() == MODEL_BYTES

    suspend fun prepare(onProgress: (String) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            if (isReady && model?.isLoaded == true) return@withContext

            val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            if (memoryInfo.availMem < 1_800_000_000L) {
                error("CLOSE OTHER APPS • QWEN3 NEEDS ABOUT 1.8 GB AVAILABLE RAM")
            }

            if (!isModelDownloaded()) {
                val parent = modelDirectory
                if (!parent.exists() && !parent.mkdirs()) {
                    error("CANNOT CREATE LOCAL AI MODEL FOLDER")
                }

                val usableBytes = StatFs(parent.absolutePath).availableBytes
                if (usableBytes < MODEL_BYTES + 250_000_000L) {
                    error("LOCAL AI NEEDS AT LEAST 1.6 GB FREE STORAGE")
                }

                val partial = File(parent, modelFile.name + ".part")
                partial.delete()
                onProgress("DOWNLOADING QWEN3 • 1.28 GB")

                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "OBSIDIAN-Android")
                }

                try {
                    connection.connect()
                    if (connection.responseCode !in 200..299) {
                        error("QWEN3 DOWNLOAD HTTP ${connection.responseCode}")
                    }

                    val totalBytes = connection.contentLengthLong
                    connection.inputStream.buffered().use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var downloaded = 0L
                            var lastReported = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (downloaded - lastReported >= 16L * 1024L * 1024L) {
                                    val percent = if (totalBytes > 0L) {
                                        (downloaded * 100 / totalBytes).toInt()
                                    } else -1
                                    onProgress(
                                        if (percent >= 0) "DOWNLOADING QWEN3 • $percent%"
                                        else "DOWNLOADING QWEN3 • ${downloaded / 1_000_000} MB"
                                    )
                                    lastReported = downloaded
                                }
                            }
                        }
                    }

                    if (totalBytes > 0L && partial.length() != totalBytes) {
                        error("QWEN3 DOWNLOAD INCOMPLETE • RETRY INSTALL")
                    }
                    if (partial.length() != MODEL_BYTES) {
                        error("QWEN3 MODEL FILE IS INCOMPLETE")
                    }
                    if (modelFile.exists()) modelFile.delete()
                    if (!partial.renameTo(modelFile)) {
                        error("COULD NOT SAVE QWEN3 MODEL")
                    }
                } finally {
                    connection.disconnect()
                    partial.delete()
                }
            }

            onProgress("LOADING QWEN3 INTO MEMORY")
            val loaded = Llama.loadModel(
                modelPath = modelFile.absolutePath,
                config = LlamaConfig(
                    contextSize = 2048,
                    threads = 4,
                    gpuLayers = 0,
                    temperature = 0.65f,
                    topP = 0.85f,
                    topK = 30
                )
            )
            model = loaded
            isReady = true
            onProgress("QWEN3 LOCAL BRAIN ONLINE")
        }
    }

    suspend fun think(userText: String, memoryContext: String = ""): String {
        val activeModel = model ?: error("LOCAL AI MODEL IS NOT LOADED")
        require(userText.isNotBlank()) { "I DID NOT RECEIVE A COMMAND" }

        val inferenceStartedAt = android.os.SystemClock.elapsedRealtime()
        val result = Llama.complete(
            model = activeModel,
            prompt = buildString {
                if (memoryContext.isNotBlank()) {
                    appendLine("Relevant information from previous conversations:")
                    appendLine(memoryContext.takeLast(3500))
                    appendLine()
                }
                appendLine("Current user message:")
                appendLine(userText.trim())
                append("/no_think")
            },
            systemPrompt = SYSTEM_PROMPT,
            maxTokens = 120
        )
        lastInferenceDurationMs = android.os.SystemClock.elapsedRealtime() - inferenceStartedAt
        lastTokensPerSecond = result.tokensPerSecond

        val answer = result.text
            .substringAfter("</think>", result.text)
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("<\\|[^|]+\\|>"), "")
            .trim()

        return answer.ifBlank { "I processed your command, but could not form a response." }
    }

    fun shutdown() {
        model?.let { runCatching { Llama.releaseModel(it) } }
        model = null
        isReady = false
    }

    private companion object {
        const val MODEL_BYTES = 1_282_439_264L
        const val MODEL_URL =
            "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true"

        const val SYSTEM_PROMPT = """
You are OBSIDIAN, a private on-device AI assistant.
Speak with calm authority, precision, and confidence. Be concise by default.
Understand English, Hindi, and Romanized Hinglish. Reply in the language the user uses.
Answer directly. Do not reveal hidden reasoning or write <think> tags.
Do not claim to perform phone actions unless an available tool actually performed them.
If uncertain, say so clearly. Keep spoken responses natural and easy to understand.
"""
    }
}
