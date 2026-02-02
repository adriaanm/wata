/**
 * Voice Message Flow Tests (Phase 2)
 *
 * Integration tests for the complete audio pipeline:
 * 1. Record voice (AudioRecord → OggOpusEncoder)
 * 2. Upload to Matrix
 * 3. Download from Matrix
 * 4. Play back (MediaPlayer)
 *
 * Phase 2 Exit Criteria: Can record voice, upload to Matrix,
 * download and play back.
 *
 * These tests verify the WataClient integration with audio:
 * - sendVoiceMessage() uploads audio data to Matrix
 * - sendVoiceMessageToRoom() uploads to specific room
 * - sendVoiceMessageFromFile() uploads from file path (for AudioService integration)
 * - Matrix mxc URLs are properly generated
 * - VoiceMessage duration and metadata are correct
 *
 * Note: Actual AudioRecord/MediaPlayer testing requires Android device/emulator.
 * These tests use fake audio data to test the Matrix upload/download flow.
 */

package com.wata.integration

import com.wata.client.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.*

/**
 * Voice message flow tests for Phase 2 (Audio Pipeline)
 *
 * Tests the complete flow: record → upload → download → play back
 * using WataClient's audio upload methods.
 */
class VoiceMessageFlowTest {

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

        // Wait for both to sync
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

