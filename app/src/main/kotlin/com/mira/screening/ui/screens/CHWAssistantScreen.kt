package com.mira.screening.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mira.screening.gemma.GemmaInference
import com.mira.screening.gemma.PromptTemplates
import com.mira.screening.ui.components.MarkdownText
import com.mira.screening.ui.components.TypingDotsIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * "Ask Mira about VIA" training and Q&A surface for community health workers.
 *
 * The CHW types a question (technique, anatomy, when to refer, what
 * acetowhitening looks like, edge cases) and Gemma 4 streams an answer
 * token-by-token in the chat-style conversation log. The streaming UX is
 * intentional: it tells the user the model is working without making them
 * wait the full generation duration for any visible feedback.
 *
 * Hackathon-track alignment: Future of Education ("reimagine the learning
 * journey... empower the educator") and Digital Equity (a CHW in a rural
 * clinic with no specialist nearby gets an interactive curriculum at their
 * fingertips, offline).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CHWAssistantScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    // Track the live generation Job so the Stop button can cancel it.
    // Cleared in the finally block of sendQuestion's launch so the
    // "isGenerating ? show Stop : show Send" pivot in the input bar stays
    // in lockstep with the actual coroutine state.
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val gemmaState by GemmaInference.state.collectAsState()
    val isReady = gemmaState is GemmaInference.State.Ready

    // On first composition, seed the conversation with an introductory
    // assistant message so the empty-chat state isn't bare. This is local
    // copy, not a Gemma call.
    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            messages.add(
                ChatMessage(
                    role = Role.Assistant,
                    text = "Ask me anything about Visual Inspection with Acetic Acid. " +
                        "I can help with technique, what acetowhitening looks like, the " +
                        "transformation zone, when to refer, and how to read edge cases."
                )
            )
        }
    }

    // Auto-scroll to bottom as new tokens stream in or new messages arrive.
    //
    // We deliberately use the non-animated scrollToItem rather than
    // animateScrollToItem here. During fast streaming the LaunchedEffect
    // re-fires on every token, which would cancel an in-progress
    // animateScrollToItem before the follow-up scrollBy(MAX) can run,
    // leaving the bottom of a long answer perpetually clipped by the
    // input bar. scrollToItem is synchronous and not cancel-mid-flight,
    // so the subsequent scrollBy(MAX) reliably pins the bubble's bottom
    // to the viewport bottom (Compose clamps the scroll to the actual
    // max). The visual result during streaming is a smooth-feeling auto
    // follow rather than a jump, because each token only changes the
    // scroll by the height of a few extra characters.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
            listState.scrollBy(Float.MAX_VALUE)
        }
    }

    // Single send path so the input bar and the suggested-question chips
    // both share identical behaviour: same validation, same placeholder
    // bubble, same stream collection, same error fallback.
    val sendQuestion: (String) -> Unit = { rawQuestion ->
        val question = rawQuestion.trim()
        if (question.isNotEmpty() && !isGenerating && isReady) {
            input = ""
            messages.add(ChatMessage(role = Role.User, text = question))
            val assistantMessage = ChatMessage(role = Role.Assistant, text = "")
            messages.add(assistantMessage)
            val placeholderIndex = messages.lastIndex
            isGenerating = true
            currentJob = scope.launch {
                val builder = StringBuilder()
                try {
                    GemmaInference.generateStream(
                        prompt = PromptTemplates.TrainingQA.userPrompt(question),
                        systemInstruction = PromptTemplates.TrainingQA.systemInstruction
                    ).catch { t ->
                        // Catches Flow-side errors emitted during collection.
                        // Surfaced as the assistant message so the user sees
                        // it instead of a silent failure.
                        builder.clear()
                        builder.append(
                            "Sorry, I could not generate an answer right now. " +
                                "(${t.message.orEmpty()})"
                        )
                    }.collect { token ->
                        builder.append(token)
                        messages[placeholderIndex] = assistantMessage.copy(
                            text = builder.toString()
                        )
                    }
                    // Final flush in case the catch block populated the
                    // builder without an emit triggering a recomposition.
                    messages[placeholderIndex] = assistantMessage.copy(
                        text = builder.toString().ifEmpty { assistantMessage.text }
                    )
                } catch (ce: CancellationException) {
                    // User pressed Stop. Preserve whatever was generated so
                    // far rather than replacing it with an error string; if
                    // literally nothing came through before the cancel, leave
                    // a short acknowledgment so the empty bubble does not
                    // hang as a permanent typing indicator. Re-thrown per
                    // the structured-concurrency convention so the coroutine
                    // machinery completes properly.
                    if (builder.isEmpty()) {
                        messages[placeholderIndex] = assistantMessage.copy(
                            text = "_Stopped._"
                        )
                    }
                    throw ce
                } catch (t: Throwable) {
                    // Defensive catch: any error that escapes the Flow's own
                    // .catch (e.g. an upstream synchronous throw before the
                    // Flow even exists) lands here and becomes a visible
                    // assistant message rather than crashing the activity.
                    messages[placeholderIndex] = assistantMessage.copy(
                        text = "Sorry, I could not generate an answer right now. " +
                            "(${t.message.orEmpty()})"
                    )
                } finally {
                    isGenerating = false
                    currentJob = null
                }
            }
        }
    }

    // Cancel the live generation Job. The launch's finally block clears
    // isGenerating and currentJob, so we deliberately do not touch either
    // here, otherwise the two state owners can drift apart for one frame.
    val stopGeneration: () -> Unit = {
        currentJob?.cancel()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask Mira") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(message = message)
                }
                // Starter-question chips below the welcome message. Visible
                // only while the chat is still in its initial seeded state
                // (one assistant message, no user messages yet). Tapping a
                // chip submits the same way the input bar does, so judges
                // and first-time CHWs can get a real answer streaming in
                // without typing anything.
                if (messages.size == 1 && messages.first().role == Role.Assistant) {
                    item {
                        SuggestedQuestionChips(
                            questions = SUGGESTED_QUESTIONS,
                            enabled = isReady && !isGenerating,
                            onChipClick = sendQuestion
                        )
                    }
                }
            }

            if (!isReady) {
                GemmaStatusBanner(state = gemmaState)
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                inputEnabled = !isGenerating && isReady,
                isGenerating = isGenerating,
                onSend = { sendQuestion(input) },
                onStop = stopGeneration
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == Role.User
    val align = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        if (!isUser) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "Mira",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(4.dp))
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            when {
                isUser -> Text(
                    text = message.text.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
                // Empty assistant bubble means the placeholder has been
                // added but Gemma has not produced its first token yet.
                // Render the typing dots inside the bubble itself so the
                // visual transition from "thinking" to "answering" is just
                // the dots being replaced by streamed text in the same
                // surface, the way iMessage and WhatsApp do it.
                message.text.isEmpty() -> TypingDotsIndicator(
                    dotColor = MaterialTheme.colorScheme.primary
                )
                else -> MarkdownText(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
            }
        }
    }
}

