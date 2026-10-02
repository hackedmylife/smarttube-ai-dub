package com.liskovsoft.smartyoutubetv2.common.aidub;

/**
 * Supplies Gemini Live connection information at runtime.
 * Secrets are kept out of the WebSocket URL so they cannot leak via URL/error logging.
 */
public interface GeminiEndpointProvider {
    String getWebSocketUrl() throws Exception;

    /**
     * Returns the Gemini API key for the x-goog-api-key handshake header.
     * Implementations should fetch it from private device storage or a secure broker.
     */
    String getApiKeyHeader() throws Exception;
}