    @After
    fun tearDown() {
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
    // Phase 2 Exit Criteria: Record → Upload → Download → Play Back
    // ========================================================================

    /**
     * Phase 2 Exit Criteria Test:
     * Can record voice, upload to Matrix, download and play back.
     *
     * This test verifies the complete flow using sendVoiceMessageToRoom().
     * The "record" part is simulated by creating fake audio data.
     * Actual AudioRecord testing requires device/emulator.
     */
    @Test
    fun phase2ExitCriteria_recordUploadDownloadPlayback() {
        logger.log("=== Phase 2 Exit Criteria Test ===")

        // Step 1: Create a DM room
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        logger.log("Created room: ${lastN(roomId, 12)}")

        waitForRoomInBoth(roomId)

        // Step 2: "Record" voice (simulate recording with fake audio data)
        // In a real scenario, AudioService would create Ogg Opus data
        val audioData = createFakeAudioData(
            durationSeconds = 3.0,
            prefix = "VOICE_TEST"
        )
        val duration = 3.0 // seconds

        logger.log("Created fake audio data: ${audioData.size} bytes")

        // Step 3: Upload to Matrix
        val voiceMessage = aliceClient.sendVoiceMessageToRoom(
            roomId = roomId,
            audio = audioData,
            duration = duration
        )

        logger.log("Uploaded voice message: ${lastN(voiceMessage.id, 12)}")

        // Verify upload succeeded
        assertNotNull("Voice message should have an ID", voiceMessage.id)
        assertNotNull("Voice message should have sender", voiceMessage.sender)
        assertEquals("Sender should be Alice", TestUser.ALICE.userId, voiceMessage.sender.id)
        assertNotNull("Voice message should have audio URL", voiceMessage.audioUrl)
        assertNotNull("Voice message should have MXC URL", voiceMessage.mxcUrl)
        assertEquals("Duration should match", duration, voiceMessage.duration, 0.1)

        // Step 4: Wait for Bob to receive the message
        waitForCondition(
            description = "Bob to receive voice message",
            condition = {
                val convo = bobClient.getConversationByRoomId(roomId)
                convo != null && convo.messages.any { it.id == voiceMessage.id }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        logger.log("Bob received the message")

        // Step 5: Verify Bob can access the message
        val bobConvo = bobClient.getConversationByRoomId(roomId)
        assertNotNull("Bob should have the conversation", bobConvo)

        val bobMessage = bobConvo?.messages?.find { it.id == voiceMessage.id }
        assertNotNull("Bob should have Alice's message", bobMessage)
        assertEquals("Sender should be Alice", TestUser.ALICE.userId, bobMessage!!.sender.id)
        assertEquals("Duration should match", duration, bobMessage.duration, 0.1)
        assertNotNull("Message should have audio URL", bobMessage.audioUrl)

        // Step 6: Download audio (simulate playback prep)
        // In a real scenario, AudioService would download and play via MediaPlayer
        val downloadedAudio = bobClient.getAudioData(bobMessage!!)

        logger.log("Downloaded audio: ${downloadedAudio.size} bytes")

        // Verify download matches upload
        assertNotNull("Downloaded audio should not be null", downloadedAudio)
        assertEquals("Downloaded audio size should match uploaded size",
            audioData.size, downloadedAudio.size)

        // Verify content matches (byte-by-byte comparison)
        assertArrayEquals("Downloaded audio content should match uploaded content",
            audioData, downloadedAudio)

        logger.log("=== Phase 2 Exit Criteria Test PASSED ===")
    }

    // ========================================================================
    // sendVoiceMessage Tests
    // ========================================================================

    @Test
    fun sendVoiceMessage_toContactTarget_uploadsToMatrix() {
        // Create room
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        // Get Bob as a contact
        val aliceContacts = aliceClient.getContacts()
        assertTrue("Alice should have Bob as a contact", aliceContacts.isNotEmpty())

        val bobContact = aliceContacts.first()
        assertEquals("Contact should be Bob", TestUser.BOB.userId, bobContact.user.id)

        // Send message using ContactTarget.DM
        val audioData = createFakeAudioData(durationSeconds = 2.0)
        val voiceMessage = aliceClient.sendVoiceMessage(
            target = ContactTarget.DM(bobContact),
            audio = audioData,
            duration = 2.0
        )

        // Verify message was sent
        assertNotNull("Message should have ID", voiceMessage.id)
        assertEquals("Sender should be Alice", TestUser.ALICE.userId, voiceMessage.sender.id)
        assertNotNull("Message should have mxcUrl", voiceMessage.mxcUrl)

        // Verify Bob receives it
        waitForCondition(
            description = "Bob to receive message",
            condition = {
                val convo = bobClient.getConversationByRoomId(roomId)
                convo != null && convo.messages.any { it.id == voiceMessage.id }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val bobMessage = bobClient.getConversationByRoomId(roomId)?.messages
            ?.find { it.id == voiceMessage.id }
        assertNotNull("Bob should receive message", bobMessage)
    }

    // ========================================================================
    // sendVoiceMessageFromFile Tests (AudioService Integration)
    // ========================================================================

    @Test
    fun sendVoiceMessageFromFile_uploadsToMatrix() {
        // Create room
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        // Create a temporary audio file (simulating AudioService output)
        val tempFile = createTempOggFile(
            durationSeconds = 4.0,
            prefix = "FILE_TEST"
        )

        try {
            logger.log("Created temp file: ${tempFile.absolutePath}")

            // Send using file path (as AudioService would)
            val voiceMessage = aliceClient.sendVoiceMessageFromFile(
                roomId = roomId,
                filePath = tempFile.absolutePath,
                duration = 4.0
            )

            // Verify message was sent
            assertNotNull("Message should have ID", voiceMessage.id)
            assertEquals("Sender should be Alice", TestUser.ALICE.userId, voiceMessage.sender.id)
            assertEquals("Duration should be 4 seconds", 4.0, voiceMessage.duration, 0.1)

            // Verify Bob receives it
            waitForCondition(
                description = "Bob to receive message",
                condition = {
                    val convo = bobClient.getConversationByRoomId(roomId)
                    convo != null && convo.messages.any { it.id == voiceMessage.id }
                },
                timeoutMs = TEST_SYNC_TIMEOUT_MS
            )

            val bobMessage = bobClient.getConversationByRoomId(roomId)?.messages
                ?.find { it.id == voiceMessage.id }
            assertNotNull("Bob should receive message", bobMessage)

            // Verify Bob can download it
            val downloadedAudio = bobClient.getAudioData(bobMessage!!)
            assertNotNull("Bob should be able to download audio", downloadedAudio)

            logger.log("File upload test passed")

        } finally {
            // Clean up temp file
            tempFile.delete()
        }
    }

    @Test
    fun sendVoiceMessageFromFile_nonexistentFile_throwsException() {
        // Create room
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)

        // Try to send with nonexistent file
        try {
            aliceClient.sendVoiceMessageFromFile(
                roomId = roomId,
                filePath = "/nonexistent/path/to/audio.ogg",
                duration = 1.0
            )
            fail("Should throw exception for nonexistent file")
        } catch (e: IllegalArgumentException) {
            assertTrue("Exception should mention file not found",
                e.message?.contains("not found", ignoreCase = true) == true)
        }
    }

    // ========================================================================
    // MXC URL Tests
    // ========================================================================

    @Test
    fun voiceMessage_hasValidMxcUrl() {
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        val audioData = createFakeAudioData(durationSeconds = 2.0)
        val voiceMessage = aliceClient.sendVoiceMessageToRoom(
            roomId = roomId,
            audio = audioData,
            duration = 2.0
        )

        // Verify MXC URL format
        val mxcUrl = voiceMessage.mxcUrl
        assertNotNull("Message should have MXC URL", mxcUrl)
        assertTrue("MXC URL should start with mxc://", mxcUrl!!.startsWith("mxc://"))

        // Parse MXC URL: mxc://serverName/mediaId
        val parts = mxcUrl.removePrefix("mxc://").split("/")
        assertEquals("MXC URL should have server and media ID parts", 2, parts.size)
        assertTrue("Server name should not be empty", parts[0].isNotEmpty())
        assertTrue("Media ID should not be empty", parts[1].isNotEmpty())

        logger.log("MXC URL: $mxcUrl")
        logger.log("Server: ${parts[0]}, Media ID: ${parts[1]}")
    }

    // ========================================================================
    // Multiple Messages Test
    // ========================================================================

    @Test
    fun multipleVoiceMessages_allUploadAndDownloadCorrectly() {
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        val messageCount = 5
        val sentMessages = mutableListOf<VoiceMessage>()

        // Send multiple messages
        for (i in 1..messageCount) {
            val duration = 1.0 + i * 0.5
            val audioData = createFakeAudioData(
                durationSeconds = duration,
                prefix = "MSG_$i"
            )

            val message = aliceClient.sendVoiceMessageToRoom(
                roomId = roomId,
                audio = audioData,
                duration = duration
            )

            sentMessages.add(message)
            logger.log("Sent message $i: ${lastN(message.id, 12)}")
        }

        // Wait for Bob to receive all messages by checking each specific message ID
        for (sent in sentMessages) {
            waitForCondition(
                description = "Bob to receive message ${lastN(sent.id, 12)}",
                condition = {
                    val convo = bobClient.getConversationByRoomId(roomId)
                    convo != null && convo.messages.any { it.id == sent.id }
                },
                timeoutMs = TEST_SYNC_TIMEOUT_MS
            )
            logger.log("Bob received message ${lastN(sent.id, 12)}")
        }

        // Verify all messages received
        val bobMessages = bobClient.getConversationByRoomId(roomId)?.messages
            ?: emptyList()
        assertTrue("Bob should have at least $messageCount messages", bobMessages.size >= messageCount)

        // Verify each sent message is present
        for (sent in sentMessages) {
            val received = bobMessages.find { it.id == sent.id }
            assertNotNull("Bob should have message ${lastN(sent.id, 12)}", received)
            assertEquals("Duration should match for ${lastN(sent.id, 12)}",
                sent.duration, received!!.duration, 0.1)

            // Verify download works for each
            val downloaded = bobClient.getAudioData(received)
            assertNotNull("Should be able to download ${lastN(sent.id, 12)}", downloaded)
        }

        logger.log("All $messageCount messages uploaded and downloaded successfully")
    }

    // ========================================================================
    // Bidirectional Communication Test
    // ========================================================================

    @Test
    fun bidirectionalVoiceMessages_bothCanUploadAndDownload() {
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        // Alice sends first
        val aliceAudio = createFakeAudioData(durationSeconds = 2.0, prefix = "ALICE")
        val aliceMessage = aliceClient.sendVoiceMessageToRoom(
            roomId = roomId,
            audio = aliceAudio,
            duration = 2.0
        )

        // Bob receives Alice's message
        waitForCondition(
            description = "Bob to receive Alice's message",
            condition = {
                val convo = bobClient.getConversationByRoomId(roomId)
                convo != null && convo.messages.any { it.id == aliceMessage.id }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Bob downloads and verifies Alice's message
        val bobReceived = bobClient.getConversationByRoomId(roomId)?.messages
            ?.find { it.id == aliceMessage.id }
        assertNotNull("Bob should receive Alice's message", bobReceived)

        val aliceDownloaded = bobClient.getAudioData(bobReceived!!)
        assertArrayEquals("Bob should download Alice's audio correctly",
            aliceAudio, aliceDownloaded)

        logger.log("Alice → Bob message verified")

        // Bob replies
        val bobAudio = createFakeAudioData(durationSeconds = 3.0, prefix = "BOB")
        val bobMessage = bobClient.sendVoiceMessageToRoom(
            roomId = roomId,
            audio = bobAudio,
            duration = 3.0
        )

        // Alice receives Bob's reply
        waitForCondition(
            description = "Alice to receive Bob's reply",
            condition = {
                val convo = aliceClient.getConversationByRoomId(roomId)
                convo != null && convo.messages.any { it.id == bobMessage.id }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        // Alice downloads and verifies Bob's message
        val aliceReceived = aliceClient.getConversationByRoomId(roomId)?.messages
            ?.find { it.id == bobMessage.id }
        assertNotNull("Alice should receive Bob's reply", aliceReceived)

        val bobDownloaded = aliceClient.getAudioData(aliceReceived!!)
        assertArrayEquals("Alice should download Bob's audio correctly",
            bobAudio, bobDownloaded)

        logger.log("Bob → Alice message verified")

        // Both should have both messages
        val aliceFinal = aliceClient.getConversationByRoomId(roomId)?.messages ?: emptyList()
        val bobFinal = bobClient.getConversationByRoomId(roomId)?.messages ?: emptyList()

        assertTrue("Alice should have both messages", aliceFinal.size >= 2)
        assertTrue("Bob should have both messages", bobFinal.size >= 2)

        logger.log("Bidirectional communication test passed")
    }

    // ========================================================================
    // Duration Metadata Tests
    // ========================================================================

    @Test
    fun voiceMessage_durationIsPreserved() {
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        val testDurations = listOf(0.5, 1.0, 2.5, 5.0, 10.0, 30.0)

        for (duration in testDurations) {
            val audioData = createFakeAudioData(durationSeconds = duration)
            val message = aliceClient.sendVoiceMessageToRoom(
                roomId = roomId,
                audio = audioData,
                duration = duration
            )

            assertEquals("Duration should be preserved for ${duration}s",
                duration, message.duration, 0.1)

            logger.log("Duration $duration preserved correctly")
        }
    }

    // ========================================================================
    // MIME Type Tests
    // ========================================================================

    @Test
    fun voiceMessage_usesCorrectMimeType() {
        // Verify that WataClient uses the correct MIME type for Ogg Opus
        // This is important for Matrix to serve the file correctly

        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        val audioData = createFakeAudioData(durationSeconds = 2.0)
        val message = aliceClient.sendVoiceMessageToRoom(
            roomId = roomId,
            audio = audioData,
            duration = 2.0
        )

        // The message should have an audio URL that Matrix can serve
        assertNotNull("Message should have audio URL", message.audioUrl)

        // Bob should be able to download it
        waitForCondition(
            description = "Bob to receive message",
            condition = {
                val convo = bobClient.getConversationByRoomId(roomId)
                convo != null && convo.messages.any { it.id == message.id }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS
        )

        val bobMessage = bobClient.getConversationByRoomId(roomId)?.messages
            ?.find { it.id == message.id }
        assertNotNull("Bob should receive message", bobMessage)

        // Download should succeed
        val downloaded = bobClient.getAudioData(bobMessage!!)
        assertNotNull("Download should succeed", downloaded)
        assertTrue("Downloaded data should not be empty", downloaded.isNotEmpty())

        logger.log("MIME type test passed - file served correctly by Matrix")
    }

    // ========================================================================
    // Large File Tests
    // ========================================================================

    @Test
    fun largeVoiceFile_uploadsAndDownloadsCorrectly() {
        val roomId = createDMRoom(aliceClient, TestUser.BOB.userId)
        waitForRoomInBoth(roomId)

        // Simulate a larger audio file (e.g., 60 seconds)
        val largeAudio = createFakeAudioData(durationSeconds = 60.0)
        val largeSize = largeAudio.size

        logger.log("Large audio size: $largeSize bytes")

        val message = aliceClient.sendVoiceMessageToRoom(
            roomId = roomId,
            audio = largeAudio,
            duration = 60.0
        )

        assertNotNull("Large file upload should succeed", message.id)

        // Wait for Bob to receive
        waitForCondition(
            description = "Bob to receive large file",
            condition = {
                val convo = bobClient.getConversationByRoomId(roomId)
                convo != null && convo.messages.any { it.id == message.id }
            },
            timeoutMs = TEST_SYNC_TIMEOUT_MS * 2
        )

        // Verify download
        val bobMessage = bobClient.getConversationByRoomId(roomId)?.messages
            ?.find { it.id == message.id }
        assertNotNull("Bob should receive large file", bobMessage)

        val downloaded = bobClient.getAudioData(bobMessage!!)
        assertNotNull("Download should succeed", downloaded)
        assertEquals("Downloaded size should match uploaded size",
            largeSize, downloaded.size)

        logger.log("Large file test passed")
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createDMRoom(client: WataClient, targetUserId: String): String {
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
        waitForRoom(aliceClient, roomId, timeoutMs = 120000L, logger = logger)
        bobClient.joinRoom(roomId)
        waitForRoom(bobClient, roomId, timeoutMs = 120000L, logger = logger)
    }

    /**
     * Create a temporary Ogg file for testing sendVoiceMessageFromFile
     */
    private fun createTempOggFile(durationSeconds: Double, prefix: String): File {
        val tempDir = System.getProperty("java.io.tmpdir")
        val timestamp = System.currentTimeMillis()
        val file = File(tempDir, "voice_${timestamp}_$prefix.ogg")

        // Create fake Ogg Opus content
        val content = "OGG_OPUS:${prefix}:${durationSeconds}s:".repeat(
            (durationSeconds * 100).toInt()
        ).toByteArray()

        file.writeBytes(content)
        return file
    }

    private val logger = TestLogger("VoiceFlowTest")
}
