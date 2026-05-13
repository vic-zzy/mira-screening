package com.mira.screening.gemma

import android.content.Context
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device Gemma 4 inference, wrapped around the Google AI Edge LiteRT-LM SDK.
 *
 * Loads the bundled `gemma4.litertlm` asset once at app launch, extracts it to
 * the app's internal storage (LiteRT-LM expects a real filesystem path), then
 * exposes synchronous and streaming generation APIs to the rest of the app.
 *
 * Initialization is ~10 seconds on a mid-range Android phone, so it runs in a
 * background coroutine kicked off from MiraApp.onCreate. UI surfaces that need
 * Gemma either wait via `waitUntilReady()` or check `isReady` and show a
 * loading state.
 *
 * Thread-safe: a single Mutex serializes initialization, and the underlying
 * Engine handles its own concurrency for createConversation calls.
 */
object GemmaInference {

    private const val ASSET_NAME = "gemma4.litertlm"
    private const val INTERNAL_FILENAME = "gemma4.litertlm"
    private const val COPY_BUFFER_BYTES = 4 * 1024 * 1024

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
            initMutex.withLock {
                if (engine != null) return@withLock
                val modelFile = ensureModelOnDisk(context.applicationContext)
                val config = EngineConfig(modelPath = modelFile.absolutePath)
                engine = Engine(config).also { it.initialize() }
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
            ?: error("Gemma engine missing after waitUntilReady; this is a bug")
        val convConfig = systemInstruction?.let {
            ConversationConfig(systemInstruction = it)
        }
        val conversation = if (convConfig != null) {
            activeEngine.createConversation(convConfig)
        } else {
            activeEngine.createConversation()
        }
        conversation.use { conv ->
            conv.sendMessage(prompt)
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
            ?: error("Gemma engine missing after waitUntilReady; this is a bug")
        val convConfig = systemInstruction?.let {
            ConversationConfig(systemInstruction = it)
        }
        val conversation = if (convConfig != null) {
            activeEngine.createConversation(convConfig)
        } else {
            activeEngine.createConversation()
        }
        return flow {
            conversation.sendMessageAsync(prompt).collect { token ->
                emit(token)
            }
        }.onCompletion {
            conversation.close()
        }
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
     * Extract the .litertlm model from the APK assets to the app's internal
     * filesDir, if not already present. Returns the on-disk path for use as
     * EngineConfig.modelPath.
     *
     * Runs on Dispatchers.IO. The first-launch copy of a ~2.4 GB file can
     * take 30 to 60 seconds on a slow device, which is why startup is
     * background-async.
     */
    private suspend fun ensureModelOnDisk(context: Context): File =
        withContext(Dispatchers.IO) {
            val target = File(context.filesDir, INTERNAL_FILENAME)
            if (target.exists() && target.length() > 0) {
                return@withContext target
            }
            context.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            }
            target
        }
}
