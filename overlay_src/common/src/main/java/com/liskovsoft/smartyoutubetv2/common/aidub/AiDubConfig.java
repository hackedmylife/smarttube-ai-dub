package com.liskovsoft.smartyoutubetv2.common.aidub;

public final class AiDubConfig {
    // Primary low-latency dubbing engine. Gemini 3.8 Live performs audio ->
    // Turkish native audio in one persistent WebSocket session, avoiding the
    // per-phrase REST Translation/TTS request pressure that caused HTTP 429s.
    public static final String MODEL = "models/gemini-3.8-live";
    public static final String LIVE_VOICE = "Puck";

    // Cascade model constants are retained only as an emergency rollback path.
    public static final String TRANSCRIBE_MODEL = "models/gemini-3.5-transcribe-live";
    public static final String TRANSLATION_MODEL = "gemini-3.5-flash-lite";
    public static final String TTS_MODEL = "gemini-3.8-flash-tts";
    public static final String TTS_VOICE = "Sulafat";

    public static final String TARGET_LANGUAGE = "tr";
    public static final int GEMINI_INPUT_SAMPLE_RATE_HZ = 16_000;
    public static final int GEMINI_OUTPUT_SAMPLE_RATE_HZ = 24_000;
    public static final int PCM_BYTES_PER_SAMPLE = 2;
    public static final int CHUNK_DURATION_MS = 100;
    public static final int INPUT_CHUNK_BYTES =
            GEMINI_INPUT_SAMPLE_RATE_HZ * PCM_BYTES_PER_SAMPLE * CHUNK_DURATION_MS / 1000;

    // Rollback cascade segmentation values.
    public static final int NATURAL_DUB_TARGET_WORDS = 8;
    public static final int NATURAL_DUB_MIN_PUNCTUATION_WORDS = 5;
    public static final int NATURAL_DUB_MAX_WORDS = 12;
    public static final int MAX_PENDING_NATURAL_PHRASES = 32;

    public static final int MAX_PENDING_INPUT_CHUNKS = 20;
    public static final int MAX_PENDING_OUTPUT_CHUNKS = 256;
    public static final int OUTPUT_PREBUFFER_MS = 220;
    public static final int OUTPUT_AUDIO_TRACK_BUFFER_MS = 1_200;
    public static final int OUTPUT_BYTES_PER_SECOND =
            GEMINI_OUTPUT_SAMPLE_RATE_HZ * PCM_BYTES_PER_SAMPLE;
    public static final int OUTPUT_PREBUFFER_BYTES =
            OUTPUT_BYTES_PER_SECOND * OUTPUT_PREBUFFER_MS / 1000;
    public static final int OUTPUT_AUDIO_TRACK_BUFFER_BYTES =
            OUTPUT_BYTES_PER_SECOND * OUTPUT_AUDIO_TRACK_BUFFER_MS / 1000;

    private AiDubConfig() {}
}
