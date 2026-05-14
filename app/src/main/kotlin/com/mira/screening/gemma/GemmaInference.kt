package com.mira.screening.gemma

import android.content.Context
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device Gemma 4 inference, wrapped around the Google AI Edge LiteRT-LM SDK.
 *
 * On first launch the model file (~2.4 GB) is downloaded from the official
 * Google-blessed Hugging Face mirror at litert-community/gemma-4-E2B-it-
 * litert-lm and cached at `<filesDir>/gemma4.litertlm`. On subsequent launches
 * the cached file is loaded directly with no network access. Downloads
 * support range-based resume so a dropped connection mid-download can pick
 * up where it left off rather than restart from zero.
 *
 * The full initialization sequence runs in a background coroutine kicked off
 * from MiraApp.onCreate. UI surfaces observe the public `state` StateFlow to
 * render either a download progress bar, an "engine loading" indicator, or
 * the actual generated content once ready.
 *
 * If anything in the init path fails (no network on first launch, disk full,
 * LiteRT-LM native init error), the exception is caught and surfaced as
 * State.Error rather than crashing the app. Consumer code sees `isReady ==
 * false` and renders a friendly error message.
 *
 * Thread-safe: a single Mutex serializes initialization. The underlying
 * Engine handles its own concurrency for createConversation calls.
 */
object GemmaInference {

    private const val INTERNAL_FILENAME = "gemma4.litertlm"
    private const val TEMP_SUFFIX = ".part"
    private const val LOG_TAG = "GemmaInference"

    // Official Google-blessed LiteRT-LM mirror of Gemma 4 E2B-IT on Hugging
    // Face. Anonymous downloads work (verified) with a rate-limit warning
    // header; we do not need a token. Range requests are supported, so we
    // can resume partial downloads.
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm" +
            "/resolve/main/gemma-4-E2B-it.litertlm"

    // Expected size of the model file in bytes (the value reported by the
    // mirror's x-linked-size header). Used to (a) detect a partially-
    // downloaded cache from a previous failed run, (b) compute progress
    // percentages before the Content-Length header has been parsed.
    private const val EXPECTED_SIZE_BYTES = 2_588_147_712L

    private const val DOWNLOAD_BUFFER_BYTES = 256 * 1024
    private const val PROGRESS_EMIT_INTERVAL_BYTES = 4L * 1024L * 1024L
    private const val HTTP_PARTIAL_CONTENT = 206
    private const val NETWORK_CONNECT_TIMEOUT_MS = 30_000
    private const val NETWORK_READ_TIMEOUT_MS = 60_000

    /**
     * The observable state of Gemma's initialization pipeline. UI surfaces
     * collect this flow to render the right thing at the right moment.
     */
    sealed interface State {
        /** Nothing started yet. */
        data object Idle : State

        /**
         * The model file is being downloaded from the network. Used by the UI
         * to render a progress bar and the human-readable percentage.
         */
        data class Downloading(
            val bytesDownloaded: Long,
            val totalBytes: Long
        ) : State {
            val percent: Int
                get() = if (totalBytes > 0) {
                    ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                } else {
                    0
                }
        }

        /** Download complete. The LiteRT-LM engine is now loading the model. */
        data object LoadingEngine : State

        /** Engine loaded. `generate` and `generateStream` will work. */
        data object Ready : State

        /** Something went wrong. The `message` is suitable to show to a user. */
        data class Error(val message: String) : State
    }

    @Volatile
    private var engine: Engine? = null
    private val initMutex = Mutex()
    private var initJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * True if the engine is loaded and ready to serve generation requests.
     */
    val isReady: Boolean
        get() = engine != null

    /**
     * Kick off background initialization. Idempotent: subsequent calls reuse
     * the existing init job rather than reloading the model.
     *
     * Typical call site: MiraApp.onCreate.
     */
    fun startInitialization(context: Context, scope: CoroutineScope) {
        if (initJob != null) return
        initJob = scope.launch(Dispatchers.IO) {
            try {
                initMutex.withLock {
                    if (engine != null) return@withLock
                    val modelFile = ensureModelOnDisk(context.applicationContext)
                    _state.value = State.LoadingEngine
                    val config = EngineConfig(modelPath = modelFile.absolutePath)
                    engine = Engine(config).also { it.initialize() }
                    _state.value = State.Ready
                }
            } catch (t: Throwable) {
                android.util.Log.e(LOG_TAG, "Gemma 4 initialization failed", t)
                _state.value = State.Error(
                    t.message ?: "Mira could not load her AI model."
                )
            }
        }
    }

    /**
     * Suspend until the engine is ready. If initialization hasn't been kicked
     * off yet this is an error in caller setup, not a normal state.
     */
    suspend fun waitUntilReady() {
        val job = initJob
            ?: error("GemmaInference.startInitialization was never called")
        job.join()
    }