/**
 * Bottom input bar with a single action button that pivots between Send and
 * Stop depending on whether Gemma is currently generating. When idle the
 * button submits the typed question (or stays dimmed if the field is empty
 * or Gemma is not yet Ready). When generating it cancels the live coroutine
 * so the user can cut off a long answer once they've seen enough.
 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    inputEnabled: Boolean,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val canSend = inputEnabled && value.isNotBlank()
    // The action button stays interactive in two situations: when Gemma is
    // already generating (so the user can press Stop), and when the user
    // has typed something and the engine is Ready (so they can press Send).
    val buttonActive = isGenerating || canSend
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask a question about VIA.") },
            enabled = inputEnabled,
            shape = RoundedCornerShape(20.dp),
            maxLines = 4,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Send
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSend = { onSend() }
            )
        )
        Spacer(Modifier.size(8.dp))
        IconButton(
            onClick = { if (isGenerating) onStop() else onSend() },
            enabled = buttonActive,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    if (buttonActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
        ) {
            Icon(
                imageVector = if (isGenerating) {
                    Icons.Filled.Stop
                } else {
                    Icons.AutoMirrored.Filled.Send
                },
                contentDescription = if (isGenerating) "Stop" else "Send",
                tint = if (buttonActive) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * Shown above the input bar while Gemma's initialization pipeline is still in
 * flight. Mirrors the state machine in [MiraExplainsCard] so the CHW gets
 * consistent feedback wherever the model is being prepared: a progress bar for
 * the one-time download, a spinner while LiteRT-LM warms up, or a friendly
 * error string if any step failed.
 */
@Composable
private fun GemmaStatusBanner(state: GemmaInference.State) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        when (state) {
            is GemmaInference.State.Downloading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Downloading Mira's reasoning model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${state.percent}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "One-time download. The model stays on this phone afterwards. " +
                        "${formatBytes(state.bytesDownloaded)} of ${formatBytes(state.totalBytes)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            GemmaInference.State.LoadingEngine,
            GemmaInference.State.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Loading the on-device reasoning model.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is GemmaInference.State.Error -> Text(
                text = "Mira could not load her reasoning model right now. " +
                    "(${state.message.take(100)})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GemmaInference.State.Ready -> Unit
        }
    }
}

/**
 * Pre-seeded starter questions surfaced as tappable chips below the welcome
 * message. Three of the most common CHW knowledge gaps for VIA: anatomy,
 * referral decision, and timing. Phrased the way a CHW would actually ask,
 * not the way a textbook would title a section.
 */
private val SUGGESTED_QUESTIONS = listOf(
    "How do I find the transformation zone?",
    "When should I refer a patient?",
    "How long do I wait after applying acetic acid?"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestedQuestionChips(
    questions: List<String>,
    enabled: Boolean,
    onChipClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Try one of these",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        // FlowRow with zero explicit vertical spacing: SuggestionChip wraps
        // itself in a 48dp minimum interactive size for accessibility, which
        // already supplies all the visual breathing room between rows. Any
        // extra Arrangement.spacedBy here would compound on top of that
        // 48dp and make the chip stack look airy and disconnected from the
        // welcome message above it.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            questions.forEach { question ->
                SuggestionChip(
                    onClick = { onChipClick(question) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = question,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return "%.2f GB".format(gb)
    val mb = bytes / (1024.0 * 1024.0)
    return "%.0f MB".format(mb)
}

private enum class Role { User, Assistant }

private data class ChatMessage(
    val role: Role,
    val text: String
)
