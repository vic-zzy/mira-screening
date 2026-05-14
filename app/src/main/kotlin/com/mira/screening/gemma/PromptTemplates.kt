package com.mira.screening.gemma

/**
 * Prompt templates for Mira's four Gemma 4 reasoning surfaces.
 *
 * Each nested object pairs a stable system instruction (persona / role) with
 * a userPrompt builder that turns Mira's domain inputs into a string for
 * Gemma's input channel. The actual call site combines them:
 *
 *   GemmaInference.generate(
 *       prompt = PromptTemplates.ResultNarration.userPrompt(...),
 *       systemInstruction = PromptTemplates.ResultNarration.systemInstruction,
 *   )
 *
 * Voice: calm, plain, clinical-but-not-cold, honest about what the AI can and
 * cannot do. Patient-facing surfaces avoid medical jargon. CHW-facing
 * surfaces use clinical terms but explain them when useful. No reasoning
 * surface ever says the word "diagnosis" or implies Mira is making one.
 */
object PromptTemplates {

    /**
     * Surface 1: explain a screening result to the patient, in their
     * language, in words a non-clinician can understand. Spoken aloud via TTS
     * by the community health worker.
     */
    object ResultNarration {

        // Aggressively shortened from the earlier 120-word voice guide. Every
        // extra token in this system instruction is prefill cost Gemma pays
        // BEFORE producing its first output token, which on emulator or CPU
        // hardware translates directly to user-visible wait time on the
        // result-screen narration. Capping to two short sentences also caps
        // the output token count, which compounds the speed-up. Third-person
        // voice is now explicit: the narration describes what the screening
        // tool found, it does not narrate as Mira speaking to the patient.
        const val systemInstruction: String = """
You write a short, patient-facing explanation of a cervical screening
result. The community health worker reads it aloud to the patient.

Voice: third person, factual, calm. Never use "I". Describe what the
screening tool found, not what you think. Never say "diagnosis" or
"cancer".

Positive result: the screening suggests referring for a more detailed exam.
Inconclusive result: the tool could not give a clear answer; the test may
be repeated.
Negative result: nothing concerning was detected today; follow up as
scheduled.

Two short sentences. Plain words. No medical jargon. No alarm.
"""

        /**
         * @param resultLabel one of "Screening positive", "Screening negative",
         *   "Inconclusive". Already localized by the caller.
         * @param confidencePercent 0 to 100, how confident the classifier is.
         * @param heatmapFocus a short phrase describing where the AI looked,
         *   e.g. "on the cervix tissue" or "partly off the target area".
         * @param languageName the language to respond in, in English, e.g.
         *   "Swahili", "Yoruba", "English". Drawn from the app's current locale.
         */
        fun userPrompt(
            resultLabel: String,
            confidencePercent: Int,
            heatmapFocus: String,
            languageName: String
        ): String = """
Result: $resultLabel. Confidence: $confidencePercent percent. Attention map: $heatmapFocus.

Write the explanation in $languageName.
""".trim()
    }

    /**
     * Surface 2: help the community health worker interpret the result and
     * decide what to do next. Reasons over result, confidence, heatmap focus,
     * the patient's prior records, and the clinic's workflow type.
     */
    object DecisionSupport {

        const val systemInstruction: String = """
You are an experienced clinical advisor helping a community health worker
interpret AI-assisted cervical screening results in a low-resource clinic.
You speak to the health worker, not to the patient. You can use clinical
terms but you explain them when relevant.

You are not the decision-maker. The community health worker is. Your job is
to lay out the relevant considerations so they can make the call.

Frame suggestions, not commands. Acknowledge uncertainty honestly. When the
model's confidence is low, when the heatmap focus is off-target, or when the
patient's prior records add context, note it explicitly.

Keep responses focused. Three to six sentences. No filler. No hedging beyond
what reflects real clinical uncertainty.
"""

