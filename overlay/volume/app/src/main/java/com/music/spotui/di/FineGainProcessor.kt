// Myspoti AI patch set, built on Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// New file written for this patch set; see NOTICE.md at the root of the patch repository.
package com.music.spotui.di

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** The fine volume (0.01 to 1), shared with the audio thread: every [FineGainProcessor] reads it. */
object FineGain {
    @Volatile
    var level: Float = 1f
}

/**
 * The last stage of the player's audio chain: multiplies the sound by [FineGain.level], on both
 * decks of AutoMix and across crossfades alike, so none of the places that set a player's volume needs
 * to know about it. The gain glides (about 15 ms) so a step of the volume does not click.
 */
@UnstableApi
class FineGainProcessor : BaseAudioProcessor() {
    private var floatSamples = false
    private var channels = 0
    private var gain = 1f
    private var glide = 0.01f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        val supported = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT
        if (!supported || inputAudioFormat.channelCount < 1 || inputAudioFormat.sampleRate <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        floatSamples = encoding == C.ENCODING_PCM_FLOAT
        channels = inputAudioFormat.channelCount
        glide = 1f - exp(-1f / (0.015f * inputAudioFormat.sampleRate))
        return inputAudioFormat
    }

    override fun onFlush() {
        gain = FineGain.level
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val target = FineGain.level
        if (gain >= 0.9999f && target >= 0.9999f) {
            gain = 1f
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }
        val frameBytes = (if (floatSamples) 4 else 2) * channels
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * frameBytes)
        inputBuffer.order(ByteOrder.nativeOrder())
        repeat(frames) {
            gain += (target - gain) * glide
            if (abs(target - gain) < 0.0002f) gain = target
            for (c in 0 until channels) {
                if (floatSamples) {
                    out.putFloat(inputBuffer.getFloat() * gain)
                } else {
                    out.putShort((inputBuffer.getShort() * gain).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        out.flip()
    }
}
