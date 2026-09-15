package dev.slate.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.slate.android.data.SlatesUiState
import dev.slate.android.data.SyncState
import dev.slate.android.ui.theme.toneColor

/** Slates list: cards with tone accent, relative updatedAt, open-question badges. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlatesListScreen(
    viewModel: dev.slate.android.ui.SlatesListViewModel,
    onOpenSlate: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sync by viewModel.syncState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Slate") },
                actions = {
                    // Pin-widget fallback for launchers that don't offer the
                    // widget picker inline (requestPinAppWidget, API 26+).
                    IconButton(
                        onClick = {
                            val manager = android.appwidget.AppWidgetManager.getInstance(context)
                                ?: return@IconButton
                            val provider = android.content.ComponentName(
                                context,
                                dev.slate.android.widget.SlateWidgetProvider::class.java,
                            )
                            if (manager.isRequestPinAppWidgetSupported) {
                                runCatching { manager.requestPinAppWidget(provider, null, null) }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Widgets, contentDescription = "Pin widget to home screen")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
            if (!ui.configured) {
                EmptyState(
                    title = "Almost there",
                    body = "Add your server and API key so your agents can reach you.",
                )
            } else if (ui.cards.isEmpty()) {
                EmptyState(
                    title = "All quiet.",
                    body = "Your agents have nothing to report — enjoy it.",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(ui.cards, key = { it.slateId }) { card ->
                        SlateCard(card = card, onClick = { onOpenSlate(card.slateId) })
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SlateCard(
    card: dev.slate.android.data.SlateCardUi,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(toneColor(card.tone)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(dev.slate.android.widget.FreshnessFormatter.relativeIso(card.updatedAt, System.currentTimeMillis()))
                        append(" · ")
                        append(card.slateId)
                        if (card.expired) append(" · stale")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.openQuestions > 0) {
                AssistChip(
                    onClick = onClick,
                    label = { Text("${card.openQuestions} question${if (card.openQuestions == 1) "" else "s"}") },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
