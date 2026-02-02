package com.wata.integration

import com.wata.client.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end flow tests for WataClient.
 *
 * Tests complete user journeys from login to sending/receiving voice messages.
 * Run against local Conduit server with alice and bob users.
 */
class EndToEndFlowTest {

    private val aliceLogger = TestLogger("AliceClient")
    private val bobLogger = TestLogger("BobClient")
    private lateinit var aliceClient: WataClient
    private lateinit var bobClient: WataClient

    @Before
    fun setup() {
        requireMatrixServerRunning(TEST_HOMESERVER)

        // Create clients
        aliceClient = WataClient(TEST_HOMESERVER, aliceLogger)
        bobClient = WataClient(TEST_HOMESERVER, bobLogger)

        // Login both users
        aliceClient.login(TestUser.ALICE.username, TestUser.ALICE.password)
        bobClient.login(TestUser.BOB.username, TestUser.BOB.password)

        // Start sync
        aliceClient.connect()
        bobClient.connect()
    }

    @After
    fun tearDown() {
        // Disconnect both clients to stop sync threads
        // This is critical - without it, the JVM won't exit because
        // the sync threads are still running
        try {
            aliceClient.disconnect()
        } catch (e: Exception) {
            aliceLogger.error("Error disconnecting Alice: $e")
        }
        try {
            bobClient.disconnect()
        } catch (e: Exception) {
            bobLogger.error("Error disconnecting Bob: $e")
        }
    }

    // ========================================================================
    // Complete Flow Test
    // ========================================================================

