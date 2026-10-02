package com.liskovsoft.smartyoutubetv2.common.aidub;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class AiDubPcmTapAudioProcessor implements AudioProcessor {
    private int sampleRateHz = -1;
    private int channelCount = -1;
    private int encoding = -1;
    private ByteBuffer buffer = EMPTY_BUFFER;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private boolean inputEnded;

    @Override
    public boolean configure(int sampleRateHz, int channelCount, @C.Encoding int encoding)
            throws UnhandledFormatException {
        boolean changed = this.sampleRateHz != sampleRateHz
                || this.channelCount != channelCount
                || this.encoding != encoding;
        this.sampleRateHz = sampleRateHz;
        this.channelCount = channelCount;
        this.encoding = encoding;
        return changed;
    }

    @Override
    public boolean isActive() {
        return sampleRateHz > 0 && channelCount > 0 && encoding == C.ENCODING_PCM_16BIT;
    }

    @Override
    public int getOutputChannelCount() {
        return channelCount;
    }

    @Override
    public int getOutputEncoding() {
        return encoding;
    }

    @Override
    public int getOutputSampleRateHz() {
        return sampleRateHz;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining <= 0) {
            return;
        }
        if (isActive()) {
            ByteBuffer mirror = inputBuffer.asReadOnlyBuffer();
            byte[] copy = new byte[remaining];
            mirror.get(copy);
            AiDubRuntime.dispatchPcm16(copy, sampleRateHz, channelCount);
        }

        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
        } else {
            buffer.clear();
        }
        buffer.put(inputBuffer);
        buffer.flip();
        outputBuffer = buffer;
    }

    @Override
    public void queueEndOfStream() {
        inputEnded = true;
    }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer result = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return result;
    }

    @SuppressWarnings("ReferenceEquality")
    @Override
    public boolean isEnded() {
        return inputEnded && outputBuffer == EMPTY_BUFFER;
    }

    @Override
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
        inputEnded = false;
        AiDubRuntime.dispatchFlush();
    }

    @Override
    public void reset() {
        flush();
        buffer = EMPTY_BUFFER;
        outputBuffer = EMPTY_BUFFER;
        sampleRateHz = -1;
        channelCount = -1;
        encoding = -1;
    }
}
