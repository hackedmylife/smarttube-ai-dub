package com.liskovsoft.smartyoutubetv2.common.aidub;

/**
 * Supplies Gemini connection information at runtime.
 *
 * The Live WebSocket uses the key in its query string. REST translation/TTS
 * requests reuse the exact same persisted key in the x-goog-api-key header.
 */
public interface GeminiEndpointProvider {
    String getWebSocketUrl() throws Exception;

    /** Returns a key for the x-goog-api-key header, or an empty string when query auth is used. */
    String getApiKeyHeader() throws Exception;

    /** Returns the persisted Gemini API key for REST calls in the natural-dub cascade. */
    String getApiKey() throws Exception;
}
