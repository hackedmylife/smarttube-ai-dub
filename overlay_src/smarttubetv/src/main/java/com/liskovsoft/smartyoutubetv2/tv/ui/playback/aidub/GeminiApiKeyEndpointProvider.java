package com.liskovsoft.smartyoutubetv2.tv.ui.playback.aidub;

import android.net.Uri;

import com.liskovsoft.smartyoutubetv2.common.aidub.GeminiEndpointProvider;

/**
 * Supplies Gemini Live and REST authentication from the same persisted TV key.
 *
 * Raw BidiGenerateContent WebSocket auth uses the ?key= query parameter. The
 * natural-dub REST stages reuse the same key through x-goog-api-key.
 */
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
        return BASE_URL + "?key=" + Uri.encode(requireKey());
    }

    @Override
    public String getApiKeyHeader() {
        // Raw Gemini Live WebSocket auth uses the URL query parameter.
        return "";
    }

    @Override
    public String getApiKey() {
        return requireKey();
    }
}
