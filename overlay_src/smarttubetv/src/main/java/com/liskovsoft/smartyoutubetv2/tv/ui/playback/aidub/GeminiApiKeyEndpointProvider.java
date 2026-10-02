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

    private String requireKey() {
        String apiKey = keyStore.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Gemini API key is not configured");
        }
        return apiKey.trim();
    }

    @Override
    public String getWebSocketUrl() {
        String apiKey = requireKey();

        // Current AI Studio authorization keys use the AQ. prefix and are intended for header auth.
        // Standard/legacy keys continue to follow the Live API raw WebSocket query-param examples.
        if (apiKey.startsWith("AQ.")) {
            return BASE_URL;
        }

        return BASE_URL + "?key=" + Uri.encode(apiKey);
    }

    @Override
    public String getApiKeyHeader() {
        String apiKey = requireKey();
        return apiKey.startsWith("AQ.") ? apiKey : "";
    }
}
