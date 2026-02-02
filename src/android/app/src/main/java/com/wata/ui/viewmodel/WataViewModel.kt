package com.wata.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wata.client.ConnectionState
import com.wata.client.Contact
import com.wata.client.Conversation
import com.wata.client.Logger
import com.wata.client.VoiceMessage
import com.wata.client.WataClient
import com.wata.client.WataClientEvents
import com.wata.config.MatrixConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "WataViewModel"

/**
 * UI state for the Wata app
 */
data class WataUiState(
    val connectionState: ConnectionState = ConnectionState.OFFLINE,
    val contacts: List<Contact> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
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
 */
class WataViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(WataUiState())
    val uiState: StateFlow<WataUiState> = _uiState.asStateFlow()

    private val client: WataClient = WataClient(
        homeserverUrl = MatrixConfig.HOMESERVER_URL,
        logger = AndroidLogger()
    )

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
            // UI will be notified via conversation updates
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

    override fun onCleared() {
        super.onCleared()
        client.removeEventHandler(eventHandler)
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
