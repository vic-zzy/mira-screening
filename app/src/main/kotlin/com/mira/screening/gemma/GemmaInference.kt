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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device Gemma 4 inference, wrapped around the Google AI Edge LiteRT-LM SDK.
 *
 * Loads the `gemma4.litertlm` model from the app's internal files directory
 * and exposes synchronous and streaming generation APIs to the rest of the
 * app. The model file is NOT bundled inside the APK because its size
 * (~2.6 GB) exceeds the 2 GB Java array limit that the Android asset
 * packaging pipeline hits during compressDebugAssets. Instead, the model is
 * distributed separately (see README) and must be placed at the path
 * `context.filesDir/gemma4.litertlm` before Gemma can initialize. In a
 * production build the app would download it on first launch.
 *
 * Initialization is ~10 seconds on a mid-range Android phone, so it runs in
 * a background coroutine kicked off from MiraApp.onCreate. UI surfaces that
 * need Gemma either wait via `waitUntilReady()` or check `isReady` and show
 * a loading state. If the model file is missing or initialization fails, the
 * exception is swallowed at the engine level (logged but not propagated) and
 * consumer code sees `isReady == false`; the consumer surfaces a friendly
 * error to the user instead of crashing the app.
 *
 * Thread-safe: a single Mutex serializes initialization, and the underlying
 * Engine handles its own concurrency for createConversation calls.
 */
object GemmaInference {

    private const val INTERNAL_FILENAME = "gemma4.litertlm"
    private const val LOG_TAG = "GemmaInference"

    @Volatile
    private var engine: Engine? = null
    private val initMutex = Mutex()
    private var initJob: Job? = null

    /**
     * True if the engine is loaded and ready to serve generation requests.
     */
    val isReady: Boolean
        get() = engine != null

    /**
     * Kick off background initialization. Idempotent: subsequent calls return
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
                    val modelFile = locateModelFile(context.applicationContext)
                    val config = EngineConfig(modelPath = modelFile.absolutePath)
                    engine = Engine(config).also { it.initialize() }
                }
            } catch (t: Throwable) {
                // Swallow at the engine level so the app does not crash on
                // missing model file or LiteRT-LM init failure. Consumer UI
                // surfaces will show a friendly error instead.
                android.util.Log.e(LOG_TAG, "Gemma 4 initialization failed", t)
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
     *
     * @param prompt the user-side message
     * @param systemInstruction optional persona/system prompt; per-call so
     *   different reasoning surfaces can use the same engine
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
            // sendMessage(text) returns a Message; Message.toString() yields
            // the text content of the model's reply.
            conv.sendMessage(prompt).toString()
        }
    }

    /**
     * Streaming generation. Returns a Flow that emits the partial response as
     * the model generates it, then completes. The underlying conversation is
     * closed when the flow completes (whether normally or via cancellation).
     *
     * Used by the CHW assistant screen for token-by-token UI updates.
     */
    suspend fun generateStream(
        prompt: String,
        systemInstruction: String? = null
    ): Flow<String> {
        waitUntilReady()
        val activeEngine = engine
        // Surface engine-missing as a flow-side error rather than a
        // synchronous throw, so the consumer's .catch operator picks it up
        // instead of crashing the launching coroutine. This guards against
        // the case where startInitialization failed (missing model file,
        // OOM, native init error) and engine remained null.
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
        // The Flow overload of sendMessageAsync only takes a Message, not a
        // raw String; wrap the prompt in a user-role Message. The emitted
        // Message tokens are converted to text via toString() in map().
        return conversation.sendMessageAsync(Message.user(prompt))
            .map { it.toString() }
            .onCompletion { conversation.close() }
    }

    /**
     * Release the engine. Call from MiraApp.onTerminate or when the app
     * decides Gemma is no longer needed. Idempotent.
     */
    fun close() {
        engine?.close()
        engine = null
        initJob = null
    }

    /**
     * Resolve the model file's location on disk. The model lives in the app's
     * internal files directory at `<filesDir>/gemma4.litertlm`. If absent,
     * throws with a clear message naming the expected path so testers can
     * place the file via Android Studio Device File Explorer, adb push, or
     * the future first-launch download flow.
     */
    private suspend fun locateModelFile(context: Context): File =
        withContext(Dispatchers.IO) {
            val target = File(context.filesDir, INTERNAL_FILENAME)
            check(target.exists() && target.length() > 0) {
                "Gemma 4 model not found at ${target.absolutePath}. The " +
                    "model file (gemma4.litertlm, ~2.6 GB) is distributed " +
                    "separately. Place it at this path via Device File " +
                    "Explorer or adb push before launching."
            }
            target
        }
}
