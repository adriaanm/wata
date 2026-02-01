package com.wata.integration

import com.wata.client.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for SyncEngine.
 *
 * Tests sync loop, event parsing, and state management.
 * Run against local Conduit server.
 */
class SyncEngineTest {

    private lateinit var api: MatrixApi
    private lateinit var syncEngine: SyncEngine
    private val logger = TestLogger("SyncEngineTest")

    // Track events received
    private var syncedCount = 0
    private var roomUpdatedCount = 0
    private var timelineEventCount = 0
    private var receiptCount = 0
    private var membershipChangeCount = 0
    private val syncedTokens = mutableListOf<String>()
    private val timelineEvents = mutableListOf<Pair<String, MatrixEvent>>()
    private val receiptEvents = mutableListOf<Triple<String, String, Set<String>>>()
    private val membershipEvents = mutableListOf<Triple<String, String, String>>()

    @Before
    fun setup() {
        requireMatrixServerRunning(TEST_HOMESERVER)

        // Reset counters
        syncedCount = 0
        roomUpdatedCount = 0
        timelineEventCount = 0
        receiptCount = 0
        membershipChangeCount = 0
        syncedTokens.clear()
        timelineEvents.clear()
        receiptEvents.clear()
        membershipEvents.clear()

        // Create API and login
        api = MatrixApi(TEST_HOMESERVER, logger)
        api.login(TestUser.ALICE.username, TestUser.ALICE.password)

        // Create sync engine
        syncEngine = SyncEngine(api, logger)
        syncEngine.setUserId(TestUser.ALICE.userId)

        // Register event handlers
        syncEngine.addEventHandler(object : SyncEngineEvents {
            override fun onSynced(nextBatch: String) {
                syncedCount++
                syncedTokens.add(nextBatch)
                logger.log("[Test] onSynced: ${lastN(nextBatch, 8)}")
            }

            override fun onRoomUpdated(roomId: String, room: SyncRoomState) {
                roomUpdatedCount++
                logger.log("[Test] onRoomUpdated: ${lastN(roomId, 12)}, name=${room.name}")
            }

            override fun onTimelineEvent(roomId: String, event: MatrixEvent) {
                timelineEventCount++
                timelineEvents.add(roomId to event)
                logger.log("[Test] onTimelineEvent: ${lastN(roomId, 12)}, type=${event.type}")
            }

            override fun onReceiptUpdated(roomId: String, eventId: String, userIds: Set<String>) {
                receiptCount++
                receiptEvents.add(Triple(roomId, eventId, userIds))
                logger.log("[Test] onReceiptUpdated: ${lastN(eventId, 12)}, users=${userIds.size}")
            }

            override fun onMembershipChanged(roomId: String, userId: String, membership: String) {
                membershipChangeCount++
                membershipEvents.add(Triple(roomId, userId, membership))
                logger.log("[Test] onMembershipChanged: $userId -> $membership")
            }

            override fun onAccountDataUpdated(type: String, content: kotlinx.serialization.json.JsonObject) {
                logger.log("[Test] onAccountDataUpdated: $type")
            }

            override fun onError(error: Throwable) {
                logger.error("[Test] onError: ${error.message}")
            }
        })
    }

    // ========================================================================
    // Initial Sync Tests
    // ========================================================================

