package com.wata.integration

import com.wata.client.Logger
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

// ============================================================================
// Test Constants
// ============================================================================

const val TEST_HOMESERVER = "http://localhost:8008"
const val TEST_TIMEOUT_MS = 30000L
const val TEST_SYNC_TIMEOUT_MS = 60000L

data class TestUser(
    val username: String,
    val password: String,
    val userId: String
) {
    companion object {
        val ALICE = TestUser("alice", "testpass123", "@alice:localhost")
        val BOB = TestUser("bob", "testpass123", "@bob:localhost")
    }
}

// ============================================================================
// Test Logger
// ============================================================================

/**
 * Logger implementation for tests that outputs to console
 */
class TestLogger(private val prefix: String = "") : Logger {
    override fun log(message: String) {
        if (prefix.isNotEmpty()) {
            println("[$prefix] $message")
        } else {
            println(message)
        }
    }

    override fun warn(message: String) {
        if (prefix.isNotEmpty()) {
            println("[$prefix] WARN: $message")
        } else {
            println("WARN: $message")
        }
    }

    override fun error(message: String) {
        if (prefix.isNotEmpty()) {
            System.err.println("[$prefix] ERROR: $message")
        } else {
            System.err.println("ERROR: $message")
        }
    }
}

// ============================================================================
// Test Utilities
// ============================================================================

/**
 * Poll until a condition is true, with exponential backoff.
 *
 * @param description Human-readable description for error messages
 * @param condition Function that returns true when condition is met
 * @param timeoutMs Maximum time to wait
 * @param pollMs Initial poll interval
 */
fun waitForCondition(
    description: String,
    condition: () -> Boolean,
    timeoutMs: Long = TEST_TIMEOUT_MS,
    pollMs: Long = 200L
) {
    val startTime = System.currentTimeMillis()
    var delay = pollMs

    while (System.currentTimeMillis() - startTime < timeoutMs) {
        if (condition()) return
        Thread.sleep(delay)
        delay = min(delay * 1.3, 2000.0).toLong()
    }

    throw AssertionError("Timed out waiting for: $description (after ${timeoutMs}ms)")
}

/**
 * Wait for a specific number of items to be available.
 *
 * @param description What we're waiting for
 * @param supplier Function that returns current count
 * @param minCount Minimum count required
 * @param timeoutMs Maximum time to wait
 */
fun <T> waitForCount(
    description: String,
    supplier: () -> Collection<T>,
    minCount: Int,
    timeoutMs: Long = TEST_TIMEOUT_MS
): List<T> {
    val startTime = System.currentTimeMillis()
    var delay = 200L

    while (System.currentTimeMillis() - startTime < timeoutMs) {
        val items = supplier()
        if (items.size >= minCount) {
            return items.toList()
        }
        Thread.sleep(delay)
        delay = min(delay * 1.3, 2000.0).toLong()
    }

    val actualCount = supplier().size
    throw AssertionError("Timed out waiting for $minCount $description, got $actualCount")
}

/**
 * Assert that a condition becomes true within the timeout.
 */
fun assertEventuallyTrue(
    description: String,
    condition: () -> Boolean,
    timeoutMs: Long = TEST_TIMEOUT_MS
) {
    waitForCondition(description, condition, timeoutMs)
}

/**
 * Create fake audio data for testing voice messages.
 *
 * @param durationSeconds Duration of the fake audio in seconds
 * @param prefix Optional prefix for the data
 * @return Fake audio data as byte array
 */
fun createFakeAudioData(
    durationSeconds: Double = 5.0,
    prefix: String = "TEST_AUDIO"
): ByteArray {
    val size = (durationSeconds * 1000).toInt().coerceAtLeast(32)
    val content = "$prefix:${durationSeconds}s:".repeat(size / 20).take(size)
    return content.toByteArray()
}

// ============================================================================
// Matrix Server Utilities
// ============================================================================

/**
 * Check if local Conduit server is running and accessible.
 *
 * @return true if server is responding
 */
fun isMatrixServerRunning(
    homeserverUrl: String = TEST_HOMESERVER,
    timeoutMs: Long = 5000L
): Boolean {
    return try {
        val url = java.net.URL("$homeserverUrl/_matrix/client/versions")
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = timeoutMs.toInt()
        connection.readTimeout = timeoutMs.toInt()
        connection.requestMethod = "GET"

        val responseCode = connection.responseCode
        connection.disconnect()

        responseCode == 200
    } catch (e: Exception) {
        false
    }
}

/**
 * Assert that Matrix server is running, fail with helpful message if not.
 */
fun requireMatrixServerRunning(homeserverUrl: String = TEST_HOMESERVER) {
    val running = isMatrixServerRunning(homeserverUrl)
    if (!running) {
        throw AssertionError(
            """Matrix server not running at $homeserverUrl

            Start the server with:
              cd test/docker && ./setup.sh

            Or for Conduit via Docker:
              docker run -p 8008:8008 matrixconduit/conduit:latest
            """.trimIndent()
        )
    }
}

