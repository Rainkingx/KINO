package com.rainkingx.kino;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Sits in the player's sound path and passes the sound through untouched, noting how loud the dialogue band is in every
 * 0.1 s of the video (for SubSync). A first difference of the mono mix stands in for a speech band filter: it damps the
 * rumble and bass of music and effects. Up to 4 hours; written on the playback thread, read on any.
 */
final class SpeechMeter extends BaseAudioProcessor {
    final float[] level = new float[4 * 3600 * 1000 / SubSync.BIN_MS];
    private long baseUs, frames;
    private double acc;
    private int accFrames;
    private float prev;

    SpeechMeter() {
        Arrays.fill(level, -1);
    }

    @Override protected AudioFormat onConfigure(AudioFormat in) {
        return in.encoding == C.ENCODING_PCM_16BIT ? in : AudioFormat.NOT_SET;   // other formats pass by without a meter
    }

    @Override protected void onFlush(AudioProcessor.StreamMetadata m) {
        baseUs = m.positionOffsetUs;   // where the sound continues from (a seek, a new start)
        frames = 0;
        acc = 0;
        accFrames = 0;
    }

    @Override public void queueInput(ByteBuffer in) {
        if (!in.hasRemaining()) return;   // an empty buffer is the shared EMPTY_BUFFER, as our own output would be: copying it onto itself throws
        int ch = inputAudioFormat.channelCount, rate = inputAudioFormat.sampleRate, binFrames = rate * SubSync.BIN_MS / 1000;
        ByteBuffer src = in.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        while (src.remaining() >= 2 * ch) {
            int mix = 0;
            for (int c = 0; c < ch; c++) mix += src.getShort();
            float x = mix / (32768f * ch), y = x - 0.97f * prev;
            prev = x;
            acc += y * y;
            if (++accFrames == binFrames) {
                long bin = (baseUs / 1000 + frames * 1000 / rate) / SubSync.BIN_MS;
                if (bin >= 0 && bin < level.length) level[(int) bin] = (float) (acc / accFrames);
                frames += accFrames;
                acc = 0;
                accFrames = 0;
            }
        }
        int n = in.remaining();
        ByteBuffer out = replaceOutputBuffer(n);
        out.put(in);
        out.flip();
    }

    /** A copy of what was measured, for SubSync on another thread. */
    float[] snapshot() {
        return level.clone();
    }
}
