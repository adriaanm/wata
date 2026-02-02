package com.wata

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wata.ui.screens.ChatScreen
import com.wata.ui.screens.ContactListScreen
import com.wata.ui.theme.WataTheme
import com.wata.ui.viewmodel.WataViewModel
import java.net.URLDecoder
import java.net.URLEncoder

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: WataViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "RECORD_AUDIO permission granted")
        } else {
            Log.w(TAG, "RECORD_AUDIO permission denied")
        }
    }

    companion object {
        // KEYCODE_PTT = 79, defined in KeyEvent but not always accessible
        private const val KEYCODE_PTT = 79
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request audio permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            WataTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WataApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "KeyDown: ${keyCodeToName(keyCode)} ($keyCode)")

        // Capture PTT button (KEYCODE_PTT = 79)
        if (keyCode == KEYCODE_PTT) {
            if (hasRecordPermission()) {
                viewModel.startRecording()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "KeyUp: ${keyCodeToName(keyCode)} ($keyCode)")

        if (keyCode == KEYCODE_PTT) {
            viewModel.stopRecordingAndSend()
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
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
fun WataApp(viewModel: WataViewModel) {
    val navController = rememberNavController()

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

            ChatScreen(
                viewModel = viewModel,
                contactUserId = userId,
                contactName = userName,
                onBack = {
                    viewModel.closeChat()
                    navController.popBackStack()
                }
            )
        }
    }
}
