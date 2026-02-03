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
 * Message status for a single contact
 */
data class ContactMessageStatus(
    val unplayedCount: Int = 0,  // Unplayed incoming messages
    val failedCount: Int = 0     // Failed outgoing messages
)

/**
 * UI state for the contact list
 */
data class WataUiState(
    val connectionState: ConnectionState = ConnectionState.OFFLINE,
    val contacts: List<Contact> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentUserId: String? = null,
    // Per-contact message status (contactUserId -> status)
    val contactMessageStatus: Map<String, ContactMessageStatus> = emptyMap()
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
            val currentSize = _chatState.value.messages.size
            val currentIds = _chatState.value.messages.take(3).map { it.id.takeLast(8) } + _chatState.value.messages.takeLast(3).map { it.id.takeLast(8) }
            Log.d(TAG, "onMessageReceived: msg=${message.id.takeLast(8)}, conv=${conversation.id.takeLast(8)}, currentRoomId=${_chatState.value.roomId?.takeLast(8)}, currentSize=$currentSize, ids=$currentIds")

            // Update contact message status for the contact list
            val contactUserId = conversation.contact?.user?.id
            if (contactUserId != null) {
                val isFromMe = message.sender.id == _uiState.value.currentUserId
                _uiState.update { state ->
                    val currentStatus = state.contactMessageStatus[contactUserId] ?: ContactMessageStatus()
                    val newStatus = when {
                        // Outgoing message that failed
                        isFromMe && message.failed -> currentStatus.copy(
                            failedCount = currentStatus.failedCount + 1
                        )
                        // Incoming message that's unplayed
                        !isFromMe && !message.isPlayed -> currentStatus.copy(
                            unplayedCount = currentStatus.unplayedCount + 1
                        )
                        else -> currentStatus
                    }
                    state.copy(
                        contactMessageStatus = state.contactMessageStatus + (contactUserId to newStatus)
                    )
                }
            }

            // Once we have an active chat (roomId is set), add messages for that room to state.
            // This ensures we don't miss messages that arrive before/during openChat's async setup.
            // The UI will only display messages for the current roomId anyway.
            if (_chatState.value.roomId != null && _chatState.value.roomId == conversation.id) {
                _chatState.update { state ->
                    // Add message if not already present
                    if (state.messages.none { it.id == message.id }) {
                        val newSize = state.messages.size + 1
                        Log.d(TAG, "Adding message to chat state: ${message.id.takeLast(8)}, size: $currentSize -> $newSize")
                        state.copy(messages = state.messages + message)
                    } else {
                        Log.d(TAG, "Message already in state, skipping: ${message.id.takeLast(8)}")
                        state
                    }
                }
            } else {
                Log.d(TAG, "Ignoring message for different room (or no room set): roomId=${_chatState.value.roomId?.takeLast(8)}, msgConv=${conversation.id.takeLast(8)}")
            }
        }

        override fun onMessagePlayed(message: VoiceMessage, roomId: String) {
            Log.d(TAG, "Message played: ${message.id}")

            // Update contact message status - decrement unplayed count for the sender
            val isFromMe = message.sender.id == _uiState.value.currentUserId
            if (!isFromMe) {
                _uiState.update { state ->
                    val currentStatus = state.contactMessageStatus[message.sender.id] ?: ContactMessageStatus()
                    val newStatus = currentStatus.copy(
                        unplayedCount = maxOf(0, currentStatus.unplayedCount - 1)
                    )
                    state.copy(
                        contactMessageStatus = state.contactMessageStatus + (message.sender.id to newStatus)
                    )
                }
            }

            // If we're viewing this conversation, update the message's played status
            if (_chatState.value.roomId == roomId) {
                _chatState.update { state ->
                    state.copy(
                        messages = state.messages.map { msg ->
                            if (msg.id == message.id) {
                                msg.copy(
                                    isPlayed = message.isPlayed,
                                    playedBy = message.playedBy
                                )
                            } else {
                                msg
                            }
                        }
                    )
                }
            }
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
                Log.d(TAG, "[openChat] Starting openChat for: $contactUserId, current roomId=${_chatState.value.roomId}")

                // Clear unplayed count when opening chat (user will see messages)
                _uiState.update { state ->
                    val currentStatus = state.contactMessageStatus[contactUserId]
                    if (currentStatus != null && currentStatus.unplayedCount > 0) {
                        val newStatus = currentStatus.copy(unplayedCount = 0)
                        state.copy(
                            contactMessageStatus = state.contactMessageStatus + (contactUserId to newStatus)
                        )
                    } else {
                        state
                    }
                }

                // Get or create DM room
                val roomId = client.getDMRoomId(contactUserId)
                    ?: client.createDMRoom(contactUserId)

                Log.d(TAG, "[openChat] Got DM room ID: $roomId")

                _chatState.update {
                    it.copy(
                        roomId = roomId,
                        contactUserId = contactUserId,
                        currentUserId = _uiState.value.currentUserId
                    )
                }

                Log.d(TAG, "[openChat] Set roomId in chatState to: ${_chatState.value.roomId}")

                // Load initial messages
                refreshMessages()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to open chat", e)
            }
        }
    }

    /**
     * Close the current chat
     */
    fun closeChat() {
        _chatState.value = ChatUiState()
    }

    /**
     * Refresh messages for current chat (called once on open).
     * New messages arrive reactively via onMessageReceived.
     */
    private fun refreshMessages() {
        val roomId = _chatState.value.roomId ?: return
        Log.d(TAG, "[refreshMessages] Refreshing messages for room: $roomId")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversation = client.getConversationByRoomId(roomId)
                if (conversation != null) {
                    Log.d(TAG, "[refreshMessages] Got ${conversation.messages.size} messages, replacing current ${_chatState.value.messages.size}")
                    _chatState.update { it.copy(messages = conversation.messages) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh messages", e)
            }
        }
    }

    // =========================================================================
    // Recording Methods
    // =========================================================================

    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = _chatState.value.isRecording

    /**
     * Start PTT recording
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startRecording() {
        // Guard against key repeat events
        if (_chatState.value.isRecording) {
            return
        }

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
                    Log.d(TAG, "Playing message: mxcUrl=${message.mxcUrl}")
                    _chatState.update { it.copy(playingMessageId = message.id) }

                    // Always download from Matrix server using mxcUrl
                    val cacheFile = java.io.File(
                        getApplication<Application>().cacheDir,
                        "playback_${message.id}.ogg"
                    )

                    // Download if not cached
                    if (!cacheFile.exists()) {
                        Log.d(TAG, "Downloading audio to cache: ${cacheFile.absolutePath}")
                        val audioData = client.downloadMedia(message.mxcUrl)
                        cacheFile.writeBytes(audioData)
                    }

                    val audioUri = cacheFile.absolutePath

                    audioService.startPlayback(audioUri)

                    // Mark as played
                    client.markAsPlayed(message)

                    // Monitor playback state - wait for playback to actually start,
                    // then clear when it stops
                    var wasPlaying = false
                    audioService.isPlaying.collect { isPlaying ->
                        if (isPlaying) {
                            wasPlaying = true
                        } else if (wasPlaying && _chatState.value.playingMessageId == message.id) {
                            // Only clear when transitioning from playing to stopped
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

    /**
     * Format timestamp to friendly relative time
     * "now", "X min ago", "X hours ago", or date/time for older
     */
    fun formatTimestamp(timestamp: java.util.Date): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp.time
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> "now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> {
                val format = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
                format.format(timestamp)
            }
            else -> {
                val format = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                format.format(timestamp)
            }
        }
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
