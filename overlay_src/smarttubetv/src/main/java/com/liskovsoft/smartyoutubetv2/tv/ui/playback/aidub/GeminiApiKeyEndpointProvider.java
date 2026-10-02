package com.liskovsoft.smartyoutubetv2.tv.ui.playback.aidub;

import android.net.Uri;

import com.liskovsoft.smartyoutubetv2.common.aidub.GeminiEndpointProvider;

public final class GeminiApiKeyEndpointProvider implements GeminiEndpointProvider {
    private static final String BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/" +
                    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    private final AiDubApiKeyStore keyStore;

    public GeminiApiKeyEndpointProvider(AiDubApiKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @Override
    public String getWebSocketUrl() {
        String apiKey = keyStore.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Gemini API key is not configured");
        }
        return BASE_URL + "?key=" + Uri.encode(apiKey.trim());
    }
}
