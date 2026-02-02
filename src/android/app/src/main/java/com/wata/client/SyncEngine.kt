package com.wata.client

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ============================================================================
// State Types
// ============================================================================

/**
 * Information about a room member
 */
data class MemberInfo(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val membership: String // "join" | "invite" | "leave" | "ban" | "knock"
)

/**
 * In-memory state for a single room
 * Named SyncRoomState to avoid conflict with MatrixApi.RoomState
 */
data class SyncRoomState(
    val roomId: String,
    var name: String = "",
    var avatarUrl: String? = null,
    /** Canonical alias for the room (e.g., #family:server) */
    var canonicalAlias: String? = null,
    /** Room summary (heroes, member counts) */
    var summary: RoomSummary? = null,
    /** Unread notification counts */
    var unreadNotifications: UnreadNotifications? = null,
    /** Map of userId -> member info */
    val members: MutableMap<String, MemberInfo> = mutableMapOf(),
    /** Timeline events (chronological order, oldest to newest) */
    val timeline: MutableList<MatrixEvent> = mutableListOf(),
    /** Account data events for this room (type -> content) */
    val accountData: MutableMap<String, kotlinx.serialization.json.JsonObject> = mutableMapOf(),
    /** Read receipts: eventId -> Set of userIds who read it */
    val readReceipts: MutableMap<String, MutableSet<String>> = mutableMapOf()
)

// ============================================================================
// Event Types
// ============================================================================

interface SyncEngineEvents {
    /** Emitted after each successful sync cycle */
    fun onSynced(nextBatch: String) {}
    /** Emitted when a room's state is updated */
    fun onRoomUpdated(roomId: String, room: SyncRoomState) {}
    /** Emitted when a new timeline event arrives */
    fun onTimelineEvent(roomId: String, event: MatrixEvent) {}
    /** Emitted when read receipts are updated */
    fun onReceiptUpdated(roomId: String, eventId: String, userIds: Set<String>) {}
    /** Emitted when a membership change occurs */
    fun onMembershipChanged(roomId: String, userId: String, membership: String) {}
    /** Emitted when global account data is updated */
    fun onAccountDataUpdated(type: String, content: kotlinx.serialization.json.JsonObject) {}
    /** Emitted when sync encounters an error */
    fun onError(error: Throwable) {}
}

// ============================================================================
// No-op logger (default when no logger provided)
// ============================================================================

private val noopLogger = object : Logger {
    override fun log(message: String) {}
    override fun warn(message: String) {}
    override fun error(message: String) {}
}

// ============================================================================
// Sync Engine
// ============================================================================

/**
 * Sync Engine for WataClient
 *
 * Manages the Matrix sync loop and maintains in-memory state for rooms,
 * members, timeline events, and read receipts. Emits typed events for
 * state changes.
 *
 * This is the low-level sync layer - it maintains raw Matrix state without
 * mapping to domain concepts (that's WataClient's job).
 */
