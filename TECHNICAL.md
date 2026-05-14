# Mira technical architecture

How the system is put together, what each piece does, and why we built it this way. Companion document to the `README`.

## System overview

Mira is two on-device models stitched together by a Compose UI and a small persistence layer:

```
camera frames                    text+context                tokens
     │                                │                         │
     ▼                                ▼                         ▼
┌──────────────┐   composite    ┌───────────────┐    prompt   ┌───────────────────┐
│ CameraX +    │───────────────▶│ TFLite ViT    │────────────▶│ Gemma 4 E2B-IT    │
│ quality gate │   preprocessed │ classifier    │   result+   │ via LiteRT-LM     │
│ + burst      │   bitmap       │ (vision)      │   heatmap   │ (language/reason) │
└──────────────┘                └───────────────┘             └───────────────────┘
                                       │                              │
                                       ▼                              ▼
                                  label + heatmap            narration / advice /
                                  + confidence               function calls / Q&A
                                                                      │
                                                                      ▼
                                                              Compose UI + TTS
```

Vision is handled by a domain-specialist model (a fine-tuned ViT exported to TensorFlow Lite). Language, reasoning, multilingual generation, and structured orchestration are handled by Gemma 4 running through Google's LiteRT-LM SDK. The two communicate via a small text interface: the classifier hands Gemma a label, a confidence, a sentence describing where the saliency heatmap concentrated, and any prior patient context; Gemma hands back natural-language explanations, decision suggestions, and JSON-encoded function calls.

This split is deliberate. Gemma 4 wasn't trained on cervical pathology imagery, so asking it "is this cervix VIA-positive?" would be unreliable. The TFLite ViT is fine-tuned on the cervix-specific corpus and does the actual visual classification. Gemma takes over once the image has been reduced to a structured result and runs the parts language models are good at.

## Module layout

```
app/src/main/kotlin/com/mira/screening/
├── camera/CameraController.kt          burst capture + AWB lock + Camera2 interop
├── preprocessing/                      blur detection, white balance, multi-frame stack
├── inference/                          TFLite classifier and result types
├── gemma/
│   ├── GemmaInference.kt               LiteRT-LM lifecycle + State machine
│   ├── PromptTemplates.kt              one system instruction + user prompt per surface
│   └── FunctionRegistry.kt             JSON function-calling schema and dispatcher
├── data/                               ScreeningRecord + JSON-backed repository
├── ui/screens/                         each user-visible flow (capture, processing, etc.)
└── ui/components/                      MiraExplainsCard, MarkdownText, TypingDotsIndicator
```

## The capture pipeline

`CameraScreen` drives the user through framing the cervix and tapping capture. On tap, `CameraController.captureBurst(frameCount = 3)` fires three photographs in quick succession.

Key decisions in the camera config:

- **`CAPTURE_MODE_MINIMIZE_LATENCY`** rather than `MAXIMIZE_QUALITY`. Quality mode applies per-frame HDR-style post-processing and sharpening which is wasted work for our pipeline, because we average three frames downstream. Multi-frame averaging recovers roughly sqrt(N) in SNR, which matches what the quality mode buys per single frame. Net: the composite is the same final quality, but the burst returns 2 to 3 times faster on real hardware.
- **AWB locked** via Camera2 interop (`CONTROL_AWB_LOCK = true`). Color stability across frames matters for downstream acetowhitening detection. Without the lock the camera ISP might shift white balance between burst frames and corrupt the averaged composite.
- **Noise reduction and edge mode set to HIGH_QUALITY** at the request-options level. These don't affect throughput meaningfully but preserve fine acetowhite border detail in each frame before averaging.

After the burst:

1. `MultiFrameStack.averageAligned` drops any frame whose mean-squared-error against the reference exceeds a motion threshold, then pixel-averages the surviving frames. So a single move during the burst doesn't blur the composite; it gets dropped.
2. `LaplacianVariance.analyze` rejects the composite if it's still too motion-blurred.
3. `CervixValidityGate` runs a heuristic check ("does this look like the inside of a speculum view at all?") to refuse obviously non-cervical inputs and route them to a re-capture screen.
4. `Preprocessor` does Gray World white-balance correction and specular-reflection masking.

