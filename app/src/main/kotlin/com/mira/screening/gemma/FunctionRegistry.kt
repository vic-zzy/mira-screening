package com.mira.screening.gemma

import org.json.JSONArray
import org.json.JSONObject

/**
 * Defines the structured workflow tools Gemma 4 can call, and provides the
 * machinery to expose those tools as a JSON schema for Gemma's system
 * instruction, parse Gemma's JSON tool-call output, and dispatch calls to a
 * handler implementation.
 *
 * This is what turns Mira's screening flow from a sequence of UI screens into
 * a conversational workflow: after a screening completes, Gemma is given the
 * result + patient context and decides which combination of these functions
 * to call. The app executes them and the CHW sees the outcomes happen.
 *
 * We use JSON-mode rather than the LiteRT-LM SDK's potential native function-
 * calling API for two reasons: (1) JSON-mode works across SDK versions
 * without depending on undocumented surface, (2) Gemma 4's instruction
 * following is reliable enough that the model produces well-formed JSON
 * consistently when asked. If the SDK exposes a stable native FC API later
 * the swap is mechanical, as only formatAsJsonSchema and parseCalls need to
 * change.
 */
object FunctionRegistry {

    /**
     * A parameter on one of Mira's workflow functions.
     */
    data class FunctionParam(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean = true
    )

    /**
     * A workflow function Gemma can call.
     */
    data class FunctionDescriptor(
        val name: String,
        val description: String,
        val params: List<FunctionParam>
    )

    /**
     * A parsed call from Gemma's output. The args map contains string,
     * integer, or boolean values; callers cast at the dispatch site.
     */
    data class FunctionCall(
        val function: String,
        val args: Map<String, Any>
    )

    /**
     * The handler interface that Mira's screen layer implements. Each method
     * corresponds to a function in the registry. Implementations route the
     * call into the existing record/repository/UI plumbing.
     */
    interface WorkflowActionHandler {
        fun flagForReferral(reason: String)
        fun scheduleFollowup(weeks: Int, reason: String)
        fun generateClinicReport(includeImage: Boolean)
        fun logOutcome(outcome: String, notes: String?)
        fun recommendRecapture(reason: String)
    }

    /**
     * The full set of workflow functions exposed to Gemma. Adding a new
     * function: add a FunctionDescriptor here, add a method on
     * WorkflowActionHandler, add a branch to dispatch.
     */
    val functions: List<FunctionDescriptor> = listOf(
        FunctionDescriptor(
            name = "flag_for_referral",
            description = "Mark this patient for specialist referral. " +
                "Use when the screening is positive, or when clinical " +
                "judgment from prior context suggests referral despite an " +
                "ambiguous current result.",
            params = listOf(
                FunctionParam(
                    name = "reason",
                    type = "string",
                    description = "Short clinical reason for the referral, " +
                        "in the CHW's working language."
                )
            )
        ),
        FunctionDescriptor(
            name = "schedule_followup",
            description = "Book a return visit for this patient at a future " +
                "date. Use for negative results that still warrant routine " +
                "rescreening, or for inconclusive results that should be " +
                "retried at a later visit.",
            params = listOf(
                FunctionParam(
                    name = "weeks",
                    type = "integer",
                    description = "Number of weeks from today to schedule " +
                        "the followup."
                ),
                FunctionParam(
                    name = "reason",
                    type = "string",
                    description = "Short reason the followup is needed."
                )
            )
        ),
        FunctionDescriptor(
            name = "generate_clinic_report",
            description = "Produce a clinic-format summary report for the " +
                "patient's record. Always safe to call; use after any " +
                "completed screening.",
            params = listOf(
                FunctionParam(
                    name = "include_image",
                    type = "boolean",
                    description = "Whether to include the captured cervical " +
                        "image in the report. Defaults to false for privacy.",
                    required = false
                )
            )
        ),
        FunctionDescriptor(
            name = "log_outcome",
            description = "Record the final clinical outcome for this " +
                "screening session. The outcome label is what gets shown " +
                "in the patient's history view.",
            params = listOf(
                FunctionParam(
                    name = "outcome",
                    type = "string",
                    description = "Outcome label. Examples: 'Referred', " +
                        "'Follow-up scheduled', 'No action needed', " +
                        "'Recapture recommended'."
                ),
                FunctionParam(
                    name = "notes",
                    type = "string",
                    description = "Optional free-text notes from the CHW " +
                        "or the reasoning layer.",
                    required = false
                )
            )
        ),
        FunctionDescriptor(
            name = "recommend_recapture",
            description = "Tell the community health worker to capture " +
                "another image instead of acting on this result. Use when " +
                "confidence is below the clinic's threshold, when the " +
                "attention heatmap is off-target, or when image quality is " +
                "visibly poor.",
            params = listOf(
                FunctionParam(
                    name = "reason",
                    type = "string",
                    description = "Why a recapture is recommended, in plain " +
                        "language for the CHW."
                )
            )
        )
    )

