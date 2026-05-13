package com.mira.screening.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mira.screening.data.Prefs
import com.mira.screening.ui.screens.CHWAssistantScreen
import com.mira.screening.ui.screens.CaptureScreen
import com.mira.screening.ui.screens.HistoryDetailScreen
import com.mira.screening.ui.screens.HistoryScreen
import com.mira.screening.ui.screens.HomeScreen
import com.mira.screening.ui.screens.OnboardingScreen
import com.mira.screening.ui.screens.PreCaptureScreen
import com.mira.screening.ui.screens.ProcessingScreen
import com.mira.screening.ui.screens.ResultScreen
import com.mira.screening.ui.screens.SettingsScreen

object Routes {
    const val Home = "home"
    const val PreCapture = "pre_capture"
    const val Capture = "capture"
    const val Processing = "processing/{captureId}"
    const val Result = "result/{captureId}"
    const val History = "history"
    const val HistoryDetail = "history/{recordId}"
    const val Settings = "settings"
    const val Onboarding = "onboarding"
    const val Assistant = "assistant"

    fun processing(captureId: String) = "processing/$captureId"
    fun result(captureId: String) = "result/$captureId"
    fun historyDetail(recordId: String) = "history/$recordId"
}

@Composable
fun MiraNavHost() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val navController = rememberNavController()
    val start = if (prefs.onboardingComplete) Routes.Home else Routes.Onboarding

    NavHost(
        navController = navController,
        startDestination = start
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(
                onDone = {
                    prefs.onboardingComplete = true
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.Home) {
            HomeScreen(
                onStartScreening = { navController.navigate(Routes.PreCapture) },
                onOpenHistory = { navController.navigate(Routes.History) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenAssistant = { navController.navigate(Routes.Assistant) }
            )
        }
        composable(Routes.PreCapture) {
            PreCaptureScreen(
                onContinue = { navController.navigate(Routes.Capture) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.Capture) {
            CaptureScreen(
                onCaptured = { id ->
                    navController.navigate(Routes.processing(id)) {
                        popUpTo(Routes.Capture) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.Processing,
            arguments = listOf(navArgument("captureId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("captureId") ?: return@composable
            ProcessingScreen(
                captureId = id,
                onDone = { resultId ->
                    navController.navigate(Routes.result(resultId)) {
                        popUpTo(Routes.Home)
                    }
                },
                onError = {
                    navController.popBackStack(Routes.Home, inclusive = false)
                }
            )
        }
        composable(
            route = Routes.Result,
            arguments = listOf(navArgument("captureId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("captureId") ?: return@composable
            ResultScreen(
                captureId = id,
                onDone = {
                    navController.popBackStack(Routes.Home, inclusive = false)
                }
            )
        }
        composable(Routes.History) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenRecord = { id -> navController.navigate(Routes.historyDetail(id)) }
            )
        }
        composable(
            route = Routes.HistoryDetail,
            arguments = listOf(navArgument("recordId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("recordId") ?: return@composable
            HistoryDetailScreen(
                recordId = id,
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() }
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Assistant) {
            CHWAssistantScreen(onBack = { navController.popBackStack() })
        }
    }
}