Only after all four gates pass does the composite move to inference.

## The vision classifier

The current baseline is a **DINOv2-S/14** fine-tuned on the public Intel/MobileODT corpus, using transformation-zone type as a documented proxy label for VIA-positive/negative. Exported to TensorFlow Lite, ships as `via_model.tflite` (~84 MB) inside the APK.

At runtime:

- Loaded by `TfLiteClassifier` with XNNPack delegate enabled. XNNPack is replacing ~85% of the operators with optimized CPU kernels (visible in Logcat as `Replacing 1924 out of 2235 node(s) with delegate (TfLiteXNNPackDelegate)`).
- Produces a softmax probability and a 16x16 attention heatmap.
- `ViaModel` translates the raw output into a `ViaResult` (classification + confidence + heatmap) and routes anything below the user-configured confidence threshold to `INCONCLUSIVE`. We deliberately do not deliver a low-confidence positive/negative.

Honest accounting on this model lives in the `README` ("Honest framing on the classifier" section). It's a pipeline-validation baseline reporting AUC 0.71 on a held-out split of the same dataset, not a clinical claim. The architecture is what we're submitting; the classifier is a slot that will be replaced with a properly out-of-distribution clinical-cohort fine-tune.

## Gemma 4 integration

### The runtime

We use the Google AI Edge **LiteRT-LM** SDK (`com.google.ai.edge.litertlm:litertlm-android:0.11.0`). The model file is **gemma-4-E2B-IT** in `.litertlm` format (~2.4 GB, ~2.3B active parameters).

GPU acceleration is opt-in via two non-required native libraries declared in `AndroidManifest.xml`:

```xml
<uses-native-library android:name="libvndksupport.so" android:required="false" />
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

LiteRT-LM picks up `libOpenCL.so` automatically where present and falls back to CPU inference otherwise. On modern Android phones (including most sub-$100 Tecno/Infinix devices) this means roughly 3 to 5x faster inference than CPU-only. On emulator and older hardware it means CPU.

### Model delivery

The 2.4 GB model is **not** bundled in the APK. Android's asset packaging has a hard 2 GB single-array limit, and shipping a 2.4 GB APK is awful UX anyway. Instead `GemmaInference.ensureModelOnDisk` fetches the model from the official LiteRT-LM Hugging Face mirror (`litert-community/gemma-4-E2B-it-litert-lm`) on first app launch, caches it at `<filesDir>/gemma4.litertlm`, and reuses it on every subsequent launch with no network access.

Download details:

- **Anonymous, no token required.** Verified working against the public mirror.
- **HTTP Range resume.** Downloads write to a `.part` file and atomically rename on completion. A dropped connection mid-download picks up where it left off rather than restarting from zero.
- **Progress emitted every 4 MB** as `State.Downloading(bytes, total)` via the public `StateFlow`. UI surfaces render this as a progress bar with percent and human-readable byte counts.

### The state machine

`GemmaInference.state` is a `StateFlow<State>` that walks the entire init pipeline:

```
Idle
  └─▶ Downloading(bytes, total)      first launch only, ~5 to 15 minutes
        └─▶ LoadingEngine            LiteRT-LM weights and graph
              └─▶ (pre-warm runs)    invisible to user, see below
                    └─▶ Ready        generate() and generateStream() now work
