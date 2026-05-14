package com.mira.screening.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
                if (isGenerating && messages.lastOrNull()?.role == Role.User) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "Mira is thinking.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!isReady) {
                GemmaStatusBanner(state = gemmaState)
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = !isGenerating && isReady,
                onSend = {
                    val question = input.trim()
                    if (question.isEmpty() || isGenerating || !isReady) return@InputBar
                    input = ""
                    messages.add(ChatMessage(role = Role.User, text = question))
                    val assistantMessage = ChatMessage(role = Role.Assistant, text = "")
                    messages.add(assistantMessage)
                    val placeholderIndex = messages.lastIndex
                    isGenerating = true
                    scope.launch {
                        val builder = StringBuilder()
                        try {
                            GemmaInference.generateStream(
                                prompt = PromptTemplates.TrainingQA.userPrompt(question),
                                systemInstruction = PromptTemplates.TrainingQA.systemInstruction
                            ).catch { t ->
                                // Catches Flow-side errors emitted during
                                // collection. Surfaced as the assistant
                                // message so the user sees it.
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
                            // Final flush in case the catch block updated the
                            // builder without emit triggering a recomposition.
                            messages[placeholderIndex] = assistantMessage.copy(
                                text = builder.toString().ifEmpty { assistantMessage.text }
                            )
                        } catch (t: Throwable) {
                            // Defensive catch: any error that escapes the
                            // Flow's own .catch (e.g. an upstream synchronous
                            // throw before the Flow exists) lands here and
                            // becomes a visible assistant message instead of
                            // crashing the activity.
                            messages[placeholderIndex] = assistantMessage.copy(
                                text = "Sorry, I could not generate an answer right now. " +
                                    "(${t.message.orEmpty()})"
                            )
                        } finally {
                            isGenerating = false
                        }
                    }
                }
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
            if (isUser) {
                Text(
                    text = message.text.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
            } else {
                MarkdownText(
                    text = message.text.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit
) {
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
            enabled = enabled,
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
            onClick = onSend,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    if (enabled && value.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (enabled && value.isNotBlank()) {
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
