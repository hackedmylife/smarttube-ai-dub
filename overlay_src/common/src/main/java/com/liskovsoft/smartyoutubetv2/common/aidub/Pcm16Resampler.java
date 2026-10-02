package com.liskovsoft.smartyoutubetv2.common.aidub;

import java.io.ByteArrayOutputStream;

public final class Pcm16Resampler {
    private final int targetSampleRateHz;
    private int inputSampleRateHz = -1;
    private int channelCount = -1;
    private double sourceFramesPerOutputFrame;
    private double nextSourcePosition;
    private long totalInputFrames;
    private boolean hasPreviousSample;
    private short previousSample;

    public Pcm16Resampler(int targetSampleRateHz) {
        if (targetSampleRateHz <= 0) {
            throw new IllegalArgumentException("targetSampleRateHz must be > 0");
        }
        this.targetSampleRateHz = targetSampleRateHz;
    }

    public byte[] toMono(byte[] pcm16Le, int inputSampleRateHz, int channelCount) {
        if (pcm16Le == null || pcm16Le.length == 0) {
            return new byte[0];
        }
        if (inputSampleRateHz <= 0 || channelCount <= 0) {
            throw new IllegalArgumentException("invalid PCM format");
        }

        if (this.inputSampleRateHz != inputSampleRateHz || this.channelCount != channelCount) {
            resetForFormat(inputSampleRateHz, channelCount);
        }

        final int bytesPerFrame = channelCount * 2;
        final int inputFrames = pcm16Le.length / bytesPerFrame;
        if (inputFrames <= 0) {
            return new byte[0];
        }

        short[] mono = downmixToMono(pcm16Le, inputFrames, channelCount);
        final long chunkStart = totalInputFrames;
        final long chunkEnd = chunkStart + inputFrames - 1L;

        if (inputSampleRateHz == targetSampleRateHz) {
            totalInputFrames += inputFrames;
            previousSample = mono[inputFrames - 1];
            hasPreviousSample = true;
            nextSourcePosition = totalInputFrames;
            return shortsToLittleEndian(mono);
        }

        int expectedFrames = Math.max(1,
                (int) Math.ceil(inputFrames / sourceFramesPerOutputFrame) + 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream(expectedFrames * 2);
        final double epsilon = 1e-9;

        while (true) {
            long leftIndex = (long) Math.floor(nextSourcePosition + epsilon);
            double fraction = nextSourcePosition - leftIndex;
            if (Math.abs(fraction) < epsilon) {
                fraction = 0.0;
            }
            long rightIndex = fraction == 0.0 ? leftIndex : leftIndex + 1L;
            if (rightIndex > chunkEnd) {
                break;
            }
            if (leftIndex < chunkStart - 1L) {
                nextSourcePosition = chunkStart;
                continue;
            }

            short left = sampleAt(leftIndex, chunkStart, mono);
            short right = fraction == 0.0 ? left : sampleAt(rightIndex, chunkStart, mono);
            double interpolated = left + (right - left) * fraction;
            writeLittleEndian(out, clamp16((int) Math.round(interpolated)));
            nextSourcePosition += sourceFramesPerOutputFrame;
        }

        totalInputFrames += inputFrames;
        previousSample = mono[inputFrames - 1];
        hasPreviousSample = true;
        return out.toByteArray();
    }

    public void reset() {
        inputSampleRateHz = -1;
        channelCount = -1;
        sourceFramesPerOutputFrame = 0.0;
        nextSourcePosition = 0.0;
        totalInputFrames = 0L;
        hasPreviousSample = false;
        previousSample = 0;
    }

    private void resetForFormat(int inputSampleRateHz, int channelCount) {
        reset();
        this.inputSampleRateHz = inputSampleRateHz;
        this.channelCount = channelCount;
        this.sourceFramesPerOutputFrame =
                (double) inputSampleRateHz / (double) targetSampleRateHz;
    }

    private short sampleAt(long globalIndex, long chunkStart, short[] mono) {
        if (globalIndex == chunkStart - 1L) {
            if (!hasPreviousSample) {
                throw new IllegalStateException("Missing previous sample");
            }
            return previousSample;
        }
        int localIndex = (int) (globalIndex - chunkStart);
        if (localIndex < 0 || localIndex >= mono.length) {
            throw new IllegalStateException("Sample outside PCM window");
        }
        return mono[localIndex];
    }

    private static short[] downmixToMono(byte[] pcm16Le, int inputFrames, int channelCount) {
        short[] mono = new short[inputFrames];
        int byteIndex = 0;
        for (int frame = 0; frame < inputFrames; frame++) {
            long sum = 0L;
            for (int ch = 0; ch < channelCount; ch++) {
                int lo = pcm16Le[byteIndex++] & 0xff;
                int hi = pcm16Le[byteIndex++];
                short sample = (short) ((hi << 8) | lo);
                sum += sample;
            }
            mono[frame] = clamp16((int) (sum / channelCount));
        }
        return mono;
    }

    private static byte[] shortsToLittleEndian(short[] samples) {
        byte[] out = new byte[samples.length * 2];
        int index = 0;
        for (short sample : samples) {
            out[index++] = (byte) (sample & 0xff);
            out[index++] = (byte) ((sample >>> 8) & 0xff);
        }
        return out;
    }

    private static void writeLittleEndian(ByteArrayOutputStream out, short sample) {
        out.write(sample & 0xff);
        out.write((sample >>> 8) & 0xff);
    }

    private static short clamp16(int value) {
        if (value > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (value < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) value;
    }
}
