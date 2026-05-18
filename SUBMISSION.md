# Mira: on-device cervical cancer screening with Gemma 4

**Author:** Victoria Polk
**Tracks:** Impact (Health & Sciences) · Cactus · LiteRT · Main
**Repo:** [vic-zzy/mira-screening](https://github.com/vic-zzy/mira-screening) · **License:** CC BY 4.0

---

## The problem

Cervical cancer kills around 350,000 women a year, and roughly 90% of those deaths happen in low- and middle-income countries. It is one of the most preventable cancers on the planet. Caught early, precancerous lesions can be treated in a single visit. The screening method the WHO recommends for resource-limited settings is Visual Inspection with Acetic Acid (VIA): swab the cervix with diluted vinegar, look for tissue that turns white, refer if needed. Cheap, fast, no lab.

The bottleneck is the trained eye. Specialists are scarce and concentrated in cities. Community health workers (CHWs) in rural clinics learn VIA in short courses and often lack the experience to be confident, so patients get referred too often or not often enough. The technology to fix this exists. It just doesn't reach the women who need it. We built Mira to close that gap with a tool a CHW can carry in a pocket and use in a clinic with no internet.

## What Mira does

Mira is an Android app. A CHW prepares the patient, waits 60 seconds after applying acetic acid, and taps capture. From that tap:

1. The phone fires a three-frame burst, drops anything corrupted by motion, and averages the rest into one composite.
2. Quality and validity gates reject blurry, glare-heavy, or obviously non-cervical inputs.
3. An on-device classifier returns a probability and a 16×16 saliency map.
4. Gemma 4, also running locally, narrates the result in the patient's language, advises the CHW on next steps, and proposes structured workflow actions.
5. A persistent **Ask Mira** chat lets the CHW ask follow-up questions about technique, anatomy, or when to refer, with the screening context already in scope.

Everything runs on the device. No images leave the phone. Mira ships in ten languages: English, Spanish, Portuguese, French, Swahili, Hausa, Yoruba, Igbo, Luganda, and Quechua.

## How it works: the stack

Mira is two on-device models stitched together by Compose UI, a capture pipeline, and a small persistence layer.

**Vision: a fine-tuned ViT in TFLite.** The classifier is a DINOv2-S/14 fine-tune exported to TensorFlow Lite, shipping as an 84 MB `via_model.tflite` bundled in the APK. It runs through the XNNPack delegate, which replaces about 85% of the operators with optimized CPU kernels. Output is a softmax probability plus a 16×16 attention heatmap; predictions below a user-set confidence threshold are routed to "Inconclusive" rather than delivered as a low-confidence positive or negative.

**Language and reasoning: Gemma 4 E2B-IT via LiteRT-LM.** Gemma 4 handles every reasoning surface, running through Google AI Edge's LiteRT-LM SDK (`litertlm-android:0.11.0`). The model file is `gemma-4-E2B-IT` in `.litertlm` format, around 2.4 GB and 2.3B active parameters. Because Android caps single-asset arrays at 2 GB, we don't bundle Gemma in the APK; instead the app fetches it from the official `litert-community/gemma-4-E2B-it-litert-lm` Hugging Face mirror on first launch, caches it in `filesDir`, and reuses it offline forever after. Downloads use HTTP Range resume, so a dropped connection picks up where it left off.

**Capture pipeline.** CameraX with `CAPTURE_MODE_MINIMIZE_LATENCY`, AWB locked via Camera2 interop for color stability across burst frames, and `HIGH_QUALITY` noise reduction. Motion-corrupted frames are dropped before averaging; the surviving composite is checked for blur, then routed through a heuristic cervix-validity gate. Only after all gates pass does the image hit the classifier.

**The two models talk through a small text interface.** The classifier hands Gemma a label, a confidence, a phrase describing where the heatmap concentrated, and any prior patient context. Gemma hands back natural-language explanations, decision suggestions, and JSON-encoded function calls. The split is deliberate task routing: Gemma 4 wasn't trained on cervical pathology, so we keep the visual call in the domain-specialist model and let Gemma do what language models are good at. The app intelligently routes each step of the workflow to the model best suited for it: vision tasks to the classifier, language and reasoning to Gemma 4. All local-first, with no network calls at any point.

**On-device throughout.** The `INTERNET` permission exists for one event in the life of the install (the Gemma download) and nothing else. Per-screening network usage is zero. Privacy is architectural, not policy.

## Why Gemma 4

We picked Gemma 4 E2B-IT for the size/quality tradeoff on real mobile hardware. At ~2.3B active parameters it fits comfortably alongside the classifier inside the ~3.5 GB memory envelope of a sub-$100 Tecno or Infinix phone. We chose Google AI Edge's LiteRT-LM runtime for Gemma 4 because it gives us first-party Android-native support and hardware delegation across mobile SoCs. Where `libOpenCL.so` is present (most phones shipping in the last two years), the runtime picks it up automatically and we get roughly 3 to 5× speedup over CPU; where it isn't, the same code path falls back to CPU without crashing.

Streaming generation is what makes the experience feel like a conversation instead of a query. The CHW sees Mira "think" (tokens appearing in the explanation card, dots pulsing in the Ask Mira bubble) rather than staring at a spinner. Pre-warming after engine init pays the one-time JIT cost up front, so the first real generation isn't slower than every subsequent one.

The four Gemma surfaces all share the same engine and the same prompt-template module: **result narration** (patient-facing, multilingual), **decision support** (CHW-facing, second opinion on the decision rather than the image), **workflow orchestration** (JSON function calling into `flag_for_referral`, `schedule_followup`, `generate_clinic_report`, `log_outcome`, `recommend_recapture`), and **training Q&A** (Ask Mira, with persistent chat memory across the session).

## Why this matters: the impact angle

The WHO has a concrete target: eliminate cervical cancer as a public health problem, by combining HPV vaccination, screening, and treatment. The blocker isn't the science. It's distribution. The screening gap is geographic and economic, not technological. The tools exist, they just don't reach the rural clinics where most of the dying happens.

Mira's offline-first design targets the unreachable. A CHW in a rural Kenyan or Peruvian clinic doesn't need a working connection to do a screening. The phone gets initialized once at a regional training or an NGO office and works indefinitely after. That's the same delivery pattern as Gboard offline language packs or downloaded Spotify music: one initial download, then full offline operation. We think it's the right shape for clinical AI in low-resource settings.

CHWs are already the front line in LMICs. We built Mira to augment their judgment, not replace clinicians. Every surface frames suggestions, not commands. Every prompt template tells Gemma it isn't the decision-maker. Positive results route to clinician follow-up. The conversational layer matters because CHWs need to *learn* the technique (where the transformation zone is, how long to wait after acetic acid, what acetowhitening actually looks like), and Mira teaches as it screens. Same app, same model, same screen visit: capture, narrate, then "and what does it look like in older patients?"

We are honest about what this is. The classifier produces a screening signal, not a diagnosis. Mira's job is to give a CHW better information than they had a minute ago.

## Honest limitations

The current classifier is a baseline. It was fine-tuned on the public Intel/MobileODT corpus using transformation-zone type as a documented proxy label for VIA-positive/negative, and it reports AUC 0.71 on a held-out split of the same dataset. That's a pipeline-validation number, not a clinical claim. Before any field deployment the model needs out-of-distribution validation, starting with **Cervix93** as a cross-validation set and then IARC-atlas-aligned cohorts as they become available. The hand-off contract to Gemma stays the same when we swap the weights.

The quality gates are heuristics (Laplacian variance for blur, a simple cervix-validity check), not the full OpenCV pipeline we want. Gemma narration is fast on flagship phones with GPU acceleration but slower on the mid-range hardware we actually target; performance characterization across the device range is next. And Mira has no clinical validation yet. It's a prototype, not a deployed product.

## What's next

- **Cervix93 OOD validation**, then IARC-atlas cohorts.
- **OpenCV-Android** for blur and glare detection.
- **Multi-language prompt tuning** for pilots.
- **Connect-when-you-can sync** for referrals only.
- **Clinical validation partnership** with an NGO or academic group.
- **Piper TTS embedding** for offline neural voices.

## Closing

Around 350,000 women die every year from a disease that is mostly preventable. The bridge from "preventable in principle" to "actually prevented" runs through the CHW in a clinic with no signal. Mira is one piece of that bridge: a pocket-sized tool that screens, explains, and teaches without ever sending an image off the phone. We built it on Gemma 4 because the model can sit in a pocket and still help a CHW make a better decision than they could alone.
