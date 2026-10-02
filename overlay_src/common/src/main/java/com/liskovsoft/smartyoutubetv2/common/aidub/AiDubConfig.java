package com.liskovsoft.smartyoutubetv2.common.aidub;

public final class AiDubConfig {
    public static final String MODEL = "models/gemini-3.5-live-translate-preview";
    public static final String TARGET_LANGUAGE = "tr";
    public static final int GEMINI_INPUT_SAMPLE_RATE_HZ = 16_000;
    public static final int GEMINI_OUTPUT_SAMPLE_RATE_HZ = 24_000;
    public static final int PCM_BYTES_PER_SAMPLE = 2;
    public static final int CHUNK_DURATION_MS = 100;
    public static final int INPUT_CHUNK_BYTES =
            GEMINI_INPUT_SAMPLE_RATE_HZ * PCM_BYTES_PER_SAMPLE * CHUNK_DURATION_MS / 1000;

    // Keep the capture side low-latency, but give the translated output enough
    // headroom to absorb the bursty delivery pattern of Live API audio frames.
    public static final int MAX_PENDING_INPUT_CHUNKS = 20;
    public static final int MAX_PENDING_OUTPUT_CHUNKS = 256;
    public static final int OUTPUT_PREBUFFER_MS = 350;
    public static final int OUTPUT_AUDIO_TRACK_BUFFER_MS = 1_200;
    public static final int OUTPUT_BYTES_PER_SECOND =
            GEMINI_OUTPUT_SAMPLE_RATE_HZ * PCM_BYTES_PER_SAMPLE;
    public static final int OUTPUT_PREBUFFER_BYTES =
            OUTPUT_BYTES_PER_SECOND * OUTPUT_PREBUFFER_MS / 1000;
    public static final int OUTPUT_AUDIO_TRACK_BUFFER_BYTES =
            OUTPUT_BYTES_PER_SECOND * OUTPUT_AUDIO_TRACK_BUFFER_MS / 1000;

    private AiDubConfig() {}
}
