package com.wata.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wata.client.ConnectionState
import com.wata.client.Contact
import com.wata.ui.components.FocusableSurface
import com.wata.ui.theme.WataColors
import com.wata.ui.theme.WataSpacing
import com.wata.ui.theme.WataTypography
import com.wata.ui.viewmodel.WataUiState
import com.wata.ui.viewmodel.WataViewModel

/**
 * Contact list screen showing all DM contacts.
 *
 * Displays:
 * - Header with title and exit button
 * - Loading state while syncing
 * - Empty state when no contacts
 * - List of contacts with D-pad navigation
 */
@Composable
fun ContactListScreen(
    viewModel: WataViewModel,
    onSelectContact: (contactUserId: String, contactName: String) -> Unit,
    onExit: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WataColors.bg)
    ) {
        // Header
        ContactListHeader(onExit = onExit)

        // Content
        when {
            uiState.isLoading -> LoadingContent(uiState)
            uiState.error != null -> ErrorContent(
                error = uiState.error!!,
                onRetry = { viewModel.retry() }
            )
            uiState.contacts.isEmpty() -> EmptyContent()
            else -> ContactList(
                contacts = uiState.contacts,
                contactMessageStatus = uiState.contactMessageStatus,
                onSelectContact = onSelectContact
            )
        }
    }
}

@Composable
private fun ContactListHeader(onExit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WataColors.bgSecondary)
            .padding(horizontal = WataSpacing.md, vertical = WataSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Contacts",
            style = WataTypography.header
        )

        FocusableSurface(
            onClick = onExit,
            backgroundColor = WataColors.bgSecondary,
            focusedBackgroundColor = WataColors.bgHighlight,
            modifier = Modifier.padding(0.dp)
        ) {
            Text(
                text = "Exit",
                style = WataTypography.body.copy(color = WataColors.primary)
            )
        }
    }
}

@Composable
private fun LoadingContent(uiState: WataUiState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = WataColors.primary
            )
            Spacer(modifier = Modifier.height(WataSpacing.md))
            Text(
                text = "Syncing...",
                style = WataTypography.body
            )
            Spacer(modifier = Modifier.height(WataSpacing.xs))
            Text(
                text = connectionStateText(uiState.connectionState),
                style = WataTypography.small
            )
        }
    }
}

@Composable
private fun ErrorContent(error: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Connection failed",
                style = WataTypography.header.copy(color = WataColors.error)
            )
            Spacer(modifier = Modifier.height(WataSpacing.sm))
            Text(
                text = error,
                style = WataTypography.small
            )
            Spacer(modifier = Modifier.height(WataSpacing.lg))
            FocusableSurface(
                onClick = onRetry,
                backgroundColor = WataColors.primary,
                focusedBackgroundColor = WataColors.primaryDark
            ) {
                Text(
                    text = "Retry",
                    style = WataTypography.large
                )
            }
        }
    }
}

@Composable
private fun EmptyContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No contacts",
                style = WataTypography.body.copy(color = WataColors.textSecondary)
            )
            Spacer(modifier = Modifier.height(WataSpacing.xs))
            Text(
                text = "Start chat in Element",
                style = WataTypography.small
            )
        }
    }
}

@Composable
private fun ContactList(
    contacts: List<Contact>,
    contactMessageStatus: Map<String, com.wata.ui.viewmodel.ContactMessageStatus>,
    onSelectContact: (contactUserId: String, contactName: String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(WataSpacing.sm)
    ) {
        items(
            items = contacts,
            key = { it.user.id }
        ) { contact ->
            val status = contactMessageStatus[contact.user.id]
            ContactItem(
                contact = contact,
                hasUnplayed = status?.unplayedCount ?: 0 > 0,
                hasFailed = status?.failedCount ?: 0 > 0,
                onClick = {
                    onSelectContact(
                        contact.user.id,
                        contact.user.displayName ?: contact.user.id
                    )
                }
            )
            Spacer(modifier = Modifier.height(WataSpacing.xs))
        }
    }
}

@Composable
private fun ContactItem(
    contact: Contact,
    hasUnplayed: Boolean,
    hasFailed: Boolean,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.user.displayName ?: contact.user.id,
                    style = WataTypography.large.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Status indicators
            if (hasFailed || hasUnplayed) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.width(WataSpacing.lg)
                ) {
                    // Red dot for failed messages (shown first/leftmost)
                    if (hasFailed) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(WataColors.error)
                        )
                    }
                    // Blue dot for unplayed messages (shown after failed)
                    if (hasUnplayed) {
                        if (hasFailed) {
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(WataColors.primary)
                        )
                    }
                }
            }
        }
    }
}

private fun connectionStateText(state: ConnectionState): String {
    return when (state) {
        ConnectionState.OFFLINE -> "Offline"
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.SYNCING -> "Syncing..."
        ConnectionState.ERROR -> "Error"
    }
}
