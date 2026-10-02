package com.liskovsoft.smartyoutubetv2.common.aidub;

import java.util.concurrent.atomic.AtomicReference;

public final class AiDubRuntime {
    public interface PcmSink {
        void onPcm16(byte[] pcm16Le, int sampleRateHz, int channelCount);
        void onAudioPipelineFlushed();
    }

    private static final AtomicReference<PcmSink> ACTIVE_SINK = new AtomicReference<>();

    private AiDubRuntime() {
    }

    public static void setActiveSink(PcmSink sink) {
        ACTIVE_SINK.set(sink);
    }

    public static void clearActiveSink(PcmSink sink) {
        ACTIVE_SINK.compareAndSet(sink, null);
    }

    static void dispatchPcm16(byte[] pcm16Le, int sampleRateHz, int channelCount) {
        PcmSink sink = ACTIVE_SINK.get();
        if (sink != null) {
            sink.onPcm16(pcm16Le, sampleRateHz, channelCount);
        }
    }

    static void dispatchFlush() {
        PcmSink sink = ACTIVE_SINK.get();
        if (sink != null) {
            sink.onAudioPipelineFlushed();
        }
    }
}