    /**
     * Build a JSON schema description of all available functions, to be
     * embedded in Gemma's system instruction. The format follows the
     * standard JSON-schema tool-use convention which Gemma 4 has been
     * trained to recognize.
     */
    fun formatAsJsonSchema(): String {
        val arr = JSONArray()
        for (f in functions) {
            val properties = JSONObject()
            val required = JSONArray()
            for (p in f.params) {
                val prop = JSONObject()
                    .put("type", p.type)
                    .put("description", p.description)
                properties.put(p.name, prop)
                if (p.required) required.put(p.name)
            }
            val schema = JSONObject()
                .put("name", f.name)
                .put("description", f.description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", required)
                )
            arr.put(schema)
        }
        return arr.toString(2)
    }

    /**
     * Parse Gemma's output into a list of FunctionCalls. Strips common
     * markdown fences (```json ... ```) and tolerates leading/trailing
     * non-JSON text by extracting the first JSON array in the output.
     *
     * Returns an empty list if no valid calls are parsed. Callers should
     * treat that as "Gemma did not request any actions" rather than as an
     * error.
     */
    fun parseCalls(modelOutput: String): List<FunctionCall> {
        val cleaned = extractJsonArray(modelOutput) ?: return emptyList()
        return try {
            val arr = JSONArray(cleaned)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("function").ifBlank {
                    obj.optString("name")
                }
                if (name.isBlank()) return@mapNotNull null
                val argsJson = obj.optJSONObject("args")
                    ?: obj.optJSONObject("arguments")
                    ?: JSONObject()
                val args = mutableMapOf<String, Any>()
                argsJson.keys().forEach { key ->
                    val v = argsJson.get(key)
                    args[key] = v
                }
                FunctionCall(function = name, args = args)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Route a parsed FunctionCall to the right method on the handler.
     * Silently ignores unknown function names rather than throwing, which is
     * the safer behavior for a CHW-facing app (a hallucinated tool name from
     * the model should not crash the screen).
     */
    fun dispatch(call: FunctionCall, handler: WorkflowActionHandler) {
        when (call.function) {
            "flag_for_referral" -> handler.flagForReferral(
                reason = call.args["reason"] as? String ?: ""
            )
            "schedule_followup" -> handler.scheduleFollowup(
                weeks = (call.args["weeks"] as? Number)?.toInt() ?: 0,
                reason = call.args["reason"] as? String ?: ""
            )
            "generate_clinic_report" -> handler.generateClinicReport(
                includeImage = call.args["include_image"] as? Boolean ?: false
            )
            "log_outcome" -> handler.logOutcome(
                outcome = call.args["outcome"] as? String ?: "",
                notes = call.args["notes"] as? String
            )
            "recommend_recapture" -> handler.recommendRecapture(
                reason = call.args["reason"] as? String ?: ""
            )
            // Unknown function name: ignore. See KDoc above.
        }
    }

    /**
     * Pull a JSON array out of a possibly-noisy model output. Handles three
     * common shapes:
     *   1. Plain JSON array: starts with '[', ends with ']'.
     *   2. Markdown-fenced: ```json [...] ``` or ``` [...] ```.
     *   3. Array embedded in prose: extract the substring from the first '['
     *      to the matching ']'.
     */
    private fun extractJsonArray(output: String): String? {
        // Strip markdown fences.
        val fenceStripped = output
            .replace("```json", "")
            .replace("```", "")
            .trim()
        if (fenceStripped.startsWith("[") && fenceStripped.endsWith("]")) {
            return fenceStripped
        }
        // Extract first balanced [ ... ].
        val start = fenceStripped.indexOf('[')
        val end = fenceStripped.lastIndexOf(']')
        if (start == -1 || end == -1 || end <= start) return null
        return fenceStripped.substring(start, end + 1)
    }
}
