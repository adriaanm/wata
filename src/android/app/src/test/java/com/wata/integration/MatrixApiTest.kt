package com.wata.integration

import com.wata.client.MatrixApi
import com.wata.client.SyncParams
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for MatrixApi HTTP layer.
 *
 * Tests individual Matrix API endpoints against a local Conduit server.
 * Verifies request/response parsing and authentication.
 */
class MatrixApiTest {

    private lateinit var api: MatrixApi
    private val logger = TestLogger("MatrixApiTest")

    @Before
    fun setup() {
        requireMatrixServerRunning(TEST_HOMESERVER)
        api = MatrixApi(TEST_HOMESERVER, logger)
        api.login(TestUser.ALICE.username, TestUser.ALICE.password)
    }

    // ========================================================================
    // Sync Endpoint Tests
    // ========================================================================

    @Test
    fun sync_returnsNextBatchToken() {
        val response = api.sync(
            params = SyncParams(timeout = 5000)
        )

        assertNotNull("Sync response should not be null", response)
        assertNotNull("next_batch should not be null", response.next_batch)
        assertTrue("next_batch should not be empty", response.next_batch.isNotEmpty())

        logger.log("Initial sync next_batch: ${lastN(response.next_batch, 8)}")
    }

    @Test
    fun sync_returnsRoomsData() {
        // First, ensure there's at least one room by syncing
        val firstResponse = api.sync(params = SyncParams(timeout = 5000))
        val nextBatch = firstResponse.next_batch

        // Second sync should return incremental data
        val response = api.sync(
            params = SyncParams(
                timeout = 0,
                since = nextBatch
            )
        )

        assertNotNull("Response should have rooms", response.rooms)

        val rooms = response.rooms
        if (rooms != null) {
            logger.log("Joined rooms: ${rooms.join?.size ?: 0}")
            logger.log("Invited rooms: ${rooms.invite?.size ?: 0}")
            logger.log("Left rooms: ${rooms.leave?.size ?: 0}")
        }
    }

    @Test
    fun incrementalSync_usesSinceToken() {
        // Initial sync
        val firstResponse = api.sync(params = SyncParams(timeout = 5000))
        val nextBatch = firstResponse.next_batch

        // Wait a bit
        Thread.sleep(500)

        // Incremental sync
        val secondResponse = api.sync(
            params = SyncParams(timeout = 0, since = nextBatch)
        )

        assertNotNull("Second response should have next_batch", secondResponse.next_batch)
        assertNotEquals("next_batch should change", nextBatch, secondResponse.next_batch)

        logger.log("Incremental sync: ${lastN(nextBatch, 8)} -> ${lastN(secondResponse.next_batch, 8)}")
    }

    // ========================================================================
    // Room Operations Tests
    // ========================================================================

    @Test
    fun createRoom_returnsRoomId() {
        val response = api.createRoom(
            request = com.wata.client.CreateRoomRequest(
                name = "Test Room ${System.currentTimeMillis()}",
                visibility = "private",
                preset = "private_chat"
            )
        )

        assertNotNull("Response should not be null", response)
        assertNotNull("room_id should not be null", response.room_id)
        assertTrue("room_id should start with !", response.room_id.startsWith("!"))

        logger.log("Created room: ${lastN(response.room_id, 12)}")
    }

    @Test
    fun createDMRoom_withIsDirectFlag() {
        val roomId = api.createRoom(
            request = com.wata.client.CreateRoomRequest(
                name = "DM Test",
                visibility = "private",
                preset = "trusted_private_chat",
                is_direct = true,
                invite = listOf(TestUser.BOB.userId)
            )
        )

        assertTrue("Room ID should be valid", roomId.room_id.startsWith("!"))
        logger.log("Created DM room: ${lastN(roomId.room_id, 12)}")
    }

    @Test
    fun joinRoom_byId() {
        // First create a room
        val createResponse = api.createRoom(
            request = com.wata.client.CreateRoomRequest(
                name = "Join Test Room",
                visibility = "private",
                preset = "private_chat"
            )
        )

        // Login as Bob and try to join
        val bobApi = MatrixApi(TEST_HOMESERVER, TestLogger("BobApi"))
        bobApi.login(TestUser.BOB.username, TestUser.BOB.password)

        // Invite Bob to the room
        api.inviteToRoom(
            roomId = createResponse.room_id,
            request = com.wata.client.InviteRequest(user_id = TestUser.BOB.userId)
        )

        // Bob joins the room
        val joinResponse = bobApi.joinRoom(createResponse.room_id)
        assertEquals("Joined room ID should match", createResponse.room_id, joinResponse.room_id)

        logger.log("Bob joined room: ${lastN(joinResponse.room_id, 12)}")
    }

    // ========================================================================
    // Messaging Tests
    // ========================================================================

