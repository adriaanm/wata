package com.wata.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wata.audio.AudioService
import com.wata.client.ConnectionState
import com.wata.client.Contact
import com.wata.client.Conversation
import com.wata.client.Logger
import com.wata.client.VoiceMessage
import com.wata.client.WataClient
import com.wata.client.WataClientEvents
import com.wata.config.MatrixConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "WataViewModel"

/**
 * UI state for the contact list
 */
data class WataUiState(
    val connectionState: ConnectionState = ConnectionState.OFFLINE,
    val contacts: List<Contact> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentUserId: String? = null
)

/**
 * UI state for the chat screen
 */
data class ChatUiState(
    val roomId: String? = null,
    val contactUserId: String? = null,
    val messages: List<VoiceMessage> = emptyList(),
    val isRecording: Boolean = false,
    val recordingDuration: Long = 0,
    val playingMessageId: String? = null,
    val currentUserId: String? = null
)

/**
 * ViewModel that manages WataClient lifecycle and exposes state via StateFlow.
 *
 * Handles:
 * - Auto-login with hardcoded credentials
 * - Sync lifecycle (connect/disconnect)
 * - Contact list updates
 * - Connection state changes
 * - Voice message recording and playback
 */
class WataViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WataUiState())
    val uiState: StateFlow<WataUiState> = _uiState.asStateFlow()

    private val _chatState = MutableStateFlow(ChatUiState())
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    private val client: WataClient = WataClient(
        homeserverUrl = MatrixConfig.HOMESERVER_URL,
        logger = AndroidLogger()
    )

    private val audioService: AudioService = AudioService(application.applicationContext)
    private var recordingProgressJob: Job? = null
    private var messageRefreshJob: Job? = null

    private val eventHandler = object : WataClientEvents {
        override fun onConnectionStateChanged(state: ConnectionState) {
            Log.d(TAG, "Connection state changed: $state")
            _uiState.update { it.copy(connectionState = state) }
        }

        override fun onContactsUpdated(contacts: List<Contact>) {
            Log.d(TAG, "Contacts updated: ${contacts.size} contacts")
            _uiState.update { it.copy(contacts = contacts) }
        }

        override fun onMessageReceived(message: VoiceMessage, conversation: Conversation) {
            Log.d(TAG, "Message received: ${message.id} in ${conversation.id}")
            // If we're viewing this conversation, refresh messages
            if (_chatState.value.roomId == conversation.id) {
                refreshMessages()
            }
        }

        override fun onMessagePlayed(message: VoiceMessage, roomId: String) {
            Log.d(TAG, "Message played: ${message.id}")
        }
    }

    init {
        client.addEventHandler(eventHandler)
        autoLogin()
    }

    /**
     * Attempt auto-login with configured credentials
     */
    private fun autoLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting auto-login...")
                _uiState.update { it.copy(isLoading = true, error = null) }

                // Login
                client.login(
                    username = MatrixConfig.USERNAME,
                    password = MatrixConfig.PASSWORD,
                    deviceDisplayName = MatrixConfig.DEVICE_NAME
                )

                val user = client.getCurrentUser()
                Log.d(TAG, "Logged in as: ${user?.id}")

                _uiState.update { it.copy(currentUserId = user?.id) }

                // Start syncing
                client.connect()
                Log.d(TAG, "Sync started")

                _uiState.update { it.copy(isLoading = false) }

            } catch (e: Exception) {
                Log.e(TAG, "Auto-login failed", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Login failed"
                    )
                }
            }
        }
    }

    /**
     * Retry login after a failure
     */
    fun retry() {
        autoLogin()
    }

    /**
     * Get the WataClient instance for operations like sending messages
     */
    fun getClient(): WataClient = client

    /**
     * Logout and disconnect
     */
    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                client.disconnect()
                _uiState.update {
                    WataUiState(
                        connectionState = ConnectionState.OFFLINE,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logout failed", e)
            }
        }
    }

    // =========================================================================
    // Chat Methods
    // =========================================================================

    /**
     * Open a chat with a contact
     */
    fun openChat(contactUserId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Opening chat with: $contactUserId")

                // Get or create DM room
                val roomId = client.getDMRoomId(contactUserId)
                    ?: client.createDMRoom(contactUserId)

                Log.d(TAG, "DM room ID: $roomId")

                _chatState.update {
                    it.copy(
                        roomId = roomId,
                        contactUserId = contactUserId,
                        currentUserId = _uiState.value.currentUserId
                    )
                }

                // Load messages
                refreshMessages()

                // Start periodic message refresh while chat is open
                startMessageRefresh()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to open chat", e)
            }
        }
    }

    /**
     * Close the current chat
     */
    fun closeChat() {
        messageRefreshJob?.cancel()
        messageRefreshJob = null
        _chatState.value = ChatUiState()
    }

    /**
     * Refresh messages for current chat
     */
    private fun refreshMessages() {
        val roomId = _chatState.value.roomId ?: return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversation = client.getConversationByRoomId(roomId)
                if (conversation != null) {
                    _chatState.update { it.copy(messages = conversation.messages) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh messages", e)
            }
        }
    }

    /**
     * Start periodic message refresh
     */
    private fun startMessageRefresh() {
        messageRefreshJob?.cancel()
        messageRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(2000) // Refresh every 2 seconds
                refreshMessages()
            }
        }
    }

    // =========================================================================
    // Recording Methods
    // =========================================================================

    /**
     * Start PTT recording
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startRecording() {
        if (_chatState.value.roomId == null) {
            Log.w(TAG, "Cannot record: no active chat")
            return
        }

        try {
            audioService.startRecording()
            _chatState.update { it.copy(isRecording = true, recordingDuration = 0) }
            startRecordingProgress()
            Log.d(TAG, "Recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    /**
     * Stop PTT recording and send the message
     */
    fun stopRecordingAndSend() {
        val roomId = _chatState.value.roomId
        if (roomId == null) {
            Log.w(TAG, "Cannot send: no active chat")
            audioService.cancelRecording()
            return
        }

        recordingProgressJob?.cancel()
        recordingProgressJob = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = audioService.stopRecording()
                Log.d(TAG, "Recording stopped: ${result.duration}ms, ${result.size} bytes")

                _chatState.update { it.copy(isRecording = false, recordingDuration = 0) }

                // Send the voice message
                val durationSeconds = result.duration / 1000.0
                client.sendVoiceMessageFromFile(roomId, result.uri, durationSeconds)
                Log.d(TAG, "Voice message sent")

                // Refresh messages to show the new one
                refreshMessages()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send voice message", e)
                _chatState.update { it.copy(isRecording = false, recordingDuration = 0) }
            }
        }
    }

    /**
     * Cancel recording without sending
     */
    fun cancelRecording() {
        recordingProgressJob?.cancel()
        recordingProgressJob = null
        audioService.cancelRecording()
        _chatState.update { it.copy(isRecording = false, recordingDuration = 0) }
    }

    private fun startRecordingProgress() {
        recordingProgressJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (_chatState.value.isRecording) {
                val duration = System.currentTimeMillis() - startTime
                _chatState.update { it.copy(recordingDuration = duration) }
                delay(100)
            }
        }
    }

    // =========================================================================
    // Playback Methods
    // =========================================================================

    /**
     * Play or stop a voice message
     */
    fun playMessage(message: VoiceMessage) {
        val currentPlaying = _chatState.value.playingMessageId

        if (currentPlaying == message.id) {
            // Stop playing
            audioService.stopPlayback()
            _chatState.update { it.copy(playingMessageId = null) }
        } else {
            // Stop any current playback
            if (currentPlaying != null) {
                audioService.stopPlayback()
            }

            // Start playing new message
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Playing message: ${message.audioUrl}")
                    _chatState.update { it.copy(playingMessageId = message.id) }

                    // Download the audio file first if it's an MXC URL
                    val audioUri = if (message.audioUrl.startsWith("mxc://")) {
                        // Download to cache
                        val audioData = client.downloadMedia(message.mxcUrl)
                        val cacheFile = java.io.File(
                            getApplication<Application>().cacheDir,
                            "playback_${message.id}.ogg"
                        )
                        cacheFile.writeBytes(audioData)
                        cacheFile.absolutePath
                    } else {
                        message.audioUrl
                    }

                    audioService.startPlayback(audioUri)

                    // Mark as played
                    client.markAsPlayed(message)

                    // Monitor playback state
                    audioService.isPlaying.collect { isPlaying ->
                        if (!isPlaying && _chatState.value.playingMessageId == message.id) {
                            _chatState.update { it.copy(playingMessageId = null) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to play message", e)
                    _chatState.update { it.copy(playingMessageId = null) }
                }
            }
        }
    }

    /**
     * Format duration in seconds to M:SS
     */
    fun formatDuration(seconds: Double): String {
        val totalSeconds = seconds.toLong()
        val minutes = totalSeconds / 60
        val secs = totalSeconds % 60
        return "$minutes:${secs.toString().padStart(2, '0')}"
    }

    override fun onCleared() {
        super.onCleared()
        client.removeEventHandler(eventHandler)
        audioService.destroy()
        messageRefreshJob?.cancel()
        recordingProgressJob?.cancel()
        try {
            client.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting on clear", e)
        }
    }
}

/**
 * Logger implementation that uses Android Log
 */
private class AndroidLogger : Logger {
    override fun log(message: String) {
        Log.d(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    override fun error(message: String) {
        Log.e(TAG, message)
    }
}
