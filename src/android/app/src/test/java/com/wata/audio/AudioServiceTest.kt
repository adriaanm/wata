/**
 * AudioService Unit Tests
 *
 * Tests for AudioService state management, recording result structure,
 * and utility functions. Note: Actual audio recording/playback requires
 * Android device/emulator testing - these tests focus on state logic.
 *
 * Phase 2 tests: Record voice, encode to Ogg Opus, upload to Matrix,
 * download and play back.
 */

package com.wata.audio

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AudioService
 *
 * Tests state management, recording results, and utility functions.
 * Actual AudioRecord/MediaPlayer testing requires device/emulator.
 */
class AudioServiceTest {

    // Note: AudioService requires a valid Android Context for initialization.
    // Since we can't mock Context in unit tests (requires Robolectric or device),
    // we focus on testing RecordingResult and formatDuration utility.
    //
    // Integration tests with actual AudioService should be done on device/emulator.

    // ========================================================================
    // RecordingResult Tests
    // ========================================================================

    @Test
    fun recordingResult_hasCorrectDefaultMimeType() {
        val result = RecordingResult(
            uri = "/path/to/audio.ogg",
            duration = 5000,
            size = 1024
        )

        assertEquals("Default mime type should be audio/ogg; codecs=opus",
            "audio/ogg; codecs=opus", result.mimeType)
    }

    @Test
    fun recordingResult_canBeCreatedWithCustomMimeType() {
        val customMimeType = "audio/ogg"
        val result = RecordingResult(
            uri = "/path/to/audio.ogg",
            duration = 3000,
            size = 512,
            mimeType = customMimeType
        )

        assertEquals("Custom mime type should be preserved",
            customMimeType, result.mimeType)
    }

    @Test
    fun recordingResult_containsAllFields() {
        val uri = "/cache/voice_1234567890.ogg"
        val duration = 7500L
        val size = 2048L

        val result = RecordingResult(
            uri = uri,
            duration = duration,
            size = size
        )

        assertEquals("URI should match", uri, result.uri)
        assertEquals("Duration should match", duration, result.duration)
        assertEquals("Size should match", size, result.size)
        assertEquals("MIME type should be opus", "audio/ogg; codecs=opus", result.mimeType)
    }

    // ========================================================================
    // formatDuration Tests (requires AudioService instance)
    // ========================================================================

    // Note: formatDuration is an instance method on AudioService.
    // We can test it with a simple wrapper class or use the full path.
    // Since AudioService needs a real Context, we skip formatDuration tests here.
    // They should be tested in Android instrumentation tests.

    // ========================================================================
    // Constants Verification Tests
    // ========================================================================

    @Test
    fun recordingResult_mimeTypeMatchesMatrixRequirement() {
        // Matrix expects "audio/ogg; codecs=opus" for Ogg Opus files
        val result = RecordingResult(
            uri = "test.ogg",
            duration = 1000,
            size = 100
        )

        assertTrue("MIME type should indicate Ogg container",
            result.mimeType.contains("audio/ogg"))
        assertTrue("MIME type should indicate Opus codec",
            result.mimeType.contains("opus", ignoreCase = true))
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    fun recordingResult_handlesZeroDuration() {
        val result = RecordingResult(
            uri = "test.ogg",
            duration = 0,
            size = 100
        )

        assertEquals("Zero duration should be stored", 0L, result.duration)
    }

    @Test
    fun recordingResult_handlesZeroSize() {
        val result = RecordingResult(
            uri = "test.ogg",
            duration = 1000,
            size = 0
        )

        assertEquals("Zero size should be stored", 0L, result.size)
    }

    @Test
    fun recordingResult_handlesVeryLargeDuration() {
        val largeDuration = Long.MAX_VALUE
        val result = RecordingResult(
            uri = "test.ogg",
            duration = largeDuration,
            size = 100
        )

        assertEquals("Large duration should be stored", largeDuration, result.duration)
    }

    @Test
    fun recordingResult_handlesVeryLargeSize() {
        val largeSize = Long.MAX_VALUE
        val result = RecordingResult(
            uri = "test.ogg",
            duration = 1000,
            size = largeSize
        )

        assertEquals("Large size should be stored", largeSize, result.size)
    }

    @Test
    fun recordingResult_equality_sameValues() {
        val result1 = RecordingResult(
            uri = "/path/to/audio.ogg",
            duration = 5000,
            size = 1024
        )

        val result2 = RecordingResult(
            uri = "/path/to/audio.ogg",
            duration = 5000,
            size = 1024
        )

        // Data class should provide equals() automatically
        assertEquals("Equal RecordingResults should be equal", result1, result2)
    }

    @Test
    fun recordingResult_equality_differentUri() {
        val result1 = RecordingResult(
            uri = "/path/to/audio1.ogg",
            duration = 5000,
            size = 1024
        )

        val result2 = RecordingResult(
            uri = "/path/to/audio2.ogg",
            duration = 5000,
            size = 1024
        )

        assertNotEquals("Different URIs should make results unequal", result1, result2)
    }

    @Test
    fun recordingResult_copy_createsNewInstance() {
        val original = RecordingResult(
            uri = "/path/to/audio.ogg",
            duration = 5000,
            size = 1024
        )

        val copy = original.copy(uri = "/new/path.ogg")

        assertEquals("Copied instance should have new URI", "/new/path.ogg", copy.uri)
        assertEquals("Copied instance should preserve duration", original.duration, copy.duration)
        assertEquals("Copied instance should preserve size", original.size, copy.size)
    }
}
