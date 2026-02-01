package com.wata

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wata.ui.theme.WataTheme

class MainActivity : ComponentActivity() {
    private var lastKeyEvent by mutableStateOf<String?>(null)

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
                    WataApp(lastKeyEvent = lastKeyEvent)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        lastKeyEvent = "DOWN: ${keyCodeToName(keyCode)} ($keyCode)"

        // Capture PTT button (KEYCODE_PTT = 79)
        if (keyCode == KEYCODE_PTT) {
            // TODO: Start recording
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        lastKeyEvent = "UP: ${keyCodeToName(keyCode)} ($keyCode)"

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

@Composable
fun WataApp(lastKeyEvent: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Wata",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Native Kotlin",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp)
            )

            if (lastKeyEvent != null) {
                Text(
                    text = lastKeyEvent,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
        }
    }
}
