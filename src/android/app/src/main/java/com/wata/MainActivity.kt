package com.wata

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wata.ui.screens.ContactListScreen
import com.wata.ui.theme.WataTheme
import com.wata.ui.viewmodel.WataViewModel
import java.net.URLDecoder
import java.net.URLEncoder

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    companion object {
        // KEYCODE_PTT = 79, defined in KeyEvent but not always accessible
        private const val KEYCODE_PTT = 79
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WataTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WataApp()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "KeyDown: ${keyCodeToName(keyCode)} ($keyCode)")

        // Capture PTT button (KEYCODE_PTT = 79)
        if (keyCode == KEYCODE_PTT) {
            // TODO: Start recording
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "KeyUp: ${keyCodeToName(keyCode)} ($keyCode)")

        if (keyCode == KEYCODE_PTT) {
            // TODO: Stop recording and send
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    private fun keyCodeToName(keyCode: Int): String = when (keyCode) {
        KEYCODE_PTT -> "PTT"
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        KeyEvent.KEYCODE_DPAD_CENTER -> "CENTER"
        KeyEvent.KEYCODE_MENU -> "MENU"
        KeyEvent.KEYCODE_BACK -> "BACK"
        KeyEvent.KEYCODE_ENTER -> "ENTER"
        else -> "KEY_$keyCode"
    }
}

/**
 * Navigation routes
 */
object Routes {
    const val CONTACT_LIST = "contacts"
    const val CHAT = "chat/{userId}/{userName}"

    fun chat(userId: String, userName: String): String {
        val encodedUserId = URLEncoder.encode(userId, "UTF-8")
        val encodedUserName = URLEncoder.encode(userName, "UTF-8")
        return "chat/$encodedUserId/$encodedUserName"
    }
}

@Composable
fun WataApp() {
    val navController = rememberNavController()
    val viewModel: WataViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Routes.CONTACT_LIST
    ) {
        composable(Routes.CONTACT_LIST) {
            ContactListScreen(
                viewModel = viewModel,
                onSelectContact = { userId, userName ->
                    navController.navigate(Routes.chat(userId, userName))
                },
                onExit = {
                    viewModel.logout()
                    // In a real app, might finish() the activity
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("userId") { type = NavType.StringType },
                navArgument("userName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val userId = URLDecoder.decode(
                backStackEntry.arguments?.getString("userId") ?: "",
                "UTF-8"
            )
            val userName = URLDecoder.decode(
                backStackEntry.arguments?.getString("userName") ?: "",
                "UTF-8"
            )

            // TODO: ChatScreen will be implemented in Phase 3.3
            // For now, show a placeholder
            ChatScreenPlaceholder(
                userId = userId,
                userName = userName,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun ChatScreenPlaceholder(
    userId: String,
    userName: String,
    onBack: () -> Unit
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        androidx.compose.material3.Text(
            text = "Chat with $userName",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.fillMaxSize().weight(0.1f)
        )
        androidx.compose.material3.Text(
            text = "(Phase 3.3)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.fillMaxSize().weight(0.1f)
        )
        com.wata.ui.components.FocusableSurface(
            onClick = onBack
        ) {
            androidx.compose.material3.Text(
                text = "Back",
                color = com.wata.ui.theme.WataColors.primary
            )
        }
    }
}