    @Test
    fun sendMessage_returnsEventId() {
        // Create a test room
        val roomId = api.createRoom(
            request = com.wata.client.CreateRoomRequest(
                name = "Message Test Room",
                visibility = "private",
                preset = "private_chat"
            )
        ).room_id

        // Send a text message
        val response = api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.text")
                put("body", "Test message from Kotlin integration test")
            }
        )

        assertNotNull("Response should not be null", response)
        assertNotNull("event_id should not be null", response.event_id)
        assertTrue("event_id should start with $", response.event_id.startsWith("$"))

        logger.log("Sent message event: ${lastN(response.event_id, 12)}")
    }

    @Test
    fun sendAudioMessage_event() {
        // Create a test room
        val roomId = api.createRoom(
            request = com.wata.client.CreateRoomRequest(
                name = "Audio Test Room",
                visibility = "private",
                preset = "private_chat"
            )
        ).room_id

        // Send an m.audio message (without actual upload for this test)
        val response = api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.audio")
                put("body", "Test voice message")
                put("url", "mxc://example.com/test123")
                put("info", buildJsonObject {
                    put("duration", 5000)  // 5 seconds in ms
                    put("mimetype", "audio/mp4")
                    put("size", 12345)
                })
            }
        )

        assertTrue("event_id should be valid", response.event_id.startsWith("$"))
        logger.log("Sent audio message event: ${lastN(response.event_id, 12)}")
    }

    // ========================================================================
    // Media Upload Tests
    // ========================================================================

    @Test
    fun uploadMedia_returnsMxcUrl() {
        val testData = createFakeAudioData(durationSeconds = 3.0)

        val response = api.uploadMedia(
            data = testData,
            contentType = "audio/mp4",
            filename = "test-audio.m4a"
        )

        assertNotNull("Response should not be null", response)
        assertNotNull("content_uri should not be null", response.content_uri)
        assertTrue("content_uri should be MXC URL", response.content_uri.startsWith("mxc://"))

        logger.log("Uploaded media: ${response.content_uri}")
    }

    @Test
    fun uploadAndDownloadMedia_roundtrip() {
        val testData = "Hello Matrix Media Repository!".toByteArray()

        // Upload
        val uploadResponse = api.uploadMedia(
            data = testData,
            contentType = "text/plain",
            filename = "test.txt"
        )

        // Download
        val downloadedData = api.downloadMedia(uploadResponse.content_uri)

        // Verify
        assertArrayEquals("Downloaded data should match uploaded", testData, downloadedData)

        logger.log("Media roundtrip successful: ${uploadResponse.content_uri}")
    }

    // ========================================================================
    // Read Receipt Tests
    // ========================================================================

    @Test
    fun sendReadReceipt() {
        // Create a test room
        val roomId = api.createRoom(
            request = com.wata.client.CreateRoomRequest(
                name = "Receipt Test Room",
                visibility = "private",
                preset = "private_chat"
            )
        ).room_id

        // Send a message
        val messageResponse = api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.text")
                put("body", "Test message for receipt")
            }
        )

        // Send read receipt
        api.sendReadReceipt(roomId, messageResponse.event_id)

        // No exception means success
        logger.log("Sent read receipt for event: ${lastN(messageResponse.event_id, 12)}")
    }

    // ========================================================================
    // Profile Tests
    // ========================================================================

    @Test
    fun getUserProfile() {
        val profile = api.getProfile(TestUser.ALICE.userId)

        assertNotNull("Profile should not be null", profile)
        // Display name and avatar_url may be null if not set
        logger.log("Profile for ${TestUser.ALICE.userId}: displayname=${profile.displayname}, avatar=${profile.avatar_url}")
    }

    @Test
    fun setDisplayName() {
        val newDisplayName = "Alice Test ${System.currentTimeMillis()}"

        // Set display name
        api.setDisplayName(TestUser.ALICE.userId, newDisplayName)

        // Verify by getting profile
        val profile = api.getProfile(TestUser.ALICE.userId)
        assertEquals("Display name should be updated", newDisplayName, profile.displayname)

        logger.log("Set display name to: $newDisplayName")
    }

    // ========================================================================
    // Redaction Tests
    // ========================================================================

    @Test
    fun redactEvent() {
        // Create a test room
        val roomId = api.createRoom(
            request = com.wata.client.CreateRoomRequest(
                name = "Redact Test Room",
                visibility = "private",
                preset = "private_chat"
            )
        ).room_id

        // Send a message
        val messageResponse = api.sendMessage(
            roomId = roomId,
            eventType = "m.room.message",
            content = buildJsonObject {
                put("msgtype", "m.text")
                put("body", "This message will be redacted")
            }
        )

        // Redact the message
        val redactResponse = api.redactEvent(
            roomId = roomId,
            eventId = messageResponse.event_id,
            reason = "Test redaction"
        )

        assertEquals("Redact event ID should match", messageResponse.event_id, redactResponse.event_id)

        logger.log("Redacted event: ${lastN(messageResponse.event_id, 12)}")
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Test
    fun requestToNonExistentRoom_returns404() {
        val exception = try {
            api.sendMessage(
                roomId = "!nonexistent:localhost",
                eventType = "m.room.message",
                content = buildJsonObject {
                    put("msgtype", "m.text")
                    put("body", "This should fail")
                }
            )
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("Should throw exception for non-existent room", exception)
        assertTrue(
            "Exception should mention 404 or room not found",
            exception?.message?.contains("404", ignoreCase = true) == true ||
            exception?.message?.contains("Unknown room", ignoreCase = true) == true
        )

        logger.log("Correctly handled non-existent room error")
    }

    @Test
    fun invalidMxcUrlForDownload_throwsException() {
        val exception = try {
            api.downloadMedia("mxc://invalid/server")
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("Should throw exception for invalid MXC URL", exception)

        logger.log("Correctly handled invalid MXC URL")
    }
}
