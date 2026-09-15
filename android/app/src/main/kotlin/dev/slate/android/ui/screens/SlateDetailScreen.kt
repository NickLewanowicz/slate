package dev.slate.android.ui.screens

import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.slate.android.data.SyncState
import dev.slate.android.interact.DeliveryStatus
import dev.slate.android.spec.OptionStyle
import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.SpecTextStyle
import dev.slate.android.spec.StatusRowElement
import dev.slate.android.spec.TodoListElement
import dev.slate.android.ui.SlateDetailViewModel
import dev.slate.android.ui.theme.toneColor
import dev.slate.android.widget.SizeBucket
import dev.slate.android.widget.WidgetRenderer

/**
 * Slate detail: TOP = the exact widget (RemoteViews applied into an AndroidView,
 * bit-identical to home screen), with a bucket selector; BOTTOM = native Compose
 * interaction surface rendered from the same parsed spec.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlateDetailScreen(
    viewModel: SlateDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val sync by viewModel.syncState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var bucket by remember { mutableStateOf(SizeBucket.MEDIUM) }
    val context = LocalContext.current

    // 20s foreground fast-poll while this screen is visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.startFastPoll(this)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.spec?.title?.ifBlank { state.detail?.slateId ?: "" } ?: "Slate") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = sync is SyncState.Syncing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding),
        ) {
            val detail = state.detail
            if (detail?.spec == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Nothing here yet.", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Once your agent pushes to this slate it appears here — and on your home screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@PullToRefreshBox
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // --- Widget preview (exact RemoteViews render) ---
                item(key = "preview") {
                    Column {
                        Text("Home-screen preview", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SizeBucket.entries.forEachIndexed { index, b ->
                                SegmentedButton(
                                    selected = bucket == b,
                                    onClick = { bucket = b },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SizeBucket.entries.size),
                                ) { Text(b.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Card {
                            val spec = detail.spec
                            val cached = detail.cached
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                factory = { ctx -> FrameLayout(ctx) },
                                update = { frame ->
                                    frame.removeAllViews()
                                    val rv = WidgetRenderer.render(
                                        context = context,
                                        bucket = bucket,
                                        spec = spec,
                                        cached = cached,
                                        answered = detail.answered,
                                        preview = true,
                                    )
                                    // apply() inflates + applies actions; caller attaches the result.
                                    rv.apply(context, frame)?.let { frame.addView(it) }
                                    frame.getChildAt(0)?.layoutParams =
                                        FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                        )
                                },
                            )
                        }
                    }
                }

                // --- Native interaction surface from the same spec ---
                itemsIndexed(detail.spec.children, key = { idx, el -> el.id ?: "${el.typeName}:$idx" }) { _, el ->
                    when (el) {
                        is StatusRowElement -> StatusRowComposable(el)
                        is TodoListElement -> TodoListComposable(
                            list = el,
                            onToggle = { item ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleTodo(el.id, item.id)
                            },
                        )
                        is QuestionElement -> QuestionComposable(
                            question = el,
                            delivery = state.delivery[viewModel.questionKey(el.id)],
                            answeredChoice = state.detail?.answered?.get(el.id),
                            onAnswer = { option ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.answer(el, option)
                            },
                            onSendText = { value -> viewModel.sendText(el, value) },
                        )
                        is dev.slate.android.spec.TextElement -> TextComposable(el)
                        else -> {} // progress/divider/spacer/containers are widget-only concerns
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun StatusRowComposable(el: StatusRowElement) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(el.icon, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(el.label, style = MaterialTheme.typography.bodyLarge)
                el.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            el.value?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = toneColor(el.tone))
            }
        }
    }
}

@Composable
private fun TodoListComposable(list: TodoListElement, onToggle: (dev.slate.android.spec.TodoItem) -> Unit) {
    Column {
        list.items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = item.checked, onCheckedChange = { onToggle(item) })
                Text(
                    item.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun QuestionComposable(
    question: QuestionElement,
    delivery: DeliveryStatus?,
    answeredChoice: dev.slate.android.data.AnsweredChoice?,
    onAnswer: (dev.slate.android.spec.QuestionOption) -> Unit,
    onSendText: (String) -> Unit,
) {
    val answered = answeredChoice != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(question.prompt, style = MaterialTheme.typography.titleMedium)
            question.options.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { index, option ->
                        val isChosen = answeredChoice?.optionId == option.id
                        val locked = answered
                        val colors = when {
                            option.style == OptionStyle.DANGER ->
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                )
                            isChosen -> ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                            option.style == OptionStyle.PRIMARY && !locked -> ButtonDefaults.buttonColors()
                            else -> ButtonDefaults.outlinedButtonColors()
                        }
                        if (option.style != OptionStyle.PRIMARY || isChosen || locked) {
                            OutlinedButton(
                                onClick = { if (!locked) onAnswer(option) },
                                enabled = !locked,
                                colors = colors,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (isChosen) "✓ ${option.label}" else option.label)
                            }
                        } else {
                            Button(
                                onClick = { if (!locked) onAnswer(option) },
                                enabled = !locked,
                                colors = colors,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(option.label)
                            }
                        }
                        if (index == 0 && pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (answeredChoice != null) {
                // One human sentence instead of transport chips (P4/P6 finding).
                Text(
                    when (delivery) {
                        DeliveryStatus.QUEUED, DeliveryStatus.SENDING -> "Sending to your agent…"
                        DeliveryStatus.DELIVERED -> "✓ Sent to agent: ${answeredChoice.optionLabel ?: answeredChoice.optionId ?: ""}"
                        DeliveryStatus.FAILED -> "Couldn't send — will retry when your agent checks in"
                        null -> "✓ Sent to agent: ${answeredChoice.optionLabel ?: answeredChoice.optionId ?: ""}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (delivery == DeliveryStatus.FAILED) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                )
            } else {
                DeliveryChip(delivery)
            }
            if (question.allowText && !answered) {
                var text by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(question.placeholder) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSendText(text); text = "" }, enabled = text.isNotBlank()) {
                    Text("Send reply")
                }
            }
        }
    }
}

@Composable
private fun DeliveryChip(delivery: DeliveryStatus?) {
    if (delivery == null) return
    val label = when (delivery) {
        DeliveryStatus.QUEUED -> "Queued"
        DeliveryStatus.SENDING -> "Sending…"
        DeliveryStatus.DELIVERED -> "Delivered to agent ✓"
        DeliveryStatus.FAILED -> "Rejected — agent will re-sync"
    }
    FilterChip(
        selected = false,
        onClick = {},
        label = { Text(label) },
    )
}

@Composable
private fun TextComposable(el: dev.slate.android.spec.TextElement) {
    val style = when (el.style) {
        SpecTextStyle.TITLE -> MaterialTheme.typography.titleLarge
        SpecTextStyle.HEADING -> MaterialTheme.typography.titleMedium
        SpecTextStyle.BODY -> MaterialTheme.typography.bodyLarge
        SpecTextStyle.CAPTION -> MaterialTheme.typography.bodySmall
    }
    Text(
        el.text,
        style = style,
        color = if (el.tone == dev.slate.android.spec.Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else toneColor(el.tone),
    )
}