class SyncEngine(
    private val api: MatrixApi,
    logger: Logger? = null
) {
    private val logger: Logger = logger ?: noopLogger
    private val rooms: MutableMap<String, SyncRoomState> = mutableMapOf()
    private var userId: String? = null
    private var nextBatch: String? = null
    private var isRunning = false
    private var syncThread: Thread? = null
    private val eventHandlers: MutableList<SyncEngineEvents> = mutableListOf()

    // ==========================================================================
    // Event Emitter
    // ==========================================================================

    fun addEventHandler(handler: SyncEngineEvents) {
        eventHandlers.add(handler)
    }

    fun removeEventHandler(handler: SyncEngineEvents) {
        eventHandlers.remove(handler)
    }

    private fun emitSynced(nextBatch: String) {
        eventHandlers.forEach { it.onSynced(nextBatch) }
    }

    private fun emitRoomUpdated(roomId: String, room: SyncRoomState) {
        eventHandlers.forEach { it.onRoomUpdated(roomId, room) }
    }

    private fun emitTimelineEvent(roomId: String, event: MatrixEvent) {
        eventHandlers.forEach {
            try {
                it.onTimelineEvent(roomId, event)
            } catch (e: Exception) {
                logger.error("[SyncEngine] Error in timelineEvent handler: $e")
            }
        }
    }

    private fun emitReceiptUpdated(roomId: String, eventId: String, userIds: Set<String>) {
        eventHandlers.forEach { it.onReceiptUpdated(roomId, eventId, userIds) }
    }

    private fun emitMembershipChanged(roomId: String, userId: String, membership: String) {
        eventHandlers.forEach { it.onMembershipChanged(roomId, userId, membership) }
    }

    private fun emitAccountDataUpdated(type: String, content: kotlinx.serialization.json.JsonObject) {
        eventHandlers.forEach { it.onAccountDataUpdated(type, content) }
    }

    private fun emitError(error: Throwable) {
        eventHandlers.forEach { it.onError(error) }
    }

    // ==========================================================================
    // Lifecycle
    // ==========================================================================

    /**
     * Set the current user ID (must be called after login)
     */
    fun setUserId(userId: String) {
        this.userId = userId
    }

    /**
     * Start the sync loop
     * Performs an initial sync before starting the background loop
     */
    fun start() {
        if (isRunning) {
            throw IllegalStateException("Sync engine already running")
        }

        if (userId == null) {
            throw IllegalStateException("User ID not set - call setUserId() after login")
        }

        isRunning = true
        logger.log("[SyncEngine] Starting initial sync")

        // Perform initial sync with a short timeout to get started quickly
        try {
            val response = api.sync(
                params = SyncParams(timeout = 5000) // 5 second timeout for initial sync
            )
            processSyncResponse(response)
            nextBatch = response.next_batch
            val roomCount = rooms.size
            logger.log("[SyncEngine] Initial sync complete: $roomCount rooms")
            emitSynced(response.next_batch)
        } catch (e: Exception) {
            // If initial sync fails, we'll retry in the background loop
            logger.error("[SyncEngine] Initial sync failed: $e")
            emitError(e)
        }

        // Start the background sync loop
        logger.log("[SyncEngine] Starting background sync loop")
        syncThread = Thread {
            runSyncLoop()
        }.apply { start() }
    }

    /**
     * Stop the sync loop
     */
    fun stop() {
        if (!isRunning) {
            return
        }

        logger.log("[SyncEngine] Stopping sync loop")
        isRunning = false

        // Wait for sync thread to finish (with timeout)
        syncThread?.let { thread ->
            thread.join(5000)
            if (thread.isAlive) {
                logger.warn("[SyncEngine] Sync thread did not stop gracefully")
            }
        }
        syncThread = null
    }

    /**
     * Main sync loop - continuously polls the sync endpoint
     */
    private fun runSyncLoop() {
        var retryDelay = 1000L // Start with 1 second
        val maxRetryDelay = 60000L // Max 60 seconds

        while (isRunning) {
            try {
                // Call sync endpoint with 30 second timeout
                val response = api.sync(
                    params = SyncParams(
                        timeout = 30000,
                        since = nextBatch
                    )
                )

                // Process the sync response
                processSyncResponse(response)

                // Store next batch token for incremental sync
                nextBatch = response.next_batch

                // Emit synced event
                emitSynced(response.next_batch)

                // Reset retry delay on success
                retryDelay = 1000L
            } catch (e: Exception) {
                // Emit error event
                logger.error("[SyncEngine] Sync error: ${e.message}")
                emitError(e)

                // Stop if we're no longer running
                if (!isRunning) {
                    break
                }

                // Exponential backoff with jitter
                val jitter = (Math.random() * 1000).toLong()
                Thread.sleep(retryDelay + jitter)
                retryDelay = (retryDelay * 2).coerceAtMost(maxRetryDelay)
            }
        }
    }

    // ==========================================================================
    // Sync Response Processing
    // ==========================================================================

    /**
     * Process a sync response and update internal state
     */
    private fun processSyncResponse(response: SyncResponse) {
        // Process global account data
        response.account_data?.events?.forEach { event ->
            // Emit account data updated event for global account data
            emitAccountDataUpdated(event.type, event.content)
        }

        // Process rooms
        response.rooms?.let { roomsData ->
            // Joined rooms
            roomsData.join?.forEach { (roomId, roomData) ->
                processJoinedRoom(roomId, roomData)
            }

            // Invited rooms
            roomsData.invite?.forEach { (roomId, roomData) ->
                processInvitedRoom(roomId, roomData)
            }

            // Left rooms
            roomsData.leave?.forEach { (roomId, roomData) ->
                processLeftRoom(roomId, roomData)
            }
        }
    }

    /**
     * Process a joined room from sync response
     */
    private fun processJoinedRoom(roomId: String, roomData: JoinedRoomSync) {
        // Get or create room state
        val room = rooms.getOrPut(roomId) {
            logger.log("[SyncEngine] New room discovered: $roomId")
            createEmptyRoom(roomId)
        }

        // Process state events (m.room.name, m.room.avatar, m.room.member, etc.)
        roomData.state?.events?.forEach { event ->
            processStateEvent(room, event)
        }

        // Process state_after events (state changes between since and end of timeline)
        roomData.state_after?.events?.forEach { event ->
            processStateEvent(room, event)
        }

        // Capture room summary
        roomData.summary?.let { room.summary = it }

        // Capture unread notifications
        roomData.unread_notifications?.let { room.unreadNotifications = it }

        // Process timeline events (new messages)
        roomData.timeline?.events?.let { events ->
            logger.log("[SyncEngine] Processing ${events.size} timeline events for room $roomId")
            events.forEach { event ->
                // Skip if event already exists in timeline (prevent duplicates from incremental syncs)
                if (room.timeline.any { it.event_id == event.event_id }) {
                    logger.log("[SyncEngine] Skipping duplicate event ${event.event_id?.takeLast(12)}")
                    return@forEach
                }

                // Add to timeline
                room.timeline.add(event)

                // Process state events in timeline
                if (event.state_key != null) {
                    processStateEvent(room, event)
                }

                // Log message events
                if (event.type == "m.room.message") {
                    val msgtype = event.content["msgtype"]?.jsonPrimitive?.content
                    logger.log("[SyncEngine] Message event: $msgtype from ${event.sender}")
                }

                // Emit timeline event
                emitTimelineEvent(roomId, event)
            }
        }

        // Process ephemeral events (receipts, typing, etc.)
        roomData.ephemeral?.events?.forEach { event ->
            if (event.type == "m.receipt") {
                processReceiptEvent(room, event)
            }
        }

        // Process room account data
        roomData.account_data?.events?.forEach { event ->
            room.accountData[event.type] = event.content
        }

        // Emit room updated event
        emitRoomUpdated(roomId, room)
    }

    /**
     * Process an invited room from sync response
     */
    private fun processInvitedRoom(roomId: String, roomData: InvitedRoomSync) {
        val room = rooms.getOrPut(roomId) {
            createEmptyRoom(roomId)
        }

        // Process stripped state events (limited info for invites)
        roomData.invite_state?.events?.forEach { event ->
            // Convert stripped state to full event format
            val fullEvent = MatrixEvent(
                type = event.type,
                state_key = event.state_key,
                content = event.content,
                sender = event.sender,
                event_id = event.event_id,
                origin_server_ts = event.origin_server_ts
            )
            processStateEvent(room, fullEvent)
        }

        // Emit room updated event
        emitRoomUpdated(roomId, room)
    }

    /**
     * Process a left room from sync response
     */
    private fun processLeftRoom(roomId: String, roomData: LeftRoomSync) {
        val room = rooms.getOrPut(roomId) {
            createEmptyRoom(roomId)
        }

        // Process state events
        roomData.state?.events?.forEach { event ->
            processStateEvent(room, event)
        }

        // Process timeline events
        roomData.timeline?.events?.forEach { event ->
            // Skip if event already exists in timeline (prevent duplicates from incremental syncs)
            if (room.timeline.any { it.event_id == event.event_id }) {
                return@forEach
            }

            room.timeline.add(event)

            if (event.state_key != null) {
                processStateEvent(room, event)
            }

            emitTimelineEvent(roomId, event)
        }

        // Emit room updated event
        emitRoomUpdated(roomId, room)
    }

    /**
     * Process a state event and update room state
     */
    private fun processStateEvent(room: SyncRoomState, event: MatrixEvent) {
        when (event.type) {
            "m.room.name" -> {
                room.name = event.content["name"]?.jsonPrimitive?.content ?: ""
            }
            "m.room.avatar" -> {
                room.avatarUrl = event.content["url"]?.jsonPrimitive?.content
            }
            "m.room.canonical_alias" -> {
                room.canonicalAlias = event.content["alias"]?.jsonPrimitive?.content
            }
            "m.room.member" -> {
                event.state_key?.let { stateKey ->
                    val memberUserId = stateKey
                    val membership = event.content["membership"]?.jsonPrimitive?.content ?: "leave"

                    val member = MemberInfo(
                        userId = memberUserId,
                        displayName = event.content["displayname"]?.jsonPrimitive?.content ?: memberUserId,
                        avatarUrl = event.content["avatar_url"]?.jsonPrimitive?.content,
                        membership = membership
                    )

                    room.members[memberUserId] = member

                    // Emit membership change event
                    emitMembershipChanged(room.roomId, memberUserId, membership)
                }
            }
        }
    }

    /**
     * Process read receipt events
     */
    private fun processReceiptEvent(room: SyncRoomState, event: MatrixEvent) {
        // Receipt event format:
        // {
        //   type: 'm.receipt',
        //   content: {
        //     '$eventId': {
        //       'm.read': {
        //         '@userId': { ts: 1234567890 }
        //       }
        //     }
        //   }
        // }

        event.content.forEach { (eventId, receiptData) ->
            val readReceipts = receiptData.jsonObject["m.read"]?.jsonObject
            if (readReceipts != null) {
                // Get or create receipt set for this event
                var receipts = room.readReceipts[eventId]
                if (receipts == null) {
                    receipts = mutableSetOf()
                    room.readReceipts[eventId] = receipts
                }

                // Add user IDs who read this event
                readReceipts.forEach { (userId, _) ->
                    receipts.add(userId)
                }

                // Emit receipt updated event
                emitReceiptUpdated(room.roomId, eventId, receipts)
            }
        }
    }

    /**
     * Create an empty room state object
     */
    private fun createEmptyRoom(roomId: String): SyncRoomState {
        return SyncRoomState(
            roomId = roomId,
            name = "",
            avatarUrl = null,
            canonicalAlias = null,
            members = mutableMapOf(),
            timeline = mutableListOf(),
            accountData = mutableMapOf(),
            readReceipts = mutableMapOf()
        )
    }

    // ==========================================================================
    // State Access
    // ==========================================================================

    /**
     * Get the current user ID
     */
    fun getUserId(): String? = userId

    /**
     * Get a specific room's state
     */
    fun getRoom(roomId: String): SyncRoomState? = rooms[roomId]

    /**
     * Get all rooms
     */
    fun getRooms(): List<SyncRoomState> = rooms.values.toList()

    /**
     * Get the next batch token (for resuming sync)
     */
    fun getNextBatch(): String? = nextBatch

    /**
     * Set the next batch token (for resuming sync)
     */
    fun setNextBatch(token: String) {
        this.nextBatch = token
    }

    /**
     * Clear all state (useful for logout)
     */
    fun clear() {
        rooms.clear()
        userId = null
        nextBatch = null
    }

    /**
     * Force a full sync on the next sync cycle.
     *
     * This clears the nextBatch token, causing the next sync to start
     * from the beginning rather than incrementally syncing. Useful after
     * operations like room creation where the new room might not appear
     * in incremental syncs immediately.
     */
    fun forceFullSync() {
        logger?.log("[SyncEngine] Forcing full sync by clearing nextBatch token")
        nextBatch = null
    }
}
