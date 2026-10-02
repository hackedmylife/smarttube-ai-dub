package com.liskovsoft.smartyoutubetv2.common.aidub;

import com.google.android.exoplayer2.SimpleExoPlayer;

import okhttp3.OkHttpClient;

public final class AiDubServices {
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder().build();
    private static volatile GeminiEndpointProvider endpointProvider;

    private AiDubServices() {
    }

    public static void setEndpointProvider(GeminiEndpointProvider provider) {
        endpointProvider = provider;
    }

    public static boolean isConfigured() {
        return endpointProvider != null;
    }

    public static AiDubController createController(
            SimpleExoPlayer player,
            AiDubController.Listener listener) {
        GeminiEndpointProvider provider = endpointProvider;
        if (provider == null) {
            throw new IllegalStateException("AI Dub endpoint is not configured");
        }
        return new AiDubController(player, HTTP_CLIENT, provider, listener);
    }
}
