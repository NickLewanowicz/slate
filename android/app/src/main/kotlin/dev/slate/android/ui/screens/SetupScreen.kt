package dev.slate.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.slate.android.ui.SetupViewModel
import dev.slate.android.ui.TestResult

/**
 * Onboarding: server URL + API key, Test connection (GET /api/ping), save.
 * Deep link slate://setup?serverUrl=&apiKey= pre-fills the fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    prefill: Pair<String?, String?>? = null,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(prefill) {
        prefill?.let { (url, key) -> viewModel.prefill(url, key) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Welcome to Slate") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "The home-screen surface where your agent asks and answers.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Point Slate at your self-hosted backend once — widgets, questions " +
                    "and status boards land on your home screen. Your agents do the rest.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::editServerUrl,
                label = { Text("Server URL") },
                placeholder = { Text("http://10.0.2.2:3000") },
                isError = state.fieldErrors.containsKey(dev.slate.android.data.SetupField.SERVER_URL),
                supportingText = {
                    state.fieldErrors[dev.slate.android.data.SetupField.SERVER_URL]?.let { Text(it) }
                        ?: Text("Two lines from whoever runs your agent")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::editApiKey,
                label = { Text("API key") },
                isError = state.fieldErrors.containsKey(dev.slate.android.data.SetupField.API_KEY),
                supportingText = {
                    state.fieldErrors[dev.slate.android.data.SetupField.API_KEY]?.let { Text(it) }
                        ?: Text("Treat this like a password")
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = viewModel::testConnection, enabled = !state.testing, modifier = Modifier.fillMaxWidth()) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).padding(end = 8.dp))
                }
                Text("Test connection")
            }

            when (val t = state.test) {
                is TestResult.Success -> Text(
                    "Connected ✓ ${t.version?.let { "· $it" } ?: ""}",
                    color = MaterialTheme.colorScheme.primary,
                )
                is TestResult.Failure -> Text(
                    t.message,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {}
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.save(onDone) },
                enabled = state.test is TestResult.Success || state.test == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save & open Slate")
            }
            if (dev.slate.android.BuildConfig.DEBUG) {
                Text(
                    "By the way: the emulator reaches your machine at 10.0.2.2.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
