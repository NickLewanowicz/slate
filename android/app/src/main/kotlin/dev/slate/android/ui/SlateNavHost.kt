package dev.slate.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import kotlinx.coroutines.flow.map
import dev.slate.android.di.AppContainer
import dev.slate.android.ui.screens.SettingsScreen
import dev.slate.android.ui.screens.SetupScreen
import dev.slate.android.ui.screens.SlateDetailScreen
import dev.slate.android.ui.screens.SlatesListScreen
import dev.slate.android.ui.theme.SlateMotion

/** Routes */
const val ROUTE_SETUP = "setup"
const val ROUTE_LIST = "list"
const val ROUTE_DETAIL = "detail/{slateId}"
const val ROUTE_SETTINGS = "settings"

/** Shared-axis-ish transitions: slide + fade on FastOutSlowIn. */
private fun enter() = slideInHorizontally(tween(SlateMotion.DURATION_MS, easing = FastOutSlowInEasing)) { it / 4 } +
    fadeIn(tween(SlateMotion.DURATION_MS, easing = FastOutSlowInEasing))
private fun exit() = slideOutHorizontally(tween(SlateMotion.DURATION_MS, easing = FastOutSlowInEasing)) { -it / 6 } +
    fadeOut(tween(SlateMotion.DURATION_MS, easing = FastOutSlowInEasing))
private fun popEnter() = slideInHorizontally(tween(SlateMotion.DURATION_MS, easing = FastOutSlowInEasing)) { -it / 6 } +
    fadeIn(tween(SlateMotion.DURATION_MS, easing = FastOutSlowInEasing))
private fun popExit() = slideOutHorizontally(tween(SlateMotion.DURATION_MS, easing = FastOutSlowInEasing)) { it / 4 } +
    fadeOut(tween(SlateMotion.DURATION_MS, easing = FastOutSlowInEasing))

@Composable
fun SlateNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
    prefill: Pair<String?, String?>? = null,
) {
    NavHost(
        navController = navController,
        startDestination = ROUTE_LIST,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
        modifier = Modifier,
    ) {
        composable(
            ROUTE_SETUP,
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "slate://setup?serverUrl={serverUrl}&apiKey={apiKey}"
                }
            ),
            arguments = listOf(
                navArgument("serverUrl") { nullable = true; defaultValue = null },
                navArgument("apiKey") { nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val prefillFromLink = Pair(
                entry.arguments?.getString("serverUrl"),
                entry.arguments?.getString("apiKey"),
            )
            // Activity-level prefill carries the ?url=&key= aliases; merge per-field
            // so a link with only one param still fills the other from the activity.
            val mergedPrefill = Pair(
                prefillFromLink.first ?: prefill?.first,
                prefillFromLink.second ?: prefill?.second,
            )
            android.util.Log.d("SlatePrefill", "nav merged=$mergedPrefill link=$prefillFromLink activity=$prefill")
            val vm: SetupViewModel = viewModel(factory = SlateVmFactory(container), key = "setup")
            SetupScreen(
                viewModel = vm,
                prefill = mergedPrefill,
                onDone = {
                    navController.navigate(ROUTE_LIST) { popUpTo(ROUTE_SETUP) { inclusive = true } }
                },
            )
        }

        composable(ROUTE_LIST) {
            val vm: SlatesListViewModel = viewModel(factory = SlateVmFactory(container), key = "list")
            // Not configured yet? Straight to setup (deep links can override).
            // Keyed off the settings flow DIRECTLY: null = not yet read (never bounce
            // on the initial emission race), false = genuinely unconfigured.
            val configured: Boolean? by container.settingsStore.settings
                .map { it.isConfigured }
                .collectAsStateWithLifecycle(initialValue = null)
            androidx.compose.runtime.LaunchedEffect(configured) {
                if (configured == false) {
                    navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                }
            }
            SlatesListScreen(
                viewModel = vm,
                onOpenSlate = { id -> navController.navigate("detail/$id") },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }

        composable(
            ROUTE_DETAIL,
            arguments = listOf(navArgument("slateId") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "slate://detail/{slateId}" }),
        ) { entry ->
            val slateId = entry.arguments?.getString("slateId") ?: "home"
            val vm: SlateDetailViewModel = viewModel(
                factory = SlateVmFactory(container, slateId),
                key = "detail/$slateId",
            )
            SlateDetailScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(ROUTE_SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = SlateVmFactory(container), key = "settings")
            SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}