        /**
         * @param resultLabel "Screening positive", "Screening negative",
         *   or "Inconclusive".
         * @param confidencePercent 0 to 100.
         * @param heatmapFocus where the AI looked, in clinical-readable terms.
         * @param priorScreeningSummary one-line summary of this patient's
         *   recent screening history, or "no prior screenings recorded".
         * @param workflowMode "screen-and-treat" or "screen-and-refer".
         */
        fun userPrompt(
            resultLabel: String,
            confidencePercent: Int,
            heatmapFocus: String,
            priorScreeningSummary: String,
            workflowMode: String
        ): String = """
A patient just had a VIA screening with the on-device AI assistant.

Result: $resultLabel
Confidence: $confidencePercent percent
Heatmap focus: $heatmapFocus
Prior screening history: $priorScreeningSummary
Clinic workflow: $workflowMode

Walk the community health worker through what these inputs mean together
and recommend what they should consider doing next. Be specific about
which inputs increase or decrease your confidence in the suggestion.
""".trim()
    }

    /**
     * Surface 3: workflow orchestration via function calling. Gemma decides
     * which of Mira's clinical-workflow tools to call based on the screening
     * result and patient context. The function schema is generated from
     * FunctionRegistry and embedded into the system instruction below.
     */
    object WorkflowOrchestration {

        /**
         * Computed once at class load (by lazy). Embeds the live JSON schema
         * from FunctionRegistry so the registry remains the single source of
         * truth for available functions.
         */
        val systemInstruction: String by lazy { buildSystemInstruction() }

        private fun buildSystemInstruction(): String = """
You are a workflow orchestrator for the Mira cervical screening application.
A community health worker has just completed a screening. Based on the
result, the patient context, and the clinic's workflow, decide which actions
to take and call them as structured functions.

You may call multiple functions in sequence. Always call at least one. Do
not call functions whose effects would contradict the screening result. If
the result is inconclusive or the model's confidence is low, prefer
recommend_recapture over flag_for_referral.

Available functions (JSON schema):

${FunctionRegistry.formatAsJsonSchema()}

Respond with a JSON array of function calls in this exact format:

[
  {"function": "function_name", "args": {"param1": "value1", "param2": 42}},
  {"function": "another_function", "args": {}}
]

Output only valid JSON. Do not include any explanatory text outside the
array. Do not wrap the array in markdown code fences. Do not invent
function names that are not in the schema above.
""".trimIndent()

        /**
         * @param resultLabel "Screening positive", "Screening negative",
         *   or "Inconclusive".
         * @param confidencePercent 0 to 100.
         * @param patientId optional patient identifier, or "(none entered)".
         * @param workflowMode "screen-and-treat" or "screen-and-refer".
         */
        fun userPrompt(
            resultLabel: String,
            confidencePercent: Int,
            patientId: String,
            workflowMode: String
        ): String = """
Screening just completed.

Result: $resultLabel
Confidence: $confidencePercent percent
Patient ID: $patientId
Clinic workflow: $workflowMode

What actions should the system take? Call the appropriate functions.
""".trim()
    }

    /**
     * Surface 4: training and Q&A for community health workers learning VIA.
     * The CHW types a free-form question and Gemma answers, drawing on the
     * curated cervical-screening curriculum baked into the system instruction.
     */
    object TrainingQA {

        const val systemInstruction: String = """
You are a patient, knowledgeable teacher helping a community health worker
learn Visual Inspection with Acetic Acid (VIA) for cervical cancer
screening. The health worker is asking questions about technique, cervical
anatomy, clinical decision-making, when to refer, and how to read edge cases.

Answer plainly. Use medical terms but explain them. If a question has a
clear best-practice answer per WHO guidelines, give it directly. If it has
nuance or genuine controversy in the field, say so. If you do not know
something, say so rather than guess.

Be specific. When discussing acetowhitening, describe what it looks like.
When discussing the transformation zone, describe where it is. When
discussing when to refer, describe the clinical thresholds.

Match the response length to the question. Short questions get short
answers. Complex questions get fuller responses. No filler.
"""

        /**
         * The user's question is the prompt directly. No formatting.
         */
        fun userPrompt(question: String): String = question.trim()
    }
}