    @Test
    fun initialSync_completesAndReturnsNextBatchToken() {
        syncEngine.start()

        // Wait for initial sync to complete
        waitForCondition(
            description = "initial sync to complete",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Verify sync state
        assertTrue("Should have synced at least once", syncedCount >= 1)
        assertTrue("Should have next_batch token", syncedTokens.isNotEmpty())

        val nextBatch = syncEngine.getNextBatch()
        assertNotNull("SyncEngine should store next_batch", nextBatch)
        assertEquals("Next batch should match last synced token", syncedTokens.last(), nextBatch)

        // Stop sync engine
        syncEngine.stop()

        logger.log("Initial sync completed with token: ${lastN(nextBatch, 8)}")
    }

    @Test
    fun initialSync_discoversRooms() {
        syncEngine.start()

        // Wait for sync
        waitForCondition(
            description = "sync to complete",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Get rooms
        val rooms = syncEngine.getRooms()
        assertTrue("Should discover some rooms", rooms.isNotEmpty())

        rooms.forEach { room ->
            logger.log("Room: ${lastN(room.roomId, 12)}, name=${room.name}, members=${room.members.size}")
        }

        syncEngine.stop()
    }

    // ========================================================================
    // Event Parsing Tests
    // ========================================================================

    @Test
    fun syncParses_m_room_name_events() {
        // Create a room with a name
        val roomName = "Test Room ${System.currentTimeMillis()}"
        val createResponse = api.createRoom(
            request = CreateRoomRequest(
                name = roomName,
                visibility = "private",
                preset = "private_chat"
            )
        )

        // Start sync
        syncEngine.start()

        // Wait for room to appear in sync
        waitForCondition(
            description = "room to appear in sync state",
            condition = {
                val room = syncEngine.getRoom(createResponse.room_id)
                room != null && room.name == roomName
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Verify room name
        val room = syncEngine.getRoom(createResponse.room_id)
        assertNotNull("Room should be found", room)
        assertEquals("Room name should match", roomName, room!!.name)

        syncEngine.stop()
        logger.log("Room name correctly parsed: $roomName")
    }

    @Test
    fun syncParses_m_room_member_events() {
        // Create a room and invite Bob
        val createResponse = api.createRoom(
            request = CreateRoomRequest(
                name = "Member Test Room",
                visibility = "private",
                preset = "private_chat",
                invite = listOf(TestUser.BOB.userId)
            )
        )

        // Start sync
        syncEngine.start()

        // Wait for room to appear
        waitForCondition(
            description = "room with members to appear",
            condition = {
                val room = syncEngine.getRoom(createResponse.room_id)
                room != null && room.members.containsKey(TestUser.ALICE.userId)
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Verify members
        val room = syncEngine.getRoom(createResponse.room_id)
        assertNotNull("Room should be found", room)
        assertTrue("Alice should be a member", room!!.members.containsKey(TestUser.ALICE.userId))

        val aliceMember = room.members[TestUser.ALICE.userId]
        assertEquals("Alice should be joined", "join", aliceMember?.membership)
        assertEquals("Alice should have correct user ID", TestUser.ALICE.userId, aliceMember?.userId)

        syncEngine.stop()
        logger.log("Room member correctly parsed: ${aliceMember?.displayName}")
    }

    @Test
    fun syncParses_m_room_message_events() {
        // Create a room
        val roomId = api.createRoom(
            request = CreateRoomRequest(
                name = "Message Test Room",
                visibility = "private",
                preset = "private_chat"
            )
        ).room_id

        // Start sync
        syncEngine.start()

        // Wait for initial sync
        waitForCondition(
            description = "initial sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Send a message
        val messageContent = "Test message at ${System.currentTimeMillis()}"
        val sendResponse = api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.text")
                put("body", messageContent)
            }
        )

        // Wait for message to appear in timeline
        waitForCondition(
            description = "message to appear in timeline",
            condition = {
                val room = syncEngine.getRoom(roomId)
                room != null && room.timeline.any { it.event_id == sendResponse.event_id }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Verify message
        val room = syncEngine.getRoom(roomId)
        val messageEvent = room?.timeline?.find { it.event_id == sendResponse.event_id }
        assertNotNull("Message event should be found", messageEvent)
        assertEquals("Event type should be m.room.message", "m.room.message", messageEvent?.type)
        assertEquals("Sender should be Alice", TestUser.ALICE.userId, messageEvent?.sender)

        syncEngine.stop()
        logger.log("Message event correctly parsed: ${lastN(sendResponse.event_id, 12)}")
    }

    @Test
    fun syncParses_m_receipt_events() {
        // Create a room
        val roomId = api.createRoom(
            request = CreateRoomRequest(
                name = "Receipt Test Room",
                visibility = "private",
                preset = "private_chat"
            )
        ).room_id

        // Start sync
        syncEngine.start()

        // Wait for initial sync
        waitForCondition(
            description = "initial sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Send a message
        val sendResponse = api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.text")
                put("body", "Test message for receipt")
            }
        )

        // Send read receipt
        api.sendReadReceipt(roomId, sendResponse.event_id)

        // Wait for receipt to be processed (may need multiple syncs)
        waitForCondition(
            description = "receipt to be processed",
            condition = {
                val room = syncEngine.getRoom(roomId)
                room != null && room.readReceipts.containsKey(sendResponse.event_id)
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Verify receipt
        val room = syncEngine.getRoom(roomId)
        val receipts = room?.readReceipts?.get(sendResponse.event_id)
        assertNotNull("Receipts should exist for event", receipts)
        assertTrue("Alice should have read the message", receipts?.contains(TestUser.ALICE.userId) == true)

        syncEngine.stop()
        logger.log("Receipt correctly parsed for event: ${lastN(sendResponse.event_id, 12)}")
    }

    // ========================================================================
    // Incremental Sync Tests
    // ========================================================================

    @Test
    fun incrementalSync_updatesSinceToken() {
        syncEngine.start()

        // Wait for initial sync
        waitForCondition(
            description = "initial sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val firstToken = syncEngine.getNextBatch()
        assertNotNull("Should have next_batch token", firstToken)

        // Wait for another sync cycle
        waitForCondition(
            description = "second sync cycle",
            condition = { syncedCount >= 2 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val secondToken = syncEngine.getNextBatch()
        assertNotNull("Should still have next_batch token", secondToken)

        // Tokens should be different
        assertNotEquals("Tokens should change between syncs", firstToken, secondToken)

        syncEngine.stop()
        logger.log("Incremental sync: ${lastN(firstToken, 8)} -> ${lastN(secondToken, 8)}")
    }

    @Test
    fun incrementalSync_processesNewEvents() {
        // Create a room
        val roomId = api.createRoom(
            request = CreateRoomRequest(
                name = "Incremental Test Room",
                visibility = "private",
                preset = "private_chat"
            )
        ).room_id

        syncEngine.start()

        // Wait for initial sync
        waitForCondition(
            description = "initial sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Get initial message count
        val initialRoom = syncEngine.getRoom(roomId)
        val initialCount = initialRoom?.timeline?.size ?: 0

        // Send a new message
        api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.text")
                put("body", "New message after initial sync")
            }
        )

        // Wait for new event to appear
        waitForCondition(
            description = "new message to appear",
            condition = {
                val room = syncEngine.getRoom(roomId)
                (room?.timeline?.size ?: 0) > initialCount
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val newRoom = syncEngine.getRoom(roomId)
        val newCount = newRoom?.timeline?.size ?: 0
        assertTrue("Should have more events after incremental sync", newCount > initialCount)

        syncEngine.stop()
        logger.log("Incremental sync processed new events: $initialCount -> $newCount")
    }

    // ========================================================================
    // State Access Tests
    // ========================================================================

    @Test
    fun getRoom_returnsNullForNonExistentRoom() {
        syncEngine.start()

        waitForCondition(
            description = "initial sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val room = syncEngine.getRoom("!nonexistent:localhost")
        assertNull("Non-existent room should return null", room)

        syncEngine.stop()
    }

    @Test
    fun getRooms_returnsAllJoinedRooms() {
        // Create a couple of rooms
        val room1 = api.createRoom(
            request = CreateRoomRequest(name = "Test Room 1", visibility = "private")
        ).room_id

        val room2 = api.createRoom(
            request = CreateRoomRequest(name = "Test Room 2", visibility = "private")
        ).room_id

        syncEngine.start()

        // Wait for rooms to appear
        waitForCondition(
            description = "rooms to appear",
            condition = {
                val rooms = syncEngine.getRooms()
                rooms.any { it.roomId == room1 } && rooms.any { it.roomId == room2 }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val rooms = syncEngine.getRooms()
        assertTrue("Should find room1", rooms.any { it.roomId == room1 })
        assertTrue("Should find room2", rooms.any { it.roomId == room2 })

        syncEngine.stop()
        logger.log("Found ${rooms.size} rooms in sync state")
    }

    // ========================================================================
    // Lifecycle Tests
    // ========================================================================

    @Test
    fun stop_haltsSyncLoop() {
        syncEngine.start()

        // Wait for at least one sync
        waitForCondition(
            description = "first sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val syncCountAtStop = syncedCount

        // Stop the sync engine
        syncEngine.stop()

        // Wait a bit to ensure no more syncs occur
        Thread.sleep(2000)

        // Count should not have increased
        assertEquals("No more syncs should occur after stop", syncCountAtStop, syncedCount)

        logger.log("Sync engine stopped correctly")
    }

    @Test
    fun clear_removesAllState() {
        syncEngine.start()

        waitForCondition(
            description = "initial sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Verify we have state
        val roomsBefore = syncEngine.getRooms()
        assertTrue("Should have rooms before clear", roomsBefore.isNotEmpty())

        // Clear state
        syncEngine.clear()

        // Verify state is cleared
        val roomsAfter = syncEngine.getRooms()
        assertTrue("Should have no rooms after clear", roomsAfter.isEmpty())
        assertNull("Next batch should be null after clear", syncEngine.getNextBatch())

        logger.log("Sync state cleared correctly")
    }

    @Test
    fun canResumeSync_withSavedToken() {
        syncEngine.start()

        // Wait for sync
        waitForCondition(
            description = "initial sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val savedToken = syncEngine.getNextBatch()
        assertNotNull("Should have token to save", savedToken)

        // Stop and clear
        syncEngine.stop()
        syncEngine.clear()

        // Resume with saved token
        syncEngine.setNextBatch(savedToken!!)
        syncEngine.start()

        // Wait for sync with resume
        waitForCondition(
            description = "resumed sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val newToken = syncEngine.getNextBatch()
        assertNotNull("Should have new token after resumed sync", newToken)

        syncEngine.stop()
        logger.log("Resumed sync from token: ${lastN(savedToken, 8)}")
    }

    // ========================================================================
    // Event Handler Tests
    // ========================================================================

    @Test
    fun timelineEvents_emittedForNewMessages() {
        // Create a room
        val roomId = api.createRoom(
            request = CreateRoomRequest(
                name = "Event Handler Test Room",
                visibility = "private"
            )
        ).room_id

        syncEngine.start()

        // Wait for initial sync
        waitForCondition(
            description = "initial sync",
            condition = { syncedCount >= 1 },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Clear any previous timeline events
        timelineEvents.clear()

        // Send a message
        api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.text")
                put("body", "Test event handler")
            }
        )

        // Wait for timeline event
        waitForCondition(
            description = "timeline event to be emitted",
            condition = { timelineEvents.any { it.first == roomId } },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        assertTrue("Should have received timeline event", timelineEvents.isNotEmpty())

        syncEngine.stop()
        logger.log("Timeline event emitted correctly")
    }
}