// ============================================================================
// Test Result Utilities
// ============================================================================

/**
 * Retry a block of code with exponential backoff.
 *
 * @param maxAttempts Maximum number of attempts
 * @param initialDelayMs Initial delay between attempts
 * @param block Code to execute
 * @return Result of successful execution
 */
fun <T> retryWithBackoff(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1000L,
    block: () -> T
): T {
    var lastException: Throwable? = null
    var delay = initialDelayMs

    repeat(maxAttempts - 1) {
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            Thread.sleep(delay)
            delay *= 2
        }
    }

    // Last attempt
    try {
        return block()
    } catch (e: Exception) {
        throw lastException ?: e
    }
}

/**
 * Time a block of code execution.
 *
 * @param block Code to execute
 * @return Pair of result and elapsed milliseconds
 */
fun <T> timeIt(block: () -> T): Pair<T, Long> {
    val startTime = System.currentTimeMillis()
    val result = block()
    val elapsed = System.currentTimeMillis() - startTime
    return result to elapsed
}

// ============================================================================
// String Utilities
// ============================================================================

/**
 * Truncate a string to a maximum length, adding "..." if truncated.
 */
fun truncate(str: String, maxLength: Int = 50): String {
    return if (str.length <= maxLength) {
        str
    } else {
        str.take(maxLength - 3) + "..."
    }
}

/**
 * Get the last N characters of a string (useful for event IDs, room IDs).
 */
fun lastN(str: String?, n: Int): String {
    if (str == null) return "(null)"
    return str.takeLast(n)
}

// ============================================================================
// Wait for Room Helper
// ============================================================================

/**
 * Wait for a room to appear in a WataClient's state.
 *
 * This helper polls for the room to appear in both the conversation API
 * and checks internal state. Uses exponential backoff for robustness.
 *
 * Based on TypeScript TestClient.waitForRoom which combines event listeners
 * with polling fallback.
 *
 * @param client The WataClient to check
 * @param roomId The room ID to wait for
 * @param timeoutMs Maximum time to wait (default: 90s for eventual consistency)
 * @param logger Optional logger for debug output
 */
fun waitForRoom(
    client: com.wata.client.WataClient,
    roomId: String,
    timeoutMs: Long = 90000L,
    logger: Logger? = null
) {
    val startTime = System.currentTimeMillis()
    var checkCount = 0
    var pollDelay = 200L
    val maxPollDelay = 2000L
    var lastSyncState: String? = null

    logger?.log("[waitForRoom] Waiting for room ${lastN(roomId, 12)}...")

    while (System.currentTimeMillis() - startTime < timeoutMs) {
        checkCount++

        // Check if room is available via conversation API
        val conversation = client.getConversationByRoomId(roomId)
        if (conversation != null) {
            logger?.log("[waitForRoom] Room found via getConversationByRoomId (after $checkCount checks, ${System.currentTimeMillis() - startTime}ms)")
            return
        }

        // Also check if the room is known as a DM room
        // This handles the case where the room exists but isn't yet a full conversation
        if (client.isDMRoom(roomId)) {
            logger?.log("[waitForRoom] Room found via isDMRoom (after $checkCount checks, ${System.currentTimeMillis() - startTime}ms)")
            return
        }

        // Log progress and sync state every 10 checks
        if (checkCount % 10 == 0) {
            val syncState = client.getConnectionState().toString()
            if (syncState != lastSyncState) {
                lastSyncState = syncState
                logger?.log("[waitForRoom] Still waiting for room... (check $checkCount, elapsed: ${System.currentTimeMillis() - startTime}ms, syncState: $syncState)")
            } else {
                logger?.log("[waitForRoom] Still waiting for room... (check $checkCount, elapsed: ${System.currentTimeMillis() - startTime}ms)")
            }
        }

        // Exponential backoff
        Thread.sleep(pollDelay)
        pollDelay = (pollDelay * 1.3).toLong().coerceAtMost(maxPollDelay)
    }

    // Timeout - provide helpful error message
    val elapsed = System.currentTimeMillis() - startTime
    throw AssertionError(
        """Room ${lastN(roomId, 12)} not found after ${elapsed}ms ($checkCount checks).
           |
           |Client state at timeout:
           |  - Connection state: ${client.getConnectionState()}
           |  - Current user: ${client.getCurrentUser()?.id}
           |  - Is DM room: ${client.isDMRoom(roomId)}
           |
           |Troubleshooting:
           |  - Is the Conduit server running?
           |  - Was the room created successfully?
           |  - Is the sync engine running?
           |  - Try increasing TEST_SYNC_TIMEOUT_MS in TestHelpers.kt
        """.trimMargin()
    )
}
