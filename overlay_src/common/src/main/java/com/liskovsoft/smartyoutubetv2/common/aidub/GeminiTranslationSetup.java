package com.liskovsoft.smartyoutubetv2.common.aidub;

/** Minimal raw WebSocket profile for the dedicated continuous translator. */
public final class GeminiTranslationSetup {
    private GeminiTranslationSetup() {}

    public static String createJson() {
        // Only compile-time model/language identifiers are interpolated here.
        // The translation model does not support agent instructions or tools.
        // Keep transcription fields out: they are not needed for audio output.
        return "{\"setup\":{\"model\":\"" + AiDubConfig.MODEL +
                "\",\"generationConfig\":{\"responseModalities\":[\"AUDIO\"]," +
                "\"translationConfig\":{\"targetLanguageCode\":\"" +
                AiDubConfig.TARGET_LANGUAGE + "\",\"echoTargetLanguage\":true}}}}";
    }
}