    @Test
    fun completeFlow_LoginSyncCreateRoomSendReceive() {
        // Wait for both clients to sync
        waitForCondition(
            description = "Alice to sync",
            condition = { aliceClient.getConnectionState() == ConnectionState.SYNCING },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )
        waitForCondition(
            description = "Bob to sync",
            condition = { bobClient.getConnectionState() == ConnectionState.SYNCING },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        logger.log("Both clients synced successfully")

        // Debug: Check room count before creating
        logger.log("Alice room count before: ${aliceClient.getRoomCount()}")
        logger.log("Bob room count before: ${bobClient.getRoomCount()}")

        // Create a room with Bob (via Alice)
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        logger.log("Created room: $roomId")

        // Debug: Check room count after creating
        logger.log("Alice room count after creation: ${aliceClient.getRoomCount()}")
        logger.log("Alice room IDs: ${aliceClient.getRoomIds().joinToString()}")

        // Force a full sync to pick up the newly created room
        // This is needed because Conduit may not include newly created rooms
        // in incremental syncs immediately
        logger.log("Forcing full sync for Alice...")
        aliceClient.forceFullSync()

        // Wait for Alice (the room creator) to see the room
        // The room should appear in Alice's sync state because she created it
        logger.log("Waiting for Alice to see room $roomId...")
        waitForRoom(aliceClient, roomId, timeoutMs = 120000L, logger = logger)

        // Bob needs to join the room (he was invited but hasn't joined yet)
        // This matches the TypeScript test pattern where participants explicitly join
        logger.log("Bob joining room $roomId...")
        bobClient.joinRoom(roomId)

        // Now wait for Bob to see the room after joining
        logger.log("Waiting for Bob to see room $roomId...")
        waitForRoom(bobClient, roomId, timeoutMs = 120000L, logger = logger)

        logger.log("Room created and synced to both clients")

        // Alice sends a voice message
        val audioData = createFakeAudioData(durationSeconds = 5.0)
        val aliceMessage = aliceClient.sendVoiceMessageToRoom(
            roomId = roomId,
            audio = audioData,
            duration = 5.0
        )

        assertNotNull("Alice's message should have an ID", aliceMessage.id)
        assertEquals("Sender should be Alice", TestUser.ALICE.userId, aliceMessage.sender.id)
        assertEquals("Duration should match", 5.0, aliceMessage.duration, 0.1)

        logger.log("Alice sent message: ${lastN(aliceMessage.id, 12)}")

        // Wait for Bob to receive the message
        waitForCondition(
            description = "Bob to receive Alice's message",
            condition = {
                val convo = bobClient.getConversationByRoomId(roomId)
                convo != null && convo.messages.any { it.id == aliceMessage.id }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Verify Bob received the message
        val bobConversation = bobClient.getConversationByRoomId(roomId)
        assertNotNull("Bob should have the conversation", bobConversation)

        val bobReceivedMessage = bobConversation?.messages?.find { it.id == aliceMessage.id }
        assertNotNull("Bob should have received Alice's message", bobReceivedMessage)
        assertEquals("Sender should be Alice", TestUser.ALICE.userId, bobReceivedMessage?.sender?.id)
        assertFalse("Message should not be marked as played by Bob", bobReceivedMessage?.isPlayed ?: true)

        logger.log("Bob received Alice's message successfully")

        // Bob replies
        val bobAudioData = createFakeAudioData(durationSeconds = 3.0, prefix = "BOB_REPLY")
        val bobMessage = bobClient.sendVoiceMessageToRoom(
            roomId = roomId,
            audio = bobAudioData,
            duration = 3.0
        )

        logger.log("Bob sent reply: ${lastN(bobMessage.id, 12)}")

        // Wait for Alice to receive Bob's reply
        waitForCondition(
            description = "Alice to receive Bob's reply",
            condition = {
                val convo = aliceClient.getConversationByRoomId(roomId)
                convo != null && convo.messages.any { it.id == bobMessage.id }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val aliceReceivedMessage = aliceClient.getConversationByRoomId(roomId)
            ?.messages?.find { it.id == bobMessage.id }
        assertNotNull("Alice should have received Bob's reply", aliceReceivedMessage)
        assertEquals("Sender should be Bob", TestUser.BOB.userId, aliceReceivedMessage?.sender?.id)

        logger.log("Alice received Bob's reply successfully")

        // Both clients should have both messages
        val aliceFinalMessages = aliceClient.getConversationByRoomId(roomId)?.messages ?: emptyList()
        val bobFinalMessages = bobClient.getConversationByRoomId(roomId)?.messages ?: emptyList()

        assertTrue("Alice should have at least 2 messages", aliceFinalMessages.size >= 2)
        assertTrue("Bob should have at least 2 messages", bobFinalMessages.size >= 2)

        logger.log("Complete flow test passed!")
    }

    // ========================================================================
    // Multi-Turn Conversation Test
    // ========================================================================

    @Test
    fun multiTurnConversation_fiveMessagesBackAndForth() {
        // Wait for sync
        waitForSyncBoth()

        // Create room
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        // Have a 5-message conversation
        val expectedTurns = listOf("alice", "bob", "alice", "bob", "alice")
        val sentMessages = mutableListOf<Pair<String, VoiceMessage>>()

        for ((index, sender) in expectedTurns.withIndex()) {
            val client = if (sender == "alice") aliceClient else bobClient
            val receiver = if (sender == "alice") bobClient else aliceClient
            val duration = 2.0 + index * 0.5

            // Send message
            val audioData = createFakeAudioData(
                durationSeconds = duration,
                prefix = "${sender.uppercase()}_MSG_$index"
            )
            val message = client.sendVoiceMessageToRoom(
                roomId = roomId,
                audio = audioData,
                duration = duration
            )
            sentMessages.add(sender to message)
            logger.log("[$sender] Sent: ${lastN(message.id, 12)}")

            // Wait for receiver to get it
            waitForCondition(
                description = "$receiver to receive message $index",
                condition = {
                    val convo = receiver.getConversationByRoomId(roomId)
                    convo != null && convo.messages.any { it.id == message.id }
                },
                timeoutMs = TEST_SYNC_TIMEOUT_MS
            )
        }

        // Verify both clients have all messages
        val aliceMessages = aliceClient.getConversationByRoomId(roomId)?.messages ?: emptyList()
        val bobMessages = bobClient.getConversationByRoomId(roomId)?.messages ?: emptyList()

        assertTrue("Alice should have all 5 messages", aliceMessages.size >= 5)
        assertTrue("Bob should have all 5 messages", bobMessages.size >= 5)

        // Verify all sent messages are present
        for ((sender, message) in sentMessages) {
            assertTrue("Alice should have message ${lastN(message.id, 12)}",
                aliceMessages.any { it.id == message.id })
            assertTrue("Bob should have message ${lastN(message.id, 12)}",
                bobMessages.any { it.id == message.id })
        }

        logger.log("Multi-turn conversation test passed! Both clients have ${aliceMessages.size} messages")
    }

    // ========================================================================
    // Message Ordering Test
    // ========================================================================

    @Test
    fun messagesAreInChronologicalOrder() {
        waitForSyncBoth()

        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        // Send multiple messages rapidly
        val messageCount = 5
        val sentIds = mutableListOf<String>()

        repeat(messageCount) { i ->
            val message = aliceClient.sendVoiceMessageToRoom(
                roomId = roomId,
                audio = createFakeAudioData(durationSeconds = 1.0 + i * 0.1),
                duration = 1.0 + i * 0.1
            )
            sentIds.add(message.id)
        }

        // Wait for all messages to sync
        waitForCondition(
            description = "all messages to sync to Alice",
            condition = {
                val convo = aliceClient.getConversationByRoomId(roomId)
                convo != null && convo.messages.size >= messageCount
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Verify ordering (timeline should be chronological)
        val messages = aliceClient.getConversationByRoomId(roomId)?.messages ?: emptyList()
        val lastMessages = messages.takeLast(messageCount)

        // Messages should be in the order they were sent (by timestamp)
        val timestamps = lastMessages.map { it.timestamp.time }
        assertTrue("Timestamps should be non-decreasing",
            timestamps.zipWithNext().all { (a, b) -> a <= b })

        logger.log("Messages are in correct chronological order")
    }

    // ========================================================================
    // User Identity Test
    // ========================================================================

    @Test
    fun clientsHaveCorrectUserIdentities() {
        // Verify Alice's identity
        val aliceUser = aliceClient.getCurrentUser()
        assertNotNull("Alice should have a user", aliceUser)
        assertEquals("Alice's user ID should match", TestUser.ALICE.userId, aliceUser?.id)

        val aliceWhoami = aliceClient.whoami()
        assertEquals("Alice's whoami should match", TestUser.ALICE.userId, aliceWhoami)

        // Verify Bob's identity
        val bobUser = bobClient.getCurrentUser()
        assertNotNull("Bob should have a user", bobUser)
        assertEquals("Bob's user ID should match", TestUser.BOB.userId, bobUser?.id)

        val bobWhoami = bobClient.whoami()
        assertEquals("Bob's whoami should match", TestUser.BOB.userId, bobWhoami)

        logger.log("User identities verified: Alice=$aliceUser, Bob=$bobUser")
    }

    // ========================================================================
    // Connection State Test
    // ========================================================================

    @Test
    fun connectionStateChanges_toSYNCING() {
        // After login and connect, state should be SYNCING
        assertEquals("Alice should be in SYNCING state",
            ConnectionState.SYNCING, aliceClient.getConnectionState())
        assertEquals("Bob should be in SYNCING state",
            ConnectionState.SYNCING, bobClient.getConnectionState())

        // Disconnect Alice
        aliceClient.disconnect()

        // State should be OFFLINE
        assertEquals("Alice should be in OFFLINE state after disconnect",
            ConnectionState.OFFLINE, aliceClient.getConnectionState())

        // Reconnect
        aliceClient.connect()

        // State should return to SYNCING
        waitForCondition(
            description = "Alice to return to SYNCING",
            condition = { aliceClient.getConnectionState() == ConnectionState.SYNCING },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        logger.log("Connection state transitions work correctly")
    }

    // ========================================================================
    // Room By ID Test
    // ========================================================================

    @Test
    fun getConversationByRoomId_returnsCorrectConversation() {
        waitForSyncBoth()

        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        // Get conversation by room ID
        val aliceConvo = aliceClient.getConversationByRoomId(roomId)
        assertNotNull("Alice should get conversation by room ID", aliceConvo)
        assertEquals("Conversation ID should match", roomId, aliceConvo?.id)
        assertEquals("Conversation should be DM type", ConversationType.DM, aliceConvo?.type)
        assertNotNull("DM should have contact", aliceConvo?.contact)

        val bobConvo = bobClient.getConversationByRoomId(roomId)
        assertNotNull("Bob should get conversation by room ID", bobConvo)
        assertEquals("Conversation ID should match", roomId, bobConvo?.id)

        logger.log("getConversationByRoomId works correctly")
    }

    // ========================================================================
    // Access Token Test
    // ========================================================================

    @Test
    fun getAccessToken_returnsValidToken() {
        val aliceToken = aliceClient.getAccessToken()
        assertNotNull("Alice should have access token", aliceToken)
        assertTrue("Alice's token should not be empty", aliceToken?.isNotEmpty() == true)

        val bobToken = bobClient.getAccessToken()
        assertNotNull("Bob should have access token", bobToken)
        assertTrue("Bob's token should not be empty", bobToken?.isNotEmpty() == true)

        assertNotEquals("Tokens should be different for different users", aliceToken, bobToken)

        logger.log("Access tokens are valid and unique")
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createDMRoom(client: WataClient, targetUserId: String): String {
        // Use WataClient's createDMRoom method which uses the same API instance
        // as the sync engine, ensuring better consistency
        return client.createDMRoom(targetUserId)
    }

    private fun waitForSyncBoth() {
        waitForCondition(
            description = "Alice to sync",
            condition = { aliceClient.getConnectionState() == ConnectionState.SYNCING },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )
        waitForCondition(
            description = "Bob to sync",
            condition = { bobClient.getConnectionState() == ConnectionState.SYNCING },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )
    }

    private fun waitForRoomInBoth(roomId: String) {
        // Wait for Alice to see the room (she's the creator in our tests)
        waitForRoom(aliceClient, roomId, timeoutMs = 120000L, logger = logger)

        // Bob needs to join the room
        bobClient.joinRoom(roomId)

        // Wait for Bob to see the room after joining
        waitForRoom(bobClient, roomId, timeoutMs = 120000L, logger = logger)
    }

    private val logger = TestLogger("E2ETest")
}
