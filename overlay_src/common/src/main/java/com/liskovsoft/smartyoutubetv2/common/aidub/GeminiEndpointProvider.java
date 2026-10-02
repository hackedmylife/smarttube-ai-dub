package com.liskovsoft.smartyoutubetv2.common.aidub;

/**
 * Supplies Gemini Live connection information at runtime.
 * New AQ authorization keys are sent in x-goog-api-key.
 * Legacy/standard keys may be carried in the WebSocket query string as documented by Live API.
 */
public interface GeminiEndpointProvider {
    String getWebSocketUrl() throws Exception;

    /** Returns a key for the x-goog-api-key header, or an empty string when query auth is used. */
    String getApiKeyHeader() throws Exception;
}
