package io.github.jingrongx.screenmirror

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.jingrongx.screenmirror.ui.ClientScreen
import io.github.jingrongx.screenmirror.ui.HomeScreen
import io.github.jingrongx.screenmirror.ui.HostScreen
import io.github.jingrongx.screenmirror.ui.SettingsScreen
import io.github.jingrongx.screenmirror.ui.UpdateDialog
import io.github.jingrongx.screenmirror.ui.theme.ScreenMirrorTheme
import io.github.jingrongx.screenmirror.update.UpdateController

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScreenMirrorTheme {
                AppRoot()
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Host : Screen
    data object Client : Screen
    data object Settings : Screen
}

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val appContext = LocalContext.current.applicationContext
    val updateController = remember { UpdateController(appContext) }

    // 每天自动静默检查一次更新（发现新版本才弹窗）
    LaunchedEffect(Unit) {
        if (UpdateController.shouldAutoCheck(appContext)) {
            updateController.check(showResult = false)
        }
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    UpdateDialog(updateController)

    when (screen) {
        Screen.Home -> HomeScreen(
            onHost = { screen = Screen.Host },
            onClient = { screen = Screen.Client },
            onSettings = { screen = Screen.Settings },
        )

        Screen.Host -> HostScreen(onBack = { screen = Screen.Home })
        Screen.Client -> ClientScreen(onBack = { screen = Screen.Home })
        Screen.Settings -> SettingsScreen(
            onBack = { screen = Screen.Home },
            updateController = updateController,
        )
    }
}