    /**
     * One-shot synchronous generation. Suspends until the engine is ready,
     * then runs a single send-message round trip. Returns the full assistant
     * response.
     */
    suspend fun generate(
        prompt: String,
        systemInstruction: String? = null
    ): String = withContext(Dispatchers.Default) {
        waitUntilReady()
        val activeEngine = engine
            ?: throw IllegalStateException(
                "Gemma is not available. The model failed to initialize."
            )
        val convConfig = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(it) }
        )
        activeEngine.createConversation(convConfig).use { conv ->
            conv.sendMessage(prompt).toString()
        }
    }

    /**
     * Streaming generation. Returns a Flow that emits the partial response as
     * the model generates it, then completes. The underlying conversation is
     * closed when the flow completes (whether normally or via cancellation).
     */
    suspend fun generateStream(
        prompt: String,
        systemInstruction: String? = null
    ): Flow<String> {
        waitUntilReady()
        val activeEngine = engine
        if (activeEngine == null) {
            return flow {
                throw IllegalStateException(
                    "Gemma is not available. The model failed to initialize."
                )
            }
        }
        val convConfig = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(it) }
        )
        val conversation = activeEngine.createConversation(convConfig)
        return conversation.sendMessageAsync(Message.user(prompt))
            .map { it.toString() }
            .onCompletion { conversation.close() }
    }

    /**
     * Release the engine. Idempotent.
     */
    fun close() {
        engine?.close()
        engine = null
        initJob = null
        _state.value = State.Idle
    }

    /**
     * Resolve the model file on disk, downloading it from the Hugging Face
     * mirror on first launch. Subsequent launches return immediately once the
     * cached file is verified.
     *
     * Download strategy:
     *   1. If `<filesDir>/gemma4.litertlm` exists and matches expected size,
     *      return it directly.
     *   2. If a partial `<filesDir>/gemma4.litertlm.part` exists from a
     *      previous interrupted download, resume from that offset using an
     *      HTTP Range request.
     *   3. Otherwise start a fresh download.
     *   4. Upon successful completion, atomically rename the `.part` file to
     *      the final filename so a half-written file is never visible as the
     *      "real" model.
     */
    private suspend fun ensureModelOnDisk(context: Context): File =
        withContext(Dispatchers.IO) {
            val finalFile = File(context.filesDir, INTERNAL_FILENAME)
            if (finalFile.exists() && finalFile.length() == EXPECTED_SIZE_BYTES) {
                return@withContext finalFile
            }
            // Clear a stale file that doesn't match the expected size (e.g.
            // from a previous run that crashed mid-rename).
            if (finalFile.exists() && finalFile.length() != EXPECTED_SIZE_BYTES) {
                finalFile.delete()
            }
            val partFile = File(context.filesDir, "$INTERNAL_FILENAME$TEMP_SUFFIX")
            downloadModel(partFile)
            if (!partFile.renameTo(finalFile)) {
                throw IllegalStateException(
                    "Could not finalize model file at ${finalFile.absolutePath}"
                )
            }
            finalFile
        }

    /**
     * Download the model file to `partFile`, resuming from its existing size
     * if non-empty. Emits State.Downloading updates roughly every
     * PROGRESS_EMIT_INTERVAL_BYTES so the UI gets smooth progress without
     * being flooded with state changes.
     */
    private fun downloadModel(partFile: File) {
        val resumeFrom = if (partFile.exists()) partFile.length() else 0L
        _state.value = State.Downloading(resumeFrom, EXPECTED_SIZE_BYTES)
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
            readTimeout = NETWORK_READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mira-Hackathon/0.1.0")
            if (resumeFrom > 0L) {
                setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            val responseCode = connection.responseCode
            val isResume = resumeFrom > 0L && responseCode == HTTP_PARTIAL_CONTENT
            if (responseCode !in 200..299) {
                throw IllegalStateException(
                    "Download failed with HTTP $responseCode."
                )
            }
            // If we asked to resume but the server returned 200 instead of
            // 206, it ignored our Range header and is sending the whole file
            // from scratch; truncate the part file to match.
            val totalSize = when {
                isResume -> resumeFrom + connection.contentLengthLong
                connection.contentLengthLong > 0L -> connection.contentLengthLong
                else -> EXPECTED_SIZE_BYTES
            }
            val output = if (isResume) {
                FileOutputStream(partFile, true)
            } else {
                FileOutputStream(partFile, false)
            }
            output.use { sink ->
                connection.inputStream.use { source ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var written = if (isResume) resumeFrom else 0L
                    var lastEmitted = written
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        if (written - lastEmitted >= PROGRESS_EMIT_INTERVAL_BYTES) {
                            _state.value = State.Downloading(written, totalSize)
                            lastEmitted = written
                        }
                    }
                    _state.value = State.Downloading(written, totalSize)
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
