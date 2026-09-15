package dev.slate.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.slate.android.data.SetupField
import dev.slate.android.ui.SettingsViewModel

private val INTERVAL_CHOICES = listOf(5, 10, 15, 30, 60)

/** Settings: sync interval, re-edit server url/key, about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sync interval", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVAL_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = state.syncIntervalMinutes == minutes,
                        onClick = { viewModel.setInterval(minutes) },
                        label = { Text("${minutes}m") },
                    )
                }
            }
            Text(
                "Widgets also refresh on every app open, pull-to-refresh and right after taps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Connection", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = { viewModel.editConnectionPreview(it, state.apiKey) },
                label = { Text("Server URL") },
                isError = state.fieldErrors.containsKey(SetupField.SERVER_URL),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = { viewModel.editConnectionPreview(state.serverUrl, it) },
                label = { Text("API key") },
                isError = state.fieldErrors.containsKey(SetupField.API_KEY),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { viewModel.saveConnection(state.serverUrl, state.apiKey) }) {
                Text("Save connection")
            }
            if (state.saved) Text("Saved ✓", color = MaterialTheme.colorScheme.primary)

            Text("About", style = MaterialTheme.typography.titleSmall)
            Text(
                "Slate v2 — the home-screen surface where your agent asks and answers. " +
                    "No push infrastructure: WorkManager syncs every few minutes (and 20s while you're looking), " +
                    "and your taps queue durably until the server answers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
