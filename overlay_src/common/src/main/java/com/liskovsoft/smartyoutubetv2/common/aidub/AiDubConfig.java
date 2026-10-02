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
    public static final int MAX_PENDING_INPUT_CHUNKS = 20;
    public static final int MAX_PENDING_OUTPUT_CHUNKS = 64;
    private AiDubConfig() {}
}
