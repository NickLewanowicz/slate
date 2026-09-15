package dev.slate.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import dev.slate.android.di.AppContainer
import dev.slate.android.ui.SlateNavHost
import dev.slate.android.ui.theme.SlateTheme

/**
 * Single-activity Compose app. Deep links: slate://setup (optional
 * ?serverUrl=&apiKey= pre-fill) and slate://detail/{slateId} — used by the
 * widget's header / "reply in app" rows.
 */
class MainActivity : ComponentActivity() {

    val container: AppContainer by lazy { (application as SlateApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SlateTheme {
                SlateNavHost(container = container, prefill = prefillFromIntent(intent))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun prefillFromIntent(intent: Intent?): Pair<String?, String?>? {
        val data: Uri = intent?.data ?: return null
        if (data.host != "setup") return null
        // Accept both long and short query aliases: slate://setup?serverUrl=&apiKey=
        // and slate://setup?url=&key=.
        val r = Pair(
            data.getQueryParameter("serverUrl") ?: data.getQueryParameter("url"),
            data.getQueryParameter("apiKey") ?: data.getQueryParameter("key"),
        )
        android.util.Log.d("SlatePrefill", "activity prefill=$r data=$data")
        return r
    }
}

/** Exposed for previews/tests. */
@Composable
fun SlateApp(container: AppContainer) {
    SlateTheme {
        SlateNavHost(container = container)
    }
}
