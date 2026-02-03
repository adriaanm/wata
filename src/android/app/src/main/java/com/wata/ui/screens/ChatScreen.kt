package com.wata.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wata.client.VoiceMessage
import com.wata.ui.components.FocusableSurface
import com.wata.ui.theme.WataColors
import com.wata.ui.theme.WataSpacing
import com.wata.ui.theme.WataTypography
import com.wata.ui.viewmodel.ChatUiState
import com.wata.ui.viewmodel.WataViewModel

/**
 * Chat screen for voice message conversation with a contact.
 *
 * Features:
 * - Header with back button and contact name
 * - Recording status bar with pulse animation
 * - Voice message list with playback
 * - D-pad navigation
 */
@Composable
fun ChatScreen(
    viewModel: WataViewModel,
    contactUserId: String,
    contactName: String,
    onBack: () -> Unit
) {
    val chatState by viewModel.chatState.collectAsState()
    val listState = rememberLazyListState()

    // Load conversation when screen opens
    LaunchedEffect(contactUserId) {
        viewModel.openChat(contactUserId)
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(chatState.messages.size) {
        if (chatState.messages.isNotEmpty()) {
            listState.animateScrollToItem(chatState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WataColors.bg)
    ) {
        // Header
        ChatHeader(
            contactName = contactName,
            onBack = onBack
        )

        // Recording/Status bar
        if (chatState.isRecording) {
            RecordingBar(duration = chatState.recordingDuration)
        } else {
            StatusBar()
        }

        // Message list
        if (chatState.messages.isEmpty()) {
            EmptyMessages()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WataSpacing.sm)
            ) {
                items(
                    items = chatState.messages,
                    key = { it.id }
                ) { message ->
                    MessageItem(
                        message = message,
                        isPlaying = chatState.playingMessageId == message.id,
                        currentUserId = chatState.currentUserId,
                        onPlay = { viewModel.playMessage(message) },
                        formatDuration = { viewModel.formatDuration(it) }
                    )
                    Spacer(modifier = Modifier.height(WataSpacing.xs))
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(
    contactName: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WataColors.bgSecondary)
            .padding(horizontal = WataSpacing.sm, vertical = WataSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FocusableSurface(
            onClick = onBack,
            backgroundColor = WataColors.bgSecondary,
            focusedBackgroundColor = WataColors.bgHighlight,
            modifier = Modifier.padding(0.dp)
        ) {
            Text(
                text = "<",
                style = WataTypography.large.copy(
                    color = WataColors.primary,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = Modifier.width(WataSpacing.sm))

        Text(
            text = contactName,
            style = WataTypography.header,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RecordingBar(duration: Long) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WataColors.recording)
            .padding(vertical = WataSpacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pulsing record indicator dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .alpha(alpha)
                .clip(CircleShape)
                .background(WataColors.text)
        )
        Spacer(modifier = Modifier.width(WataSpacing.sm))
        // Stable duration text
        Text(
            text = "REC ${formatDurationMs(duration)}",
            style = WataTypography.status
        )
    }
}

@Composable
private fun StatusBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(WataColors.bgSecondary)
            .padding(vertical = WataSpacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "PTT to record",
            style = WataTypography.small
        )
    }
}

@Composable
private fun EmptyMessages() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No messages",
            style = WataTypography.body.copy(color = WataColors.textMuted)
        )
    }
}

@Composable
private fun MessageItem(
    message: VoiceMessage,
    isPlaying: Boolean,
    currentUserId: String?,
    onPlay: () -> Unit,
    formatDuration: (Double) -> String
) {
    val isOwn = message.sender.id == currentUserId

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        FocusableSurface(
            onClick = onPlay,
            backgroundColor = if (isOwn) WataColors.primary else WataColors.bgSecondary,
            focusedBackgroundColor = if (isOwn) WataColors.primaryDark else WataColors.bgHighlight,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) WataColors.playing
                            else WataColors.textSecondary
                        )
                )

                Spacer(modifier = Modifier.width(WataSpacing.sm))

                // Duration
                Text(
                    text = formatDuration(message.duration),
                    style = WataTypography.body
                )

                Spacer(modifier = Modifier.width(WataSpacing.sm))

                // Sender name
                Text(
                    text = if (isOwn) "You" else (message.sender.displayName ?: message.sender.id),
                    style = WataTypography.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