```

Any failure transitions to `State.Error(message)` rather than crashing the app, and the consumer code surfaces it as a friendly card-level message ("Mira could not load her reasoning model. The screening result above is still valid.").

### Pre-warm

The engine's `initialize()` call loads the weights but defers a chunk of one-time setup work (kernel selection, graph compilation, memory layout) to the first inference call. Without intervention, the user pays that cost the first time they tap a chip in Ask Mira or land on a result screen, and the first answer feels noticeably slower than every subsequent one.

`GemmaInference.preWarm()` runs a tiny throwaway generation ("Hi") between the engine initialize and the public `Ready` transition. The "Loading the on-device reasoning model" UI lasts a few seconds longer, but the first real generation comes back dramatically faster. Pre-warm failures are caught, logged, and swallowed: a warmup failure is not a reason to refuse to expose the engine to the rest of the app.

## The four Gemma reasoning surfaces

Each surface has its own system instruction and user-prompt builder in `PromptTemplates.kt`.

### 1. Result narration (`PromptTemplates.ResultNarration`)

Surfaced as the **Mira explains** card on the result screen. After the classifier returns a label, a confidence, and a heatmap focus phrase (e.g. "centered on the cervix region of the image" vs "off-center, away from the expected cervix region"), Gemma generates a patient-facing explanation in the user's language. Not template translation: real reasoning over the result + confidence + heatmap focus, calibrated to be readable aloud to a low-literacy patient.

Streamed token by token via `generateStream`. The card transitions `Loading → Streaming(partial) → Loaded` and the play button only becomes available at `Loaded` so a user can't TTS-read a half-finished explanation. The final text is persisted to the `ScreeningRecord` so History can replay it later without a second 10-to-30 second generation.

### 2. Clinical decision support (`PromptTemplates.DecisionSupport`)

A separate Gemma call that takes the same result inputs plus any prior patient records and produces CHW-facing reasoning about next steps. The "second opinion" sits on the *decision*, not the image: confidence interpretation, what to communicate, when the heatmap focus suggests the model may be reading speculum walls rather than cervix tissue, whether to recapture or refer.

### 3. Workflow orchestration via function calling (`PromptTemplates.WorkflowOrchestration` + `FunctionRegistry`)

We use **JSON-mode function calling** rather than the SDK's native function-calling API, for SDK-version portability. `FunctionRegistry` declares five workflow functions (`flag_for_referral`, `schedule_followup`, `generate_clinic_report`, `log_outcome`, `recommend_recapture`) and `FunctionRegistry.formatAsJsonSchema()` formats them into the system prompt. Gemma emits function-call requests as fenced JSON; `FunctionRegistry.parseCalls(text)` extracts them tolerating markdown fence variations, and `FunctionRegistry.dispatch(...)` routes each call to a `WorkflowActionHandler` implementation.

This pattern lets the form-filling workflow become a conversational one: instead of the CHW navigating tabs to schedule a follow-up, Gemma reads the result and the patient context and proposes the right next actions as structured calls that the app actually executes.

### 4. Training Q&A (`PromptTemplates.TrainingQA`)

Surfaced as the **Ask Mira** screen. Chat-style interface where the CHW can ask anything about VIA technique, anatomy, when to refer, what acetowhitening looks like, edge cases.

Important: **conversation memory** is maintained across turns within a screen visit. A persistent `GemmaInference.ChatSession` wraps a single LiteRT-LM `Conversation` for the lifetime of the screen, so follow-up questions ("and what about for older patients?") resolve against the actual prior turn and short acknowledgments ("thanks", "good answer") don't get rejected as non-sequiturs. The session is created lazily when Gemma flips to `Ready` and closed on `onDispose` of the chat composable.

Below the welcome message, three starter-question chips offer the most common CHW knowledge gaps as one-tap prompts: "How do I find the transformation zone?", "When should I refer a patient?", "How long do I wait after applying acetic acid?". Tapping a chip submits through the exact same code path as the input bar.

## Streaming UX

Every Gemma surface that produces more than one sentence streams. Two design rules apply:

1. **The send/play action button is single-purpose at any moment.** In Ask Mira, while generating, the Send icon flips to a Stop square; tapping cancels the underlying coroutine and preserves whatever tokens have already streamed in (no error message replaces partial good content). In Mira explains, the Play button is hidden during streaming and only appears once the full narration is `Loaded`.

2. **No UI flashing during cancellation.** `CancellationException` is caught separately from `Throwable` in every collect block, so a user-initiated stop preserves the bubble's existing text and does not get translated into a "sorry, I could not generate" error message.

`MarkdownText` (a small custom composable, not a third-party library) parses the subset of Markdown Gemma actually emits (`**bold**`, `*italic*`, `# H1`, `## H2`, `### H3`, `- bullet`) into Compose `AnnotatedString` with proper bold/italic spans and real bullet glyphs. It is streaming-safe: an unclosed delimiter mid-stream renders as literal characters until the closing token arrives, at which point it snaps to the styled span on the next recomposition.

