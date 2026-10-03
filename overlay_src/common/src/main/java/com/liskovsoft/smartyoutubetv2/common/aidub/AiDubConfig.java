package com.liskovsoft.smartyoutubetv2.common.aidub;

public final class AiDubConfig {
    // Natural Dub cascade: live ASR -> text translation -> controllable TTS.
    public static final String TRANSCRIBE_MODEL = "models/gemini-3.5-transcribe-live";
    public static final String TRANSLATION_MODEL = "gemini-3.5-flash-lite";

    // Quality-first TTS. Gemini 3.8 Flash TTS is the higher-fidelity sibling of
    // Flash-Lite and is better suited to nuanced dubbing/prosody. We keep the
    // same 24 kHz PCM output contract and voice so the playback path is unchanged.
    public static final String TTS_MODEL = "gemini-3.8-flash-tts";
    public static final String TTS_VOICE = "Sulafat";

    // Kept only so the previous direct Live Translate implementation remains
    // compilable as an emergency rollback path. Natural Dub does not use it.
    public static final String MODEL = "models/gemini-3.5-live-translate-preview";

    public static final String TARGET_LANGUAGE = "tr";
    public static final int GEMINI_INPUT_SAMPLE_RATE_HZ = 16_000;
    public static final int GEMINI_OUTPUT_SAMPLE_RATE_HZ = 24_000;
    public static final int PCM_BYTES_PER_SAMPLE = 2;
    public static final int CHUNK_DURATION_MS = 100;
    public static final int INPUT_CHUNK_BYTES =
            GEMINI_INPUT_SAMPLE_RATE_HZ * PCM_BYTES_PER_SAMPLE * CHUNK_DURATION_MS / 1000;

    // Commit stable interim transcript prefixes before the source speaker finishes
    // the full sentence. This keeps cascade latency bounded without speaking every
    // speculative ASR revision.
    public static final int NATURAL_DUB_TARGET_WORDS = 8;
    public static final int NATURAL_DUB_MIN_PUNCTUATION_WORDS = 5;
    public static final int NATURAL_DUB_MAX_WORDS = 12;
    public static final int MAX_PENDING_NATURAL_PHRASES = 32;

    // Keep the capture side low-latency, but give generated TTS output enough
    // headroom to absorb network/model jitter.
    public static final int MAX_PENDING_INPUT_CHUNKS = 20;
    public static final int MAX_PENDING_OUTPUT_CHUNKS = 256;
    public static final int OUTPUT_PREBUFFER_MS = 250;
    public static final int OUTPUT_AUDIO_TRACK_BUFFER_MS = 1_200;
    public static final int OUTPUT_BYTES_PER_SECOND =
            GEMINI_OUTPUT_SAMPLE_RATE_HZ * PCM_BYTES_PER_SAMPLE;
    public static final int OUTPUT_PREBUFFER_BYTES =
            OUTPUT_BYTES_PER_SECOND * OUTPUT_PREBUFFER_MS / 1000;
    public static final int OUTPUT_AUDIO_TRACK_BUFFER_BYTES =
            OUTPUT_BYTES_PER_SECOND * OUTPUT_AUDIO_TRACK_BUFFER_MS / 1000;

    private AiDubConfig() {}
}
