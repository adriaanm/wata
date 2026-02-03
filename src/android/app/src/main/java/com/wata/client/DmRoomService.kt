package com.wata.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ============================================================================
// DMRoomService: Encapsulates all DM room management logic
// ============================================================================

/**
 * DMRoomService: Encapsulates all DM room management logic
 *
 * This service handles:
 * - DM room lookup (userId -> roomId mapping)
 * - Deterministic room selection when multiple rooms exist
 * - m.direct account data management
 * - DM room creation (rare, only when no room exists)
 */
class DmRoomService(
    private val api: MatrixApi,
    private val syncEngine: SyncEngine,
    private val userId: String,
    logger: Logger? = null
) {
    private val logger: Logger = logger ?: createNoopLogger()

    /**
     * Primary room ID for each contact (deterministically selected).
     * This is THE room to use when sending messages.
     */
    private val primaryRoomByContact: MutableMap<String, String> = mutableMapOf()

    /**
     * All known room IDs for each contact (may be multiple due to race conditions).
     * Used for message consolidation and room detection.
     */
    private val allRoomsByContact: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /**
     * Reverse lookup: room ID -> contact user ID.
     * Used for quick isDMRoom() checks and getContactForRoom().
     */
    private val contactByRoom: MutableMap<String, String> = mutableMapOf()

    // ==========================================================================
    // Lookup Methods (synchronous, cached)
    // ==========================================================================

    /**
     * Get the primary DM room ID for a contact (if known).
     * Returns null if no DM room exists in our cache.
     * Does NOT create a room or make network calls.
     */
    fun getDMRoomId(contactUserId: String): String? {
        return primaryRoomByContact[contactUserId]
    }

    /**
     * Get all known DM room IDs for a contact.
     * Returns empty list if none known.
     * Useful for message consolidation across duplicate rooms.
     */
    fun getAllDMRoomIds(contactUserId: String): List<String> {
        val roomIds = allRoomsByContact[contactUserId]
        return roomIds?.toList() ?: emptyList()
    }

    /**
     * Check if a room ID is a known DM room.
     */
    fun isDMRoom(roomId: String): Boolean {
        return contactByRoom.containsKey(roomId)
    }

    /**
     * Get the contact user ID for a DM room (reverse lookup).
     * Returns null if the room isn't a known DM room.
     */
    fun getContactUserId(roomId: String): String? {
        return contactByRoom[roomId]
    }

    /**
     * Get Contact object for a DM room.
     * Returns null if not a DM room or contact not found.
     */
    fun getContactForRoom(roomId: String): Contact? {
        // First try the cached mapping
        val contactUserId = contactByRoom[roomId]
        if (contactUserId != null) {
            // Cache hit - buildContactFromRoom always returns a Contact now
            return buildContactFromRoom(roomId, contactUserId)
        }

        // Fallback: Infer from room membership (recipient-side issue)
        // A DM room has exactly 2 members: current user and contact
        val room = syncEngine.getRoom(roomId)
        if (room == null) return null

        val joinedMembers = room.members.values.filter { it.membership == "join" }

        if (joinedMembers.size != 2) return null

        val otherMember = joinedMembers.find { it.userId != userId }
        if (otherMember == null) return null

        // Accept any 2-person room as a DM (relaxed check for recipient-side)
        // The is_direct flag may not be set on rooms created by others
        logger.log("[DMRoomService] getContactForRoom: inferred DM for ${otherMember.userId} from room membership (cache miss)")
        return Contact(
            user = User(
                id = otherMember.userId,
                displayName = otherMember.displayName,
                avatarUrl = otherMember.avatarUrl
            )
        )
    }

    // ==========================================================================
    // Creation Methods (async, makes network calls)
    // ==========================================================================

    /**
     * Ensure a DM room exists with the contact.
     * - First tries to find existing room via sync state
     * - Creates new room only if none found
     * - Updates m.direct and internal caches
     *
     * This is the ONLY method that should create DM rooms.
     */
    suspend fun ensureDMRoom(contactUserId: String): String {
        logger.log("[DMRoomService] ensureDMRoom for $contactUserId")

        // Step 1: Check cache first (fast path)
        val cached = primaryRoomByContact[contactUserId]
        if (cached != null) {
            // Verify the room still exists and we're joined
            val room = syncEngine.getRoom(cached)
            if (room != null && room.members[userId]?.membership == "join") {
                logger.log("[DMRoomService] Using cached room $cached")
                return cached
            }
            // Room no longer valid, remove from cache
            removeRoomFromCache(cached)
        }

        // Step 2: Scan sync state for existing DM rooms
        val existingRoom = findExistingDMRoom(contactUserId)
        if (existingRoom != null) {
            logger.log("[DMRoomService] Found existing room $existingRoom")
            updateMDirectForRoom(contactUserId, existingRoom)
            return existingRoom
        }

        // Step 3: No existing room found, create new one
        logger.log("[DMRoomService] Creating new DM room with $contactUserId")
        val roomId = createDMRoom(contactUserId)

        return roomId
    }

    // ==========================================================================
    // Cache Management
    // ==========================================================================

    /**
     * Handle m.direct account data update.
     * Called by WataClient when account data changes.
     */
    fun handleMDirectUpdate(content: Map<String, List<String>>) {
        // m.direct format: { "@user:server": ["!roomId1", "!roomId2", ...] }
        for ((contactUserId, roomIds) in content) {
            if (roomIds.isEmpty()) continue

            // Update allRoomsByContact
            allRoomsByContact[contactUserId] = roomIds.toMutableSet()

            // Update reverse mapping
            for (roomId in roomIds) {
                contactByRoom[roomId] = contactUserId
            }

            // Determine primary room (oldest by creation timestamp)
            val primaryRoomId = selectPrimaryRoom(roomIds)
            if (primaryRoomId != null) {
                primaryRoomByContact[contactUserId] = primaryRoomId
            }
        }
    }

    /**
     * Refresh cache from current sync state.
     * Called after sync batches to discover new DM rooms.
     */
    fun refreshFromSync() {
        val rooms = syncEngine.getRooms()
        logger.log("[DMRoomService] refreshFromSync: scanning ${rooms.size} rooms")

        for (room in rooms) {
            // Skip rooms we're not joined to
            val myMembership = room.members[userId]?.membership
            if (myMembership != "join") {
                logger.log("[DMRoomService] Room ${room.roomId.takeLast(12)}: skipping (my membership=$myMembership)")
                continue
            }

            // Check if this is a 2-person DM room
            val joinedMembers = room.members.values.filter { it.membership == "join" }
            if (joinedMembers.size != 2) {
                logger.log("[DMRoomService] Room ${room.roomId.takeLast(12)}: skipping (${joinedMembers.size} joined members)")
                continue
            }

            val otherMember = joinedMembers.find { it.userId != userId }
            if (otherMember == null) {
                logger.log("[DMRoomService] Room ${room.roomId.takeLast(12)}: skipping (no other member)")
                continue
            }

            // Verify is_direct flag
            if (!hasIsDirectFlag(room)) {
                logger.log("[DMRoomService] Room ${room.roomId.takeLast(12)}: skipping (no is_direct flag)")
                continue
            }

            // Add to cache
            val contactUserId = otherMember.userId
            logger.log("[DMRoomService] Room ${room.roomId.takeLast(12)}: detected as DM with $contactUserId")
            addRoomToCache(contactUserId, room.roomId)
        }
    }

    /**
     * Clear all cached state (on logout).
     */
    fun clear() {
        primaryRoomByContact.clear()
        allRoomsByContact.clear()
        contactByRoom.clear()
    }

    // ==========================================================================
    // Private Helpers
    // ==========================================================================

    /**
     * Find existing DM room with contact by scanning sync state.
     * Returns the primary room ID if found, null otherwise.
     */
    private fun findExistingDMRoom(contactUserId: String): String? {
        val candidateRooms = mutableListOf<CandidateRoom>()
        val rooms = syncEngine.getRooms()

        for (room in rooms) {
            // Skip if not joined
            if (room.members[userId]?.membership != "join") continue

            // Check if this is a 2-person room with the target user
            val joinedMembers = room.members.values.filter { it.membership == "join" }
            if (joinedMembers.size != 2) continue

            val hasTargetUser = joinedMembers.any { it.userId == contactUserId }
            if (!hasTargetUser) continue

            // Check is_direct flag and get creation timestamp
            val (isDirectRoom, creationTs) = getRoomDMInfo(room)

            // Only include valid DM rooms with timestamps
            if (isDirectRoom && creationTs != null && creationTs > 0) {
                val messageCount = room.timeline.count { event ->
                    event.type == "m.room.message" &&
                    event.content["msgtype"]?.jsonPrimitive?.content == "m.audio"
                }
                candidateRooms.add(CandidateRoom(room.roomId, creationTs, messageCount))
                logger.log(
                    "[DMRoomService] Found candidate room ${room.roomId} " +
                    "(created: $creationTs, $messageCount msgs)"
                )
            }
        }

        if (candidateRooms.isEmpty()) return null

        // Log warning if multiple rooms found
        if (candidateRooms.size > 1) {
            val roomList = candidateRooms.joinToString(", ") { r ->
                "${r.roomId.takeLast(12)} (${r.creationTs}, ${r.messageCount} msgs)"
            }
            logger.warn(
                "[DMRoomService] Multiple DM rooms with $contactUserId: $roomList. Selecting oldest."
            )
        }

        // Sort by creation timestamp (oldest first), room ID as tiebreaker
        candidateRooms.sortWith(compareBy({ it.creationTs }, { it.roomId }))

        val primaryRoom = candidateRooms[0]
        logger.log(
            "[DMRoomService] Selected primary room ${primaryRoom.roomId} " +
            "(${candidateRooms.size} candidates)"
        )

        // Update cache with all candidate rooms
        for (room in candidateRooms) {
            addRoomToCache(contactUserId, room.roomId)
        }
        primaryRoomByContact[contactUserId] = primaryRoom.roomId

        return primaryRoom.roomId
    }

    /**
     * Create a new DM room with the contact.
     */
    private suspend fun createDMRoom(contactUserId: String): String {
        val response = api.createRoom(
            CreateRoomRequest(
                is_direct = true,
                invite = listOf(contactUserId),
                preset = "trusted_private_chat",
                visibility = "private"
            )
        )

        val roomId = response.room_id

        // Update m.direct account data
        updateMDirectForRoom(contactUserId, roomId)

        // Add to cache
        addRoomToCache(contactUserId, roomId)
        primaryRoomByContact[contactUserId] = roomId

        return roomId
    }

    /**
     * Update m.direct account data with a DM room.
     */
    private suspend fun updateMDirectForRoom(contactUserId: String, roomId: String) {
        try {
            // Get current m.direct data (map of userId -> list of roomIds)
            val directData: MutableMap<String, MutableList<String>> = mutableMapOf()
            try {
                val accountData = api.getAccountData(userId, "m.direct")
                // Parse: { "@user:server": ["!roomId1", "!roomId2"], ... }
                for ((key, value) in accountData.content) {
                    val roomIds = value.jsonArray.map { it.jsonPrimitive.content }
                    directData[key] = roomIds.toMutableList()
                }
            } catch (e: Exception) {
                // No existing data - start fresh
            }

            // Add room to contact's list if not already there
            val roomList = directData.getOrPut(contactUserId) { mutableListOf() }
            if (!roomList.contains(roomId)) {
                roomList.add(roomId)

                // Serialize back to JsonObject and persist
                val newContent = buildJsonObject {
                    for ((key, rooms) in directData) {
                        put(key, JsonArray(rooms.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    }
                }
                api.setAccountData(userId, "m.direct", newContent)
                logger.log("[DMRoomService] Updated m.direct with room $roomId for $contactUserId")
            }
        } catch (e: Exception) {
            logger.error("[DMRoomService] Failed to update m.direct: $e")
        }
    }

    /**
     * Select primary room from a list of room IDs (oldest wins).
     */
    private fun selectPrimaryRoom(roomIds: List<String>): String? {
        val roomsWithTs = mutableListOf<RoomWithTimestamp>()

        for (roomId in roomIds) {
            val room = syncEngine.getRoom(roomId)
            if (room != null) {
                // Get creation timestamp
                for (event in room.timeline) {
                    if (event.type == "m.room.create" && event.origin_server_ts != null) {
                        roomsWithTs.add(RoomWithTimestamp(roomId, event.origin_server_ts))
                        break
                    }
                }
            }
        }

        if (roomsWithTs.isEmpty()) {
            // Fall back to first room if no timestamps available
            return roomIds.firstOrNull()
        }

        // Sort by creation timestamp (oldest first)
        roomsWithTs.sortBy { it.creationTs }
        return roomsWithTs[0].roomId
    }

    /**
     * Add a room to the cache for a contact.
     */
    private fun addRoomToCache(contactUserId: String, roomId: String) {
        // Update allRoomsByContact
        if (!allRoomsByContact.containsKey(contactUserId)) {
            allRoomsByContact[contactUserId] = mutableSetOf()
        }
        allRoomsByContact[contactUserId]!!.add(roomId)

        // Update reverse mapping
        contactByRoom[roomId] = contactUserId
    }

    /**
     * Remove a room from all caches.
     */
    private fun removeRoomFromCache(roomId: String) {
        val contactUserId = contactByRoom[roomId]
        if (contactUserId == null) return

        // Remove from allRoomsByContact
        allRoomsByContact[contactUserId]?.remove(roomId)
        if (allRoomsByContact[contactUserId]?.isEmpty() == true) {
            allRoomsByContact.remove(contactUserId)
        }

        // Remove from primaryRoomByContact if it's the primary
        if (primaryRoomByContact[contactUserId] == roomId) {
            primaryRoomByContact.remove(contactUserId)
        }

        // Remove reverse mapping
        contactByRoom.remove(roomId)
    }

    /**
     * Check if a room has the is_direct flag set.
     *
     * Note: is_direct is a boolean in JSON ({"is_direct": true}), not a string.
     * We need to check booleanOrNull, not content (which would give "true" string).
     */
    private fun hasIsDirectFlag(room: SyncRoomState): Boolean {
        for (event in room.timeline) {
            val isDirect = event.content["is_direct"]?.jsonPrimitive?.booleanOrNull
            if (event.type == "m.room.create" && isDirect == true) {
                return true
            }
            if (event.type == "m.room.member" &&
                event.state_key == userId &&
                isDirect == true) {
                return true
            }
        }
        return false
    }

    /**
     * Get DM-related info from a room (is_direct flag and creation timestamp).
     *
     * Note: is_direct is a boolean in JSON ({"is_direct": true}), not a string.
     */
    private fun getRoomDMInfo(room: SyncRoomState): RoomDMInfo {
        var isDirectRoom = false
        var creationTs: Long? = null

        for (event in room.timeline) {
            val isDirect = event.content["is_direct"]?.jsonPrimitive?.booleanOrNull
            if (event.type == "m.room.create") {
                creationTs = event.origin_server_ts
                if (isDirect == true) {
                    isDirectRoom = true
                }
            }
            if (event.type == "m.room.member" &&
                event.state_key == userId &&
                isDirect == true) {
                isDirectRoom = true
            }
        }

        return RoomDMInfo(isDirectRoom, creationTs)
    }

    /**
     * Build a Contact object from room membership info.
     * Works for joined members as well as invited members (DM room with pending invite).
     */
    private fun buildContactFromRoom(roomId: String, contactUserId: String): Contact? {
        val room = syncEngine.getRoom(roomId)
        if (room == null) {
            return null
        }

        val member = room.members[contactUserId]
        if (member != null) {
            return Contact(
                user = User(
                    id = contactUserId,
                    displayName = member.displayName,
                    avatarUrl = member.avatarUrl
                )
            )
        }

        // Member not in room members - use contactUserId as fallback
        // This handles cases where the invite hasn't propagated yet
        val displayName = contactUserId.substringBefore(":").removePrefix("@")
        return Contact(
            user = User(
                id = contactUserId,
                displayName = displayName,
                avatarUrl = null
            )
        )
    }
}

// ============================================================================
// Internal Data Classes
// ============================================================================

private data class CandidateRoom(
    val roomId: String,
    val creationTs: Long,
    val messageCount: Int
)

private data class RoomWithTimestamp(
    val roomId: String,
    val creationTs: Long
)

private data class RoomDMInfo(
    val isDirectRoom: Boolean,
    val creationTs: Long?
)

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
