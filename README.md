# Mira

*An on-device cervical cancer screening assistant for low-resource clinics. Fully offline. Sub-$100 Android target. Gemma 4 powers multilingual patient communication, clinical decision support, and workflow orchestration on top of a domain-specific image classifier.*

Built for the [Gemma 4 Good Hackathon](https://www.kaggle.com/competitions/gemma-4-good-hackathon).

---

## The problem

Cervical cancer kills around 350,000 women every year, and roughly 90% of those deaths happen in low- and middle-income countries. It is also one of the most preventable cancers, if precancerous lesions are caught early. The screening method recommended by the WHO for resource-limited settings is Visual Inspection with Acetic Acid (VIA): swab the cervix with diluted vinegar, look for tissue that turns white, refer if needed. Cheap, fast, no lab required.

The bottleneck is the trained eye. Community health workers in rural clinics learn VIA in short courses and often lack the experience to be confident. Specialists are scarce and concentrated in cities. Patients get referred too often or not often enough. Mira is built around that gap.

## What Mira does

The flow is short. A community health worker (CHW) prepares the patient, waits 60 seconds after applying acetic acid, and taps capture. From that tap:

1. The phone fires three photos in a burst, drops any that are blurred by motion, and averages the rest into one composite.
2. A content validity gate refuses obviously non-cervical inputs.
3. An on-device classifier produces a probability and a 16×16 saliency map showing where the model looked.
4. Gemma 4, running locally on the phone, takes the result, the saliency map, and the patient context, and produces a multilingual explanation, suggested next steps, and structured workflow actions.
5. The CHW reads the suggestion, listens to the explanation in the patient's language, and makes the clinical call.

Everything runs on the device. No internet. No images leave the phone. The app ships in ten languages: English, Spanish, Portuguese, French, Swahili, Hausa, Yoruba, Igbo, Luganda, and Quechua.

## How Gemma 4 is used

Mira uses Gemma 4 as the language, reasoning, and orchestration layer around a domain-specific image classifier. Four distinct surfaces:

**Result narration.** After the classifier returns a probability and a saliency map, Gemma 4 generates a context-aware explanation in the patient's language. Not template translation. Real reasoning over the result, the confidence level, and what the CHW should say to the patient. Spoken aloud through text-to-speech for accessibility and low-literacy patients.

**Clinical decision support for the CHW.** Gemma 4 reasons over the result, the confidence score, the saliency interpretation, and the patient's prior records to suggest next steps. The "second opinion" sits on the decision, not just on the image.

**Workflow orchestration via native function calling.** Gemma 4 issues structured calls into the app: `flag_for_referral`, `schedule_followup`, `generate_clinic_report`, `log_outcome`. The form-filling workflow becomes a conversational one.

**Training mode for new CHWs.** A Q&A surface where Mira answers questions about VIA technique, cervical anatomy, when to refer, what acetowhitening looks like, and how to read edge cases. Gemma 4 reasons over a curated screening curriculum.

## Architecture

- **Image classifier:** a fine-tuned vision transformer, exported to TensorFlow Lite, running on-device.
- **Language and reasoning layer:** Gemma 4 E2B-IT running on-device via the Google AI Edge LiteRT-LM SDK, with GPU acceleration where available and automatic CPU fallback elsewhere.
- **Capture pipeline:** CameraX with motion-aware multi-frame averaging, a heuristic content validity gate, and live image-quality feedback.
- **Confidence gating:** predictions below a user-set threshold are routed to "Inconclusive" rather than delivered as a low-confidence positive or negative.
- **Persistence:** sandboxed local storage. No cloud sync.

UI is built with Jetpack Compose and Material 3. Minimum Android 7.0 (API 24). Targets sub-$100 Android phones (Tecno, Infinix, generic OEMs) as a hard requirement rather than an aspiration.

## Honest framing on the classifier

A few things deserve to be said directly.

The image classifier inside Mira is decision support, not a diagnostic claim. The current baseline is a DINOv2-S/14 fine-tune trained on the public Intel/MobileODT corpus using transformation-zone type as a documented proxy for VIA-positive/negative. It reports AUC 0.71 on a held-out split of the same dataset. That number is a pipeline-validation baseline, not a clinical performance claim. Until the model is validated on a properly out-of-distribution clinical cohort, it should be read as a placeholder.

What this hackathon submission really demonstrates is an architecture pattern for clinical AI in low-resource settings. A real working mobile app that runs Gemma 4 on-device, communicates with patients in their language, supports a CHW's decision-making rather than replacing it, and is honest about what an image classifier can and cannot reliably do today. The classifier is a slot; the platform is the contribution.

## Build

Requirements:

- Android Studio (Iguana or later)
- JDK 17+
- An Android device or emulator running Android 7.0 (API 24) or later with at least 4 GB of RAM (the Gemma 4 E2B model needs the headroom)
- An internet connection on first launch only

Steps:

1. Clone the repo and open it in Android Studio.
2. Hit Run.

That is the whole build. The cervical classifier (`via_model.tflite`, ~84 MB) is bundled with the source tree at `app/src/main/assets/via_model.tflite`, so it goes into the APK directly. The Gemma 4 E2B-IT LiteRT-LM model (~2.4 GB) is **fetched on first launch** from the official [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) Hugging Face mirror, cached at the app's private `filesDir`, and reused on every subsequent launch with no network access. Downloads support HTTP range resume, so a dropped connection mid-download picks up where it left off rather than restarting.

The `INTERNET` permission in the manifest exists exclusively for this one-time fetch. After the model is cached, the app is fully offline and no patient images, screening results, or telemetry ever leave the phone.

If you would rather skip the build and try Mira on your phone, the [Releases page](https://github.com/vic-zzy/mira-screening/releases) ships a pre-built APK. The classifier is bundled inside; Gemma 4 downloads itself on first run the same way.

## What's still improving

Mira is built in hackathon timescale, so several surfaces are honest works-in-progress. We are flagging them up front because shipping with awareness of where the seams are is more useful than hiding them.

**Voice quality.** Spoken playback uses Android's built-in text-to-speech. On English, Spanish, Portuguese, and French it sounds neural and clean (Mira prefers Google's TTS engine and picks the highest-quality voice available on the device for the current locale). On Swahili and Hausa it sounds more synthetic, and on Yoruba, Igbo, Luganda, and Quechua TTS support is patchy to absent — Gemma's narration text is still produced correctly, but the audio rendering is degraded or falls back to an English reader. The next step is embedding a lightweight neural TTS like Piper for clean offline voices across every supported locale; that adds ~50–100 MB per voice but levels the experience across languages.

**Inference latency on slower hardware.** Gemma 4 E2B has ~2.3B active parameters. On real Android phones with `libOpenCL.so` and GPU acceleration, generation runs 3–5× faster than CPU, narrations come back in a few seconds, and the device stays cool. On emulators (CPU-only) and older devices without GPU support, the same generation takes 10–30 seconds and the host machine's fan winds up. Pre-warming on app launch already hides the first-call JIT cost; per-token streaming on every Gemma surface means the user sees text appearing instead of waiting on a spinner. We are continuing to tune both the prompts (shorter outputs where they fit) and the runtime configuration.

**Conversation memory in Ask Mira.** Mira's chat now maintains a single LiteRT-LM Conversation across the session so follow-ups like "and for older patients?" or short acknowledgments like "thanks" no longer derail her. This works inside one screen visit; persisting chat history across app restarts is a logical next step.

The throughline: this is a real working app today, with several rough edges we know about and are pushing on. Every limitation here has a concrete next step rather than a "this is broken."

## License

Creative Commons Attribution 4.0 International (CC BY 4.0). See `LICENSE`.

## Contact

Victoria Polk, [github.com/vic-zzy](https://github.com/vic-zzy)
