package com.myra.assistant.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue

class AudioEngine(private val context: Context) {

    companion object {
        const val SAMPLE_RATE_MIC = 16000
        const val SAMPLE_RATE_SPEAKER = 24000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE = 1024
    }

    // Callbacks
    var onAudioChunkReady: ((ByteArray) -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    private var isRecording = false
    private var isPlaying = false
    private var isMuted = false
    var isSpeaking = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Start Recording ───────────────────────────
    fun startRecording() {
        if (isRecording) return
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_MIC, CHANNEL_IN, FORMAT
        ).coerceAtLeast(CHUNK_SIZE * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_MIC, CHANNEL_IN, FORMAT, bufferSize
        )

        audioRecord?.startRecording()
        isRecording = true

        scope.launch {
            val buffer = ByteArray(CHUNK_SIZE)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0 && !isMuted && !isSpeaking) {
                    val chunk = buffer.copyOf(read)
                    val rms = calculateRMS(chunk)
                    withContext(Dispatchers.Main) {
                        onAmplitudeChanged?.invoke(rms)
                    }
                    onAudioChunkReady?.invoke(chunk)
                }
            }
        }
    }

    // ─── Start Playback ────────────────────────────
    fun startPlayback() {
        if (isPlaying) return
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_SPEAKER, CHANNEL_OUT, FORMAT
        ).coerceAtLeast(CHUNK_SIZE * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_SPEAKER)
                    .setChannelMask(CHANNEL_OUT)
                    .setEncoding(FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isPlaying = true

        scope.launch {
            while (isPlaying) {
                val chunk = playbackQueue.poll()
                if (chunk != null) {
                    if (!isSpeaking) {
                        isSpeaking = true
                        withContext(Dispatchers.Main) { onSpeakingStarted?.invoke() }
                    }
                    audioTrack?.write(chunk, 0, chunk.size)
                } else {
                    if (isSpeaking) {
                        isSpeaking = false
                        withContext(Dispatchers.Main) { onSpeakingStopped?.invoke() }
                    }
                    delay(20)
                }
            }
        }
    }

    // ─── Queue Audio for Playback ──────────────────
    fun queueAudio(pcmBytes: ByteArray) {
        playbackQueue.offer(pcmBytes)
    }

    // ─── Interrupt / Clear Queue ───────────────────
    fun interrupt() {
        playbackQueue.clear()
        audioTrack?.flush()
        if (isSpeaking) {
            isSpeaking = false
            CoroutineScope(Dispatchers.Main).launch { onSpeakingStopped?.invoke() }
        }
    }

    // ─── Mute Toggle ──────────────────────────────
    fun setMuted(muted: Boolean) { isMuted = muted }

    // ─── RMS Calculation ──────────────────────────
    private fun calculateRMS(buffer: ByteArray): Float {
        var sum = 0.0
        for (i in buffer.indices step 2) {
            if (i + 1 < buffer.size) {
                val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                sum += sample * sample
            }
        }
        val rms = Math.sqrt(sum / (buffer.size / 2))
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    // ─── Release ──────────────────────────────────
    fun release() {
        isRecording = false
        isPlaying = false
        scope.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        playbackQueue.clear()
    }
}
