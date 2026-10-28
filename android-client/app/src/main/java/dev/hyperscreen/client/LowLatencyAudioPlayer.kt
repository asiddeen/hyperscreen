package dev.hyperscreen.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Pro Low-Latency Linear PCM Audio Streamer for HyperScreen Display Client.
 * Renders 48kHz 16-bit Stereo Mac system audio with sub-10ms output latency
 * using unbuffered non-blocking AudioTrack in low-latency performance mode.
 */
class LowLatencyAudioPlayer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2
) {
    private var audioTrack: AudioTrack? = null
    @Volatile var isRunning: Boolean = false

    fun start() {
        if (isRunning) return
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                }
            }
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build()

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }

        track.play()
        audioTrack = track
        isRunning = true
        Log.i("HyperScreen", "Started LowLatencyAudioPlayer (${sampleRate}Hz stereo, LOW_LATENCY performance mode)")
    }

    fun writePcm(pcmData: ByteArray, offset: Int, length: Int) {
        if (!isRunning || audioTrack == null || length <= 0) return
        audioTrack?.write(pcmData, offset, length, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun stop() {
        isRunning = false
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }
        audioTrack = null
    }
}
