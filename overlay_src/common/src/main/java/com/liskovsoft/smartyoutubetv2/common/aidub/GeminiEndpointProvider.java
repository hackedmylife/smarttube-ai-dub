package com.liskovsoft.smartyoutubetv2.common.aidub;

/**
 * Supplies a fully authorized Gemini Live WebSocket URL at runtime.
 * The implementation may obtain a short-lived endpoint from a trusted token broker.
 * No long-lived secret is stored in the APK.
 */
public interface GeminiEndpointProvider {
    String getWebSocketUrl() throws Exception;
}
