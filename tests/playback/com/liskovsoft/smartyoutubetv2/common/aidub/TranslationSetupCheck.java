package com.liskovsoft.smartyoutubetv2.common.aidub;
public final class TranslationSetupCheck {
    public static void main(String[] args) {
        if (AiDubConfig.GEMINI_INPUT_SAMPLE_RATE_HZ != 16000
                || AiDubConfig.GEMINI_OUTPUT_SAMPLE_RATE_HZ != 24000
                || AiDubConfig.CHUNK_DURATION_MS != 100) {
            throw new AssertionError("Unsupported continuous-translation PCM profile");
        }
        System.out.println(GeminiTranslationSetup.createJson());
    }
}
