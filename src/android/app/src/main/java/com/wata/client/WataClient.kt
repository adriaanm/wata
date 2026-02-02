package com.wata.client

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Date

// ============================================================================
// WataClient: High-level domain interface for Wata walkie-talkie features
// ============================================================================

/**
 * WataClient: High-level domain interface for Wata walkie-talkie features
 *
 * This is the main client library that frontends interact with. It wraps
 * MatrixApi and SyncEngine to provide a domain-specific API (families,
 * contacts, voice messages) rather than exposing Matrix protocol details.
 */
class WataClient(
    homeserverUrl: String,
    logger: Logger? = null
) {
    private val api: MatrixApi
    private val logger: Logger = logger ?: createNoopLogger()

    private var syncEngine: SyncEngine? = null
    private var dmRoomService: DmRoomService? = null
    private var userId: String? = null
    private var familyRoomId: String? = null
    private val eventHandlers: MutableList<WataClientEvents> = mutableListOf()
    private var isConnected = false

    init {
        // Normalize base URL (remove trailing slash)
        val baseUrl = homeserverUrl.removeSuffix("/")
        this.api = MatrixApi(baseUrl, logger)
    }

    // ==========================================================================
    // Event Emitter
    // ==========================================================================

    fun addEventHandler(handler: WataClientEvents) {
        eventHandlers.add(handler)
    }

    fun removeEventHandler(handler: WataClientEvents) {
        eventHandlers.remove(handler)
    }

    private fun emitConnectionStateChanged(state: ConnectionState) {
        eventHandlers.forEach { it.onConnectionStateChanged(state) }
    }

    private fun emitFamilyUpdated(family: Family) {
        eventHandlers.forEach { it.onFamilyUpdated(family) }
    }

    private fun emitContactsUpdated(contacts: List<Contact>) {
        eventHandlers.forEach { it.onContactsUpdated(contacts) }
    }

    private fun emitMessageReceived(message: VoiceMessage, conversation: Conversation) {
        eventHandlers.forEach { it.onMessageReceived(message, conversation) }
    }

    private fun emitMessageDeleted(messageId: String, conversationId: String) {
        eventHandlers.forEach { it.onMessageDeleted(messageId, conversationId) }
    }

    private fun emitMessagePlayed(message: VoiceMessage, roomId: String) {
        eventHandlers.forEach { it.onMessagePlayed(message, roomId) }
    }

    // ==========================================================================
    // Lifecycle Methods
    // ==========================================================================

    /**
     * Login with username and password
     */
    fun login(
        username: String,
        password: String,
        deviceDisplayName: String? = null
    ) {
        logger.log("[WataClient] Logging in as $username")

        val response = api.login(username, password, deviceDisplayName)
        userId = response.user_id
        logger.log("[WataClient] Login successful: $userId")

        // Create sync engine and set user ID
        val engine = SyncEngine(api, logger)
        engine.setUserId(userId!!)
        syncEngine = engine

        // Create DM room service
        val dmService = DmRoomService(api, engine, userId!!, logger)
        dmRoomService = dmService

        // Wire up sync engine listeners
        setupSyncEngineListeners()
    }

    /**
     * Start real-time sync
     */
    fun connect() {
        if (userId == null) {
            throw IllegalStateException("Not logged in - call login() first")
        }

        if (isConnected) {
            throw IllegalStateException("Already connected")
        }

        logger.log("[WataClient] Starting sync")

        // Start sync loop (includes initial sync)
        syncEngine?.start()

        isConnected = true
        logger.log("[WataClient] Connected and syncing")
    }

    /**
     * Stop sync and cleanup
     */
    fun disconnect() {
        if (!isConnected) {
            return
        }

        logger.log("[WataClient] Disconnecting")
        syncEngine?.stop()
        isConnected = false
        emitConnectionStateChanged(ConnectionState.OFFLINE)
    }

    /**
     * Logout and invalidate session
     */
    fun logout() {
        logger.log("[WataClient] Logging out")
        if (isConnected) {
            disconnect()
        }

        api.logout()

        // Clear state
        userId = null
        familyRoomId = null
        dmRoomService?.clear()
        syncEngine?.clear()
        logger.log("[WataClient] Logged out")
    }

    /**
     * Get current user
     */
    fun getCurrentUser(): User? {
        if (userId == null) {
            return null
        }

        // Get display name from profile
        // For now, just use user ID as display name
        // We can enhance this by fetching profile on login
        val displayName = userId!!.substringBefore(":").removePrefix("@")
        return User(
            id = userId!!,
            displayName = displayName,
            avatarUrl = null
        )
    }

    /**
     * Verify the current user by calling whoami API
     */
    fun whoami(): String? {
        return try {
            api.whoami().user_id
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get current access token for authenticated media downloads
     */
    fun getAccessToken(): String? = api.getAccessToken()

    /**
     * Get connection state
     */
    fun getConnectionState(): ConnectionState {
        if (!isConnected) {
            return ConnectionState.OFFLINE
        }

        // Map sync engine state to connection state
        // For now, we assume if sync is running, we're syncing
        // TODO: Track actual sync state from sync engine events
        return ConnectionState.SYNCING
    }

    // ==========================================================================
    // Family Methods
    // ==========================================================================

    /**
     * Find the family room by scanning rooms for the #family alias.
     * Updates familyRoomId cache if found.
     */
    private fun findFamilyRoom(): SyncRoomState? {
        // Check cache first
        if (familyRoomId != null) {
            val room = syncEngine?.getRoom(familyRoomId!!)
            if (room != null) {
                return room
            }
            // Cache is stale, clear it
            familyRoomId = null
        }

        // Scan rooms for #family alias
        val server = userId?.substringAfter(":")
        if (server == null) return null

        val familyAlias = "#family:$server"
        val rooms = syncEngine?.getRooms() ?: return null
        for (room in rooms) {
            if (room.canonicalAlias == familyAlias) {
                // Update cache
                familyRoomId = room.roomId
                return room
            }
        }

        return null
    }

    /**
     * Check if a room is the family room (by canonical alias)
     */
    private fun isFamilyRoom(roomId: String): Boolean {
        val room = syncEngine?.getRoom(roomId)
        if (room == null) return false

        val server = userId?.substringAfter(":")
        if (server == null) return false

        return room.canonicalAlias == "#family:$server"
    }

    /**
     * Get the family (null if not in a family)
     */
    fun getFamily(): Family? {
        val room = findFamilyRoom() ?: return null

        return Family(
            id = room.roomId,
            name = room.name.ifEmpty { "Family" },
            members = getContactsFromRoom(room)
        )
    }

    /**
     * Get all contacts (family members excluding self)
     */
    fun getContacts(): List<Contact> {
        val family = getFamily()
        return family?.members ?: emptyList()
    }

    /**
     * Create family room with #family alias
     */
    fun createFamily(name: String): Family {
        val response = api.createRoom(
            CreateRoomRequest(
                name = name,
                visibility = "private",
                preset = "private_chat",
                room_alias_name = "family"
            )
        )

        familyRoomId = response.room_id

        // Wait for room to appear in sync
        waitForRoom(response.room_id)

        val family = getFamily()
        if (family == null) {
            throw IllegalStateException("Failed to create family - room not found after creation")
        }

        return family
    }

    /**
     * Invite user to family room
     */
    fun inviteToFamily(targetUserId: String) {
        val familyRoom = findFamilyRoom()
        if (familyRoom == null) {
            throw IllegalStateException("Not in a family - create or join a family first")
        }

        api.inviteToRoom(familyRoom.roomId, InviteRequest(user_id = targetUserId))
    }

    /**
     * Create a direct message room with a target user.
     *
     * This method creates a new DM room and returns the room ID.
     * The room will appear in the sync engine's state after the next sync cycle.
     *
     * Note: This is a synchronous method that creates the room immediately.
     * The caller should wait for the room to appear in sync state using
     * WataClient.getConversationByRoomId() or by polling.
     *
     * @param targetUserId The Matrix user ID to create a DM with (e.g., "@bob:localhost")
     * @return The created room ID
     */
    fun createDMRoom(targetUserId: String): String {
        logger?.log("[WataClient] Creating DM room with $targetUserId")

        val response = api.createRoom(
            request = CreateRoomRequest(
                name = null,  // Let the other user's display name be the room name
                visibility = "private",
                preset = "trusted_private_chat",
                is_direct = true,
                invite = listOf(targetUserId)
            )
        )

        val roomId = response.room_id
        logger?.log("[WataClient] DM room created: $roomId")

        // Refresh the DM room service to pick up the new room immediately
        // This helps with the sync engine detecting the room faster
        dmRoomService?.refreshFromSync()

        return roomId
    }

    /**
     * Join a room by ID.
     *
     * This method joins an existing room. Useful for:
     * - Accepting invites to DM rooms
     * - Joining rooms that were created by other users
     *
     * @param roomId The room ID to join
     */
    fun joinRoom(roomId: String) {
        logger?.log("[WataClient] Joining room: $roomId")
        api.joinRoom(roomId)

        // Wait for room to appear in sync state
        waitForRoom(roomId, 5000)

        // Refresh DM room service to pick up the new room
        dmRoomService?.refreshFromSync()

        logger?.log("[WataClient] Joined room: $roomId")
    }

    /**
     * Debug method to get the number of rooms in sync engine state.
     * For testing and debugging only.
     */
    fun getRoomCount(): Int {
        return syncEngine?.getRooms()?.size ?: 0
    }

    /**
     * Debug method to list all room IDs in sync engine state.
     * For testing and debugging only.
     */
    fun getRoomIds(): List<String> {
        return syncEngine?.getRooms()?.map { it.roomId } ?: emptyList()
    }

    /**
     * Force a full sync on the next sync cycle.
     *
     * This is useful after operations like room creation where the new room
     * might not appear in incremental syncs immediately due to Conduit's
     * eventual consistency.
     */
    fun forceFullSync() {
        logger?.log("[WataClient] Forcing full sync")
        syncEngine?.forceFullSync()
    }

    // ==========================================================================
    // Conversation Methods
    // ==========================================================================

    /**
     * Get conversation with a contact (creates DM if needed)
     */
    fun getConversation(contact: Contact): Conversation {
        val roomId = getOrCreateDMRoom(contact.user.id)
        val room = syncEngine?.getRoom(roomId)

        if (room == null) {
            throw IllegalStateException("Room $roomId not found in sync state")
        }

        return roomToConversation(room, ConversationType.DM, contact)
    }

    /**
     * Get family broadcast conversation
     */
    fun getFamilyConversation(): Conversation? {
        val room = findFamilyRoom() ?: return null

        return roomToConversation(room, ConversationType.FAMILY)
    }

    /**
     * Get conversation by room ID (synchronous, for existing rooms only)
     * Returns null if room not found. Does not create rooms.
     */
    fun getConversationByRoomId(roomId: String): Conversation? {
        val room = syncEngine?.getRoom(roomId) ?: return null

        // Check if this is the family room
        if (isFamilyRoom(roomId)) {
            return roomToConversation(room, ConversationType.FAMILY)
        }

        // Otherwise, treat as DM and find the contact
        val contact = getContactForDMRoom(roomId)
        if (contact == null) {
            // Room exists but we can't determine the contact
            // This shouldn't happen for valid DM rooms
            return null
        }

        return roomToConversation(room, ConversationType.DM, contact)
    }

    /**
     * Get all conversations with unplayed messages
     */
    fun getUnplayedConversations(): List<Conversation> {
        val conversations = mutableListOf<Conversation>()

        // Check family conversation
        val familyConvo = getFamilyConversation()
        if (familyConvo != null && familyConvo.unplayedCount > 0) {
            conversations.add(familyConvo)
        }

        // Check DM conversations using DMRoomService
        val contacts = getContacts()
        for (contact in contacts) {
            val primaryRoomId = dmRoomService?.getDMRoomId(contact.user.id)
            if (primaryRoomId != null) {
                val room = syncEngine?.getRoom(primaryRoomId)
                if (room != null) {
                    val convo = roomToConversation(room, ConversationType.DM, contact)
                    if (convo.unplayedCount > 0) {
                        conversations.add(convo)
                    }
                }
            }
        }

        return conversations
    }

    // ==========================================================================
    // Voice Message Methods
    // ==========================================================================

    /**
     * Send voice message to contact or family
     */
    fun sendVoiceMessage(
        target: ContactTarget,
        audio: ByteArray,
        duration: Double
    ): VoiceMessage {
        // Upload audio to media repo
        val uploadResponse = api.uploadMedia(
            data = audio,
            contentType = "audio/mp4",
            filename = "voice-${System.currentTimeMillis()}.m4a"
        )

        // Determine target room
        val roomId: String = when (target) {
            is ContactTarget.Family -> {
                val familyRoom = findFamilyRoom()
                if (familyRoom == null) {
                    throw IllegalStateException("Not in a family")
                }
                familyRoom.roomId
            }
            is ContactTarget.DM -> {
                val contact = (target as ContactTarget.DM).contact
                getOrCreateDMRoom(contact.user.id)
            }
        }

        // Send m.audio event
        val sendResponse = api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.audio")
                put("body", "Voice message")
                put("url", uploadResponse.content_uri)
                put("info", buildJsonObject {
                    put("duration", (duration * 1000).toInt()) // Matrix uses milliseconds
                    put("mimetype", "audio/mp4")
                    put("size", audio.size)
                })
            }
        )

        // Return a VoiceMessage with known values
        val currentUser = getCurrentUser()!!
        val mxcUrl = uploadResponse.content_uri
        return VoiceMessage(
            id = sendResponse.event_id,
            sender = currentUser,
            audioUrl = mxcToHttp(mxcUrl),
            mxcUrl = mxcUrl,
            duration = duration,
            timestamp = Date(),
            isPlayed = false,
            playedBy = emptyList()
        )
    }

    /**
     * Send voice message to a specific room ID
     */
    fun sendVoiceMessageToRoom(
        roomId: String,
        audio: ByteArray,
        duration: Double
    ): VoiceMessage {
        // Upload audio to media repo
        val uploadResponse = api.uploadMedia(
            data = audio,
            contentType = "audio/mp4",
            filename = "voice-${System.currentTimeMillis()}.m4a"
        )

        // Send m.audio event to the specified room
        val sendResponse = api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.audio")
                put("body", "Voice message")
                put("url", uploadResponse.content_uri)
                put("info", buildJsonObject {
                    put("duration", (duration * 1000).toInt()) // Matrix uses milliseconds
                    put("mimetype", "audio/mp4")
                    put("size", audio.size)
                })
            }
        )

        // Return a VoiceMessage with known values
        val currentUser = getCurrentUser()!!
        val mxcUrl = uploadResponse.content_uri
        return VoiceMessage(
            id = sendResponse.event_id,
            sender = currentUser,
            audioUrl = mxcToHttp(mxcUrl),
            mxcUrl = mxcUrl,
            duration = duration,
            timestamp = Date(),
            isPlayed = false,
            playedBy = emptyList()
        )
    }

    /**
     * Mark message as played
     */
    fun markAsPlayed(message: VoiceMessage) {
        // Find the room containing this message
        val room = findRoomForEvent(message.id)
        if (room == null) {
            throw IllegalStateException("Room not found for message ${message.id}")
        }

        api.sendReadReceipt(room.roomId, message.id)

        // Update local state and emit event
        val updatedMessage = message.copy(isPlayed = true)
        if (!message.playedBy.contains(userId!!)) {
            val newPlayedBy = updatedMessage.playedBy.toMutableList()
            newPlayedBy.add(userId!!)
        }
        emitMessagePlayed(updatedMessage, room.roomId)
    }

    /**
     * Mark message as played by room and event ID
     */
    fun markAsPlayedById(roomId: String, eventId: String) {
        logger.log("[WataClient] markAsPlayedById: room=$roomId, event=$eventId")

        api.sendReadReceipt(roomId, eventId)

        // Find the event and emit messagePlayed if found
        val roomState = syncEngine?.getRoom(roomId)
        if (roomState != null) {
            val event = roomState.timeline.find { it.event_id == eventId }
            if (event != null && isVoiceMessageEvent(event)) {
                val message = eventToVoiceMessage(event)
                val updatedMessage = message.copy(isPlayed = true)
                if (!message.playedBy.contains(userId!!)) {
                    val newPlayedBy = updatedMessage.playedBy.toMutableList()
                    newPlayedBy.add(userId!!)
                }
                emitMessagePlayed(updatedMessage, roomId)
            }
        }
    }

    /**
     * Delete a message (own messages only)
     */
    fun deleteMessage(message: VoiceMessage) {
        if (message.sender.id != userId) {
            throw IllegalStateException("Can only delete own messages")
        }

        val room = findRoomForEvent(message.id)
        if (room == null) {
            throw IllegalStateException("Room not found for message ${message.id}")
        }

        api.redactEvent(room.roomId, message.id, "Deleted by user")

        emitMessageDeleted(message.id, room.roomId)
    }

    /**
     * Get audio data for playback
     */
    fun getAudioData(message: VoiceMessage): ByteArray {
        return api.downloadMedia(message.audioUrl)
    }

    // ==========================================================================
    // Profile Methods
    // ==========================================================================

    /**
     * Update current user's display name
     */
    fun setDisplayName(name: String) {
        if (userId == null) {
            throw IllegalStateException("Not logged in")
        }

        api.setDisplayName(userId!!, name)
    }

    /**
     * Update current user's avatar URL
     */
    fun setAvatarUrl(avatarUrl: String) {
        if (userId == null) {
            throw IllegalStateException("Not logged in")
        }

        api.setAvatarUrl(userId!!, avatarUrl)
    }

    // ==========================================================================
    // Internal Helper Methods
    // ==========================================================================

    /**
     * Set up listeners for sync engine events
     */
    private fun setupSyncEngineListeners() {
        val engine = syncEngine ?: return

        // Emit connection state changes
        engine.addEventHandler(object : SyncEngineEvents {
            override fun onSynced(nextBatch: String) {
                emitConnectionStateChanged(ConnectionState.SYNCING)
            }

            override fun onError(error: Throwable) {
                emitConnectionStateChanged(ConnectionState.ERROR)
            }

            override fun onTimelineEvent(roomId: String, event: MatrixEvent) {
                handleTimelineEvent(roomId, event)
            }

            override fun onRoomUpdated(roomId: String, room: SyncRoomState) {
                handleRoomUpdated(roomId, room)
            }

            override fun onReceiptUpdated(roomId: String, eventId: String, userIds: Set<String>) {
                handleReceiptUpdated(roomId, eventId, userIds)
            }

            override fun onMembershipChanged(roomId: String, userId: String, membership: String) {
                handleMembershipChanged(roomId, userId, membership)
            }

            override fun onAccountDataUpdated(type: String, content: kotlinx.serialization.json.JsonObject) {
                handleAccountDataUpdated(type, content)
            }
        })
    }

    /**
     * Get or create DM room with a contact.
     * Delegates to DMRoomService for all DM room management.
     */
    private fun getOrCreateDMRoom(contactUserId: String): String {
        val service = dmRoomService
            ?: throw IllegalStateException("DmRoomService not initialized - call connect() first")

        // Use runBlocking to call the suspend function from sync context.
        // This will be converted to proper coroutines in Phase 1b.2.
        return kotlinx.coroutines.runBlocking {
            service.ensureDMRoom(contactUserId)
        }
    }

    /**
     * Convert SyncRoomState to Conversation
     */
    private fun roomToConversation(
        room: SyncRoomState,
        type: ConversationType,
        contact: Contact? = null
    ): Conversation {
        // Get voice messages from timeline
        // Pass room to eventToVoiceMessage since events don't have room_id set
        val messages = room.timeline
            .filter { isVoiceMessageEvent(it) }
            .map { eventToVoiceMessage(it, room) }

        // Count unplayed messages
        val unplayedCount = messages.count { !it.isPlayed }

        return Conversation(
            id = room.roomId,
            type = type,
            contact = contact,
            messages = messages,
            unplayedCount = unplayedCount
        )
    }

    /**
     * Convert MatrixEvent to VoiceMessage
     * @param event - The Matrix event
     * @param room - The room containing this event (events don't have room_id set)
     */
    private fun eventToVoiceMessage(event: MatrixEvent, room: SyncRoomState? = null): VoiceMessage {
        val sender = getUserFromEvent(event, room)
        val content = event.content
        val mxcUrl = content["url"]?.jsonPrimitive?.content ?: ""
        val audioUrl = mxcToHttp(mxcUrl)
        val duration = (content["info"]?.jsonObject?.get("duration")?.jsonPrimitive?.content?.toDouble()
            ?: 0.0) / 1000 // Convert ms to seconds
        val timestamp = Date(event.origin_server_ts ?: 0)

        // Check if current user has played this message
        val playedBy = getPlayedByForEvent(event, room)
        val isPlayed = playedBy.contains(userId!!)

        return VoiceMessage(
            id = event.event_id ?: "",
            sender = sender,
            audioUrl = audioUrl,
            mxcUrl = mxcUrl,
            duration = duration,
            timestamp = timestamp,
            isPlayed = isPlayed,
            playedBy = playedBy
        )
    }

    /**
     * Get User object from event sender
     * @param event - The Matrix event
     * @param room - The room containing this event (optional, will lookup from event.room_id if not provided)
     */
    private fun getUserFromEvent(event: MatrixEvent, room: SyncRoomState? = null): User {
        val eventUserId = event.sender ?: ""

        // Use provided room or try to look up from event.room_id
        val roomState = room ?: (event.room_id?.let { syncEngine?.getRoom(it) })
        val member = roomState?.members?.get(eventUserId)

        return User(
            id = eventUserId,
            displayName = member?.displayName ?: eventUserId.substringBefore(":").removePrefix("@"),
            avatarUrl = member?.avatarUrl
        )
    }

    /**
     * Get list of user IDs who have played a message
     * @param event - The Matrix event
     * @param room - The room containing this event (optional, will lookup from event.room_id if not provided)
     */
    private fun getPlayedByForEvent(event: MatrixEvent, room: SyncRoomState? = null): List<String> {
        // Use provided room or try to look up from event.room_id
        val roomState = room ?: (event.room_id?.let { syncEngine?.getRoom(it) })
        if (roomState == null) {
            logger.warn("[WataClient] getPlayedByForEvent: no room available for event ${event.event_id}")
            return emptyList()
        }

        val eventId = event.event_id ?: return emptyList()
        return roomState.readReceipts[eventId]?.toList() ?: emptyList()
    }

    /**
     * Get contacts from a room's membership
     */
    private fun getContactsFromRoom(room: SyncRoomState): List<Contact> {
        return room.members.values
            .filter { it.userId != userId }
            .filter { it.membership == "join" }
            .map { member ->
                Contact(
                    user = User(
                        id = member.userId,
                        displayName = member.displayName,
                        avatarUrl = member.avatarUrl
                    )
                )
            }
    }

    /**
     * Check if event is a voice message
     */
    private fun isVoiceMessageEvent(event: MatrixEvent): Boolean {
        return event.type == "m.room.message" &&
            event.content["msgtype"]?.jsonPrimitive?.content == "m.audio" &&
            event.unsigned?.redacted_because == null
    }

    /**
     * Find room containing a specific event
     */
    private fun findRoomForEvent(eventId: String): SyncRoomState? {
        val rooms = syncEngine?.getRooms() ?: return null
        for (room in rooms) {
            if (room.timeline.any { it.event_id == eventId }) {
                return room
            }
        }
        return null
    }

    /**
     * Wait for a room to appear in sync
     */
    private fun waitForRoom(roomId: String, timeoutMs: Long = 5000) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val room = syncEngine?.getRoom(roomId)
            if (room != null) {
                return
            }
            Thread.sleep(100)
        }
        throw IllegalStateException("Timeout waiting for room $roomId")
    }

    /**
     * Convert MXC URL to HTTP URL
     */
    private fun mxcToHttp(mxcUrl: String): String {
        // Parse mxc:// URL
        val match = Regex("^mxc://([^/]+)/(.+)$").find(mxcUrl)
        if (match != null) {
            val (_, serverName, mediaId) = match.groupValues
            // Get base URL from api
            // We need to expose the baseUrl from MatrixApi or reconstruct it
            // For now, we'll use a simple approach
            return "/_matrix/client/v1/media/download/${serverName}/$mediaId"
        }
        return mxcUrl // Already HTTP or invalid
    }

    // ==========================================================================
    // Event Handlers
    // ==========================================================================

    /**
     * Handle timeline events from sync engine
     */
    private fun handleTimelineEvent(roomId: String, event: MatrixEvent) {
        // Handle voice messages
        if (isVoiceMessageEvent(event)) {
            logger.log("[WataClient] Voice message received in room $roomId from ${event.sender}")

            val room = syncEngine?.getRoom(roomId)
            if (room == null) {
                logger.warn("[WataClient] Room $roomId not found in sync state")
                return
            }

            val message = eventToVoiceMessage(event)

            // Determine conversation type by checking canonical alias
            val conversation: Conversation = if (isFamilyRoom(roomId)) {
                logger.log("[WataClient] Message is in family room")
                roomToConversation(room, ConversationType.FAMILY)
            } else {
                // Find the contact for this DM
                val contact = getContactForDMRoom(roomId)
                if (contact == null) {
                    logger.warn("[WataClient] Could not find contact for DM room $roomId, dropping message")
                    // Log room membership for debugging
                    val members = room.members.entries.joinToString(", ") { (id, m) ->
                        "$id(${m.membership})"
                    }
                    logger.warn("[WataClient] Room has ${room.members.size} members: $members")
                    return
                }
                logger.log("[WataClient] Message is DM from ${contact.user.displayName}")
                roomToConversation(room, ConversationType.DM, contact)
            }

            emitMessageReceived(message, conversation)
        }

        // Handle redacted events
        if (event.unsigned?.redacted_because != null) {
            emitMessageDeleted(event.event_id ?: "", roomId)
        }
    }

    /**
     * Handle room updates
     */
    private fun handleRoomUpdated(roomId: String, room: SyncRoomState) {
        // If family room updated, emit family/contacts events
        if (isFamilyRoom(roomId)) {
            val family = getFamily()
            if (family != null) {
                emitFamilyUpdated(family)
                emitContactsUpdated(family.members)
            }
        }
    }

    /**
     * Handle receipt updates
     */
    private fun handleReceiptUpdated(roomId: String, eventId: String, userIds: Set<String>) {
        logger.log("[WataClient] Receipt update for event $eventId in room $roomId, users: ${userIds.joinToString(", ")}")

        val room = syncEngine?.getRoom(roomId)
        if (room == null) {
            logger.warn("[WataClient] Room $roomId not found for receipt update")
            return
        }

        // Verify the receipt is stored in room.readReceipts
        val storedReceipts = room.readReceipts[eventId]
        logger.log("[WataClient] Room readReceipts for ${eventId.takeLast(12)}: ${storedReceipts?.joinToString(", ") ?: "NONE"}")
        logger.log("[WataClient] Room has ${room.readReceipts.size} total receipt entries")

        val event = room.timeline.find { it.event_id == eventId }
        if (event == null) {
            logger.warn("[WataClient] Event $eventId not found in room timeline")
            return
        }

        if (!isVoiceMessageEvent(event)) {
            // Not a voice message, ignore silently
            return
        }

        logger.log("[WataClient] Emitting messagePlayed for $eventId in room $roomId")
        val message = eventToVoiceMessage(event)
        emitMessagePlayed(message, roomId)
    }

    /**
     * Handle membership changes
     */
    private fun handleMembershipChanged(roomId: String, eventUserId: String, membership: String) {
        // Auto-join invites (trusted family environment)
        if (eventUserId == userId && membership == "invite") {
            try {
                api.joinRoom(roomId)

                // After joining, refresh DM room service from sync state
                waitForRoom(roomId, 3000)
                dmRoomService?.refreshFromSync()
            } catch (e: Exception) {
                logger.error("[WataClient] Failed to auto-join room $roomId: $e")
            }
        }
    }

    /**
     * Handle account data updates (m.direct, etc.)
     */
    private fun handleAccountDataUpdated(type: String, content: kotlinx.serialization.json.JsonObject) {
        if (type == "m.direct") {
            // Delegate m.direct handling to DMRoomService
            // Parse the content JsonObject into Map<String, List<String>>
            val directData: MutableMap<String, List<String>> = mutableMapOf()
            content.forEach { (key, value) ->
                if (value is kotlinx.serialization.json.JsonArray) {
                    val roomIds = value.jsonArray.mapNotNull { it.jsonPrimitive?.content }
                    directData[key] = roomIds
                }
            }
            dmRoomService?.handleMDirectUpdate(directData)
        }
    }

    /**
     * Get contact for a DM room.
     * Delegates to DMRoomService.
     */
    fun getContactForDMRoom(roomId: String): Contact? {
        return dmRoomService?.getContactForRoom(roomId)
    }

    /**
     * Check if a room is a known DM room.
     */
    fun isDMRoom(roomId: String): Boolean {
        return dmRoomService?.isDMRoom(roomId) == true
    }

    /**
     * Get the primary DM room ID for a contact (synchronous lookup).
     * Returns null if no DM room exists in cache.
     */
    fun getDMRoomId(contactUserId: String): String? {
        return dmRoomService?.getDMRoomId(contactUserId)
    }

    /**
     * Get all known DM room IDs for a contact.
     */
    fun getAllDMRoomIds(contactUserId: String): List<String> {
        return dmRoomService?.getAllDMRoomIds(contactUserId) ?: emptyList()
    }
}

// ============================================================================
// Sealed Class for Contact Target
// ============================================================================

sealed class ContactTarget {
    object Family : ContactTarget()
    data class DM(val contact: Contact) : ContactTarget()
}

// ============================================================================
// WataClient Events Interface
// ============================================================================

interface WataClientEvents {
    fun onConnectionStateChanged(state: ConnectionState) {}
    fun onFamilyUpdated(family: Family) {}
    fun onContactsUpdated(contacts: List<Contact>) {}
    fun onMessageReceived(message: VoiceMessage, conversation: Conversation) {}
    fun onMessageDeleted(messageId: String, conversationId: String) {}
    fun onMessagePlayed(message: VoiceMessage, roomId: String) {}
}

// ============================================================================
// No-op Logger
// ============================================================================

private fun createNoopLogger(): Logger {
    return object : Logger {
        override fun log(message: String) {}
        override fun warn(message: String) {}
        override fun error(message: String) {}
    }
}
