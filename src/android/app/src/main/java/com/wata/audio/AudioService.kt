/**
 * AudioService - Handles audio recording and playback for voice messages.
 *
 * Recording: AudioRecord (PCM) → OggOpusEncoder → Ogg Opus file
 * Playback: MediaPlayer → Ogg Opus file/URL
 *
 * @package com.wata.audio
 */

package com.wata.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import io.element.android.opusencoder.OggOpusEncoder
import io.element.android.opusencoder.configuration.SampleRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "AudioService"

// Audio configuration constants (matching TS version)
private const val SAMPLE_RATE = 16000
private const val CHANNELS = 1 // Mono
private const val BITS_PER_SAMPLE = 16
private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

// Frame size for Opus encoding (60ms at 16kHz = 960 samples)
private const val FRAME_SIZE = 960
private const val PROGRESS_UPDATE_INTERVAL = 100L // ms

/**
 * Result of a recording session
 */
data class RecordingResult(
    val uri: String,
    val duration: Long,
    val size: Long,
    val mimeType: String = "audio/ogg; codecs=opus"
)

/**
 * Audio service for recording and playing voice messages
 */
class AudioService(private val context: Context) {

    // Recording state
    private var audioRecord: AudioRecord? = null
    private var opusEncoder: OggOpusEncoder? = null
    private var recordingJob: Job? = null
    private var recordingStartTime: Long = 0
    private var recordingFile: File? = null

    // Playback state
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null

    // State flows
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _recordingProgress = MutableStateFlow(0L)
    val recordingProgress: StateFlow<Long> = _recordingProgress.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0 to 0) // current to duration
    val playbackProgress: StateFlow<Pair<Int, Int>> = _playbackProgress.asStateFlow()

    /**
     * Start recording audio to Ogg Opus format
     */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startRecording() {
        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return
        }

        Log.i(TAG, "Starting recording at ${SAMPLE_RATE}Hz")

        // Create temp file for recording
        val cacheDir = context.cacheDir
        val timestamp = System.currentTimeMillis()
        recordingFile = File(cacheDir, "voice_$timestamp.ogg")

        // Initialize Opus encoder
        opusEncoder = OggOpusEncoder.create()
        val result = opusEncoder!!.init(recordingFile!!.absolutePath, SampleRate.Rate16kHz)
        if (result != 0) {
            Log.e(TAG, "Failed to initialize Opus encoder: $result")
            releaseEncoder()
            throw RuntimeException("Failed to initialize Opus encoder")
        }

        // Set bitrate to 64kbps (good for voice)
        opusEncoder!!.setBitrate(64000)

        // Calculate buffer size
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Ensure buffer is at least large enough for one Opus frame
        val frameBufferSize = FRAME_SIZE * 2 // 2 bytes per sample
        val actualBufferSize = maxOf(bufferSize, frameBufferSize * 2)

        // Create AudioRecord
        try {
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                AudioRecord.Builder()
                    .setAudioSource(AUDIO_SOURCE)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(actualBufferSize)
                    .build()
            } else {
                AudioRecord(
                    AUDIO_SOURCE,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    actualBufferSize
                )
            }

            audioRecord!!.startRecording()
            recordingStartTime = System.currentTimeMillis()
            _isRecording.value = true

            // Start recording coroutine
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(actualBufferSize / 2) // 2 bytes per short

                while (_isRecording.value) {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Encode frame to Opus
                        // Encode in chunks of FRAME_SIZE samples
                        var offset = 0
                        while (offset < read) {
                            val samplesToEncode = minOf(FRAME_SIZE, read - offset)
                            val frame = buffer.sliceArray(offset until offset + samplesToEncode)

                            val encodeResult = opusEncoder!!.encode(frame, samplesToEncode)
                            if (encodeResult < 0) {
                                Log.w(TAG, "Encode returned $encodeResult")
                            }

                            offset += samplesToEncode
                        }
                    }
                }
            }

            // Start progress update coroutine
            startRecordingProgress()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseRecorder()
            releaseEncoder()
            throw e
        }
    }

    /**
     * Stop recording and return the result
     */
    fun stopRecording(): RecordingResult {
        if (!_isRecording.value) {
            Log.w(TAG, "Not recording")
            throw IllegalStateException("Not recording")
        }

        Log.i(TAG, "Stopping recording")

        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        // Stop recording progress
        _recordingProgress.value = 0

        // Calculate duration
        val duration = System.currentTimeMillis() - recordingStartTime

        // Release resources
        releaseRecorder()
        releaseEncoder()

        // Get file info
        val file = recordingFile ?: throw IllegalStateException("No recording file")
        val result = RecordingResult(
            uri = file.absolutePath,
            duration = duration,
            size = file.length(),
            mimeType = "audio/ogg; codecs=opus"
        )

        Log.i(TAG, "Recording complete: ${result.duration}ms, ${result.size} bytes")

        return result
    }

    /**
     * Cancel recording without saving
     */
    fun cancelRecording() {
        if (!_isRecording.value) return

        Log.i(TAG, "Canceling recording")

        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
        _recordingProgress.value = 0

        // Delete temp file
        recordingFile?.delete()
        recordingFile = null

        releaseRecorder()
        releaseEncoder()
    }

    /**
     * Start playback from a URI (file path or URL)
     */
    fun startPlayback(uri: String) {
        if (_isPlaying.value) {
            stopPlayback()
        }

        Log.i(TAG, "Starting playback: $uri")

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            setDataSource(uri)
            prepareAsync()

            setOnPreparedListener {
                Log.i(TAG, "MediaPlayer prepared, duration: $duration")
                start()
                _isPlaying.value = true
                startPlaybackProgress()
            }

            setOnCompletionListener {
                Log.i(TAG, "Playback complete")
                stopPlayback()
            }

            setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                _isPlaying.value = false
                playbackJob?.cancel()
                true
            }
        }
    }

    /**
     * Stop playback
     */
    fun stopPlayback() {
        if (!_isPlaying.value) return

        Log.i(TAG, "Stopping playback")

        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
        _playbackProgress.value = 0 to 0

        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Pause playback
     */
    fun pausePlayback() {
        if (!_isPlaying.value) return

        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                Log.i(TAG, "Playback paused")
            }
        }
    }

    /**
     * Resume playback
     */
    fun resumePlayback() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                Log.i(TAG, "Playback resumed")
            }
        }
    }

    /**
     * Seek to position in milliseconds
     */
    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
    }

    /**
     * Format duration in milliseconds to M:SS string
     */
    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    // Private methods

    private fun startRecordingProgress() {
        recordingJob = CoroutineScope(Dispatchers.Default).launch {
            while (_isRecording.value) {
                val progress = System.currentTimeMillis() - recordingStartTime
                _recordingProgress.value = progress
                delay(PROGRESS_UPDATE_INTERVAL)
            }
        }
    }

    private fun startPlaybackProgress() {
        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            while (_isPlaying.value) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        val current = player.currentPosition
                        val duration = player.duration
                        _playbackProgress.value = current to duration
                    }
                }
                delay(PROGRESS_UPDATE_INTERVAL)
            }
        }
    }

    private fun releaseRecorder() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    private fun releaseEncoder() {
        try {
            opusEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing OpusEncoder", e)
        }
        opusEncoder = null
    }

    /**
     * Clean up resources when service is destroyed
     */
    fun destroy() {
        Log.i(TAG, "Destroying AudioService")
        cancelRecording()
        stopPlayback()
    }
}