`TypingDotsIndicator` renders three dots that pulse in a staggered wave. Used inside an empty assistant bubble while Gemma is preparing the first token, so the visual transition from "thinking" to "answering" is just the dots being replaced by streamed text in the same surface, the way iMessage and WhatsApp do it.

## Languages and TTS

Mira ships in **ten languages**: English, Spanish, Portuguese, French, Swahili, Hausa, Yoruba, Igbo, Luganda, Quechua. Set via the standard Android per-app locale picker; persisted by `AppCompatDelegate.setApplicationLocales` (the manifest declares `AppLocalesMetadataHolderService` with `autoStoreLocales` so this survives process death without our own storage).

`Locale.getDefault().language` is mapped to an English language name (`"Swahili"`, `"Yoruba"`, etc.) via `ResultScreen.localeToLanguageName` and fed into Gemma's user prompt. Gemma generates in that language directly. The output text is what the `MiraExplainsCard` shows and what gets read aloud.

TTS playback is handled by Android's built-in `TextToSpeech`. Two important behaviors:

- **Engine preference.** We pass `"com.google.android.tts"` to the `TextToSpeech` constructor when the Google TTS package is installed, falling back to `null` (system default) otherwise. Google's TTS engine has higher-quality voices than the AOSP Pico fallback.
- **Voice selection.** Once `onInit` reports SUCCESS, a `LaunchedEffect` enumerates `tts.voices`, filters to voices matching the current locale's language and not requiring a network connection, and picks the highest `quality` enum value (`VERY_HIGH = 500`, `HIGH = 400`, etc.). Setting `tts.voice` to the best available overrides the engine default.

A known ceiling: on third-party apps Google TTS's truly neural voices (the SEANet `quality = 500` variants) are gated by app identifier. We end up with the best non-neural offline voice the engine will hand to a non-blessed app, which is `quality = 400`. It's better than Pico, still robotic compared to neural. The roadmap item to embed Piper TTS for clean offline neural voices in every language is documented in the `README`.

The Ask Mira surface, the Mira explains card on the result screen, and the Mira explains card on the History detail screen all share a TTS pattern: a single `TextToSpeech` instance per screen, an `UtteranceProgressListener` that tracks which utterance id is currently active (`"mira-result"` vs `"mira-explain"` vs `"mira-history"`), and a `ttsConfigured` boolean that gates the play buttons until voice selection has completed (prevents a first-click race where the click lands while the engine is still mid-reconfigure).

## Data model and persistence

`ScreeningRecord` is the unit of persistence:

```kotlin
data class ScreeningRecord(
    val id: String,
    val timestampMs: Long,
    val classification: ViaClassification,
    val confidence: Float,
    val patientId: String?,
    val imagePath: String?,
    val notes: String?,
    val userOverride: ViaClassification? = null,
    val narration: String? = null,
    val narrationLanguage: String? = null
)
```

Persisted as JSON via `ScreeningRepository` (a single `records.json` file in `filesDir`, plus JPEG-on-disk for images in `filesDir/images/`). Decode is defensive: records written by older builds without `narration` / `narrationLanguage` / `userOverride` still load cleanly with those fields null. A Room migration is on the roadmap for when we need indexed queries or reactive flows; the JSON manifest is enough for V1.

