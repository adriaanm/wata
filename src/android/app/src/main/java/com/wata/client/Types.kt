package com.wata.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Date

// ============================================================================
// Identity & User Types
// ============================================================================

/**
 * A user in the system (identified by Matrix user ID)
 */
@Serializable
data class User(
    /** Matrix user ID (e.g., @alice:server.local) */
    val id: String,
    /** Display name */
    val displayName: String,
    /** Avatar URL (MXC or HTTP URL), null if no avatar */
    val avatarUrl: String? = null
)

// ============================================================================
// Family & Contact Types
// ============================================================================

/**
 * A family member (contact)
 */
@Serializable
data class Contact(
    /** User information */
    val user: User,
    /** Online status (future: presence) */
    val isOnline: Boolean? = null
)

/**
 * The family group (maps to family room in Matrix)
 */
@Serializable
data class Family(
    /** Room ID */
    val id: String,
    /** Family name */
    val name: String,
    /** List of family members (excluding self) */
    val members: List<Contact>
)

// ============================================================================
// Conversation Types
// ============================================================================

/**
 * A conversation (1:1 DM or family broadcast)
 */
@Serializable
data class Conversation(
    /** Room ID */
    val id: String,
    /** Conversation type */
    val type: ConversationType,
    /** Contact for DM conversations (null for family) */
    val contact: Contact? = null,
    /** Voice messages in this conversation */
    val messages: List<VoiceMessage> = emptyList(),
    /** Number of unplayed messages */
    val unplayedCount: Int = 0
)

@Serializable
enum class ConversationType {
    DM,
    FAMILY
}

// ============================================================================
// Message Types
// ============================================================================

/**
 * A voice message
 */
@Serializable
data class VoiceMessage(
    /** Event ID */
    val id: String,
    /** Message sender */
    val sender: User,
    /** HTTP download URL for playback */
    val audioUrl: String,
    /** Original MXC URL from the Matrix event (for downloadMedia API) */
    val mxcUrl: String,
    /** Duration in seconds */
    val duration: Double,
    /** Message timestamp as unix milliseconds */
    @Serializable(with = DateSerializer::class)
    val timestamp: Date,
    /** Has current user played this message */
    val isPlayed: Boolean,
    /** User IDs who have played this message */
    val playedBy: List<String> = emptyList(),
    /** Whether sending this message failed */
    val failed: Boolean = false
)

// ============================================================================
// Connection State
// ============================================================================

/**
 * Client connection/sync state
 */
enum class ConnectionState {
    /** Initial connection in progress */
    CONNECTING,
    /** Connected, not yet synced */
    CONNECTED,
    /** Actively syncing */
    SYNCING,
    /** Connection error */
    ERROR,
    /** Disconnected */
    OFFLINE
}

// ============================================================================
// Event Handler Types
// ============================================================================

/**
 * Handler for connection state changes
 */
typealias ConnectionStateChangedHandler = (ConnectionState) -> Unit

/**
 * Handler for family updates
 */
typealias FamilyUpdatedHandler = (Family) -> Unit

/**
 * Handler for contacts list updates
 */
typealias ContactsUpdatedHandler = (List<Contact>) -> Unit

/**
 * Handler for new message received
 */
typealias MessageReceivedHandler = (VoiceMessage, Conversation) -> Unit

/**
 * Handler for message deletion
 */
typealias MessageDeletedHandler = (String, String) -> Unit

/**
 * Handler for message played status update
 * Includes roomId to avoid needing to search for the room
 */
typealias MessagePlayedHandler = (VoiceMessage, String) -> Unit

// ============================================================================
// Logging
// ============================================================================

/**
 * Logger interface for WataClient
 * Platform-agnostic - each platform provides its own implementation
 */
interface Logger {
    fun log(message: String)
    fun warn(message: String)
    fun error(message: String)
}

// ============================================================================
// Custom Serializers
// ============================================================================

/**
 * Custom serializer for Date to/from unix timestamp (milliseconds)
 */
object DateSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Date", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Date) {
        encoder.encodeLong(value.time)
    }

    override fun deserialize(decoder: Decoder): Date {
        return Date(decoder.decodeLong())
    }
}