The narration field is populated by an `onNarrationReady` callback that `MiraExplainsCard` fires exactly once on clean generation completion. `ResultScreen` wires that callback to re-save the existing record via the same `save(bitmap = null, persistImage = false)` update pattern the override dialog uses. `HistoryDetailScreen` then passes the saved narration to `MiraExplainsCard` as `cachedNarration`, which short-circuits both the generation `LaunchedEffect` and the engine-state body dispatch: a user opening History while Gemma is still downloading sees their saved narration immediately, not a download progress bar.

The `userOverride` field exists so we can audit the model's classification-vs-human-override agreement rate for the prize-submission report. The model output is preserved alongside the human override; nothing gets silently overwritten.

## Privacy and network model

The app's connectivity story is intentional and short.

**Exactly one network event over the lifetime of an install:** the Gemma 4 model download from Hugging Face on first launch. The `INTERNET` permission in the manifest exists for that and nothing else, and the manifest comment says so explicitly. After the model is cached:

- **Zero per-screening network usage.** No image upload, no API call when the CHW captures a cervix, no telemetry, no analytics ping.
- **No patient data ever transmits.** Images, screening results, the saved Mira narrations, the patient ID strings, all of it stays in the app's private `filesDir`. Privacy is architectural, not policy.
- **Field-ready after setup.** A CHW in a rural clinic does not need a working connection to do a screening. The phone gets initialized once at a regional training or an NGO office and works indefinitely offline thereafter.

This is the same model as Gboard offline language packs, Google Translate offline languages, downloaded music on Spotify, Steam Deck games: one initial download, then full offline operation. The pitch is "field-ready", not "has never touched the internet".

## Performance characteristics

Rough orders of magnitude on the hardware we've tested against:

| Surface                          | Real Android phone w/ GPU | Emulator (CPU only) |
| -------------------------------- | ------------------------- | ------------------- |
| First-launch model download      | 5 to 15 min (one-time)    | 5 to 15 min (one-time) |
| Engine load + pre-warm           | 8 to 15 sec               | 25 to 45 sec        |
| Classifier inference per capture | < 1 sec                   | 1 to 3 sec          |
| Gemma per-token generation       | 15 to 30 tok/sec          | 3 to 8 tok/sec      |
| Result narration end-to-end      | 5 to 10 sec               | 25 to 60 sec        |
| Ask Mira first chat answer       | 5 to 15 sec               | 20 to 45 sec        |
| Memory peak (engine + classifier)| ~3.5 GB                   | ~3.5 GB             |

The emulator numbers are accurate to within a factor and a half on a modern MacBook. The real-device numbers assume a phone with `libOpenCL.so` and at least 4 GB of RAM (the standard configuration of any Tecno or Infinix shipping in the last two years).

## Honest limitations

Documented in detail in `README` ("What's still improving"). The short version:

- **Voice quality** on lower-resource locales is degraded because Android's built-in TTS doesn't have neural voices for them; Piper TTS embedding is the roadmap fix.
- **Inference latency** on CPU-only hardware (emulator, older devices) is slow enough to feel sluggish even with streaming and pre-warm. Real-device GPU paths fix this; on devices without GPU the app remains correct, just slow.
- **Conversation memory** is per-screen-visit, not persistent across app restarts. Single-session is fine for the training use case but worth extending.
- **The classifier** is a baseline, not a clinical claim, until validated on an out-of-distribution clinical cohort.

Each limitation in the public-facing README is paired with a concrete next step. None of them are "this is broken"; they are roadmap entries with current state explicitly stated.

## Building and running

See `README` "Build" section. The three-line summary: clone, open in Android Studio, hit Run. The classifier is bundled in the APK; Gemma downloads itself on first launch.

The minimum device target is Android 7.0 (API 24) with 4 GB of RAM. The app runs on emulators too but is meaningfully slower because of the absent GPU.

## License

Creative Commons Attribution 4.0 International (CC BY 4.0). See `LICENSE`.
