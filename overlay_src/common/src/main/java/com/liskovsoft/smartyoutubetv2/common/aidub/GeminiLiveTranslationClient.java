package com.liskovsoft.smartyoutubetv2.common.aidub;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Gemini 3.8 Live native-audio dubbing client.
 *
 * Audio is sent once over a persistent WebSocket and the model performs the
 * spoken-dialogue understanding, Turkish translation and native speech output
 * in the same live session. This intentionally avoids per-phrase REST calls,
 * which are unsuitable for continuous TV dialogue on RPM-limited API tiers.
 */
public final class GeminiLiveTranslationClient {
    public interface Listener {
        void onReady();
        void onTranslatedPcm(byte[] pcm24kMono16Le);
        void onError(Throwable error);
        void onClosed(int code, String reason);
        default void onDiagnostic(String message) {}
    }

    private static final long SETUP_TIMEOUT_MS = 10_000L;

    private final OkHttpClient httpClient;
    private final GeminiEndpointProvider endpointProvider;
    private final Listener listener;
    private final Object lock = new Object();
    private final Deque<byte[]> pendingInput = new ArrayDeque<>();

    private volatile WebSocket webSocket;
    private volatile boolean setupComplete;
    private volatile boolean closed;
    private volatile boolean firstAudioSentNotified;
    private volatile boolean firstOutputNotified;
    private long connectionGeneration;

    public GeminiLiveTranslationClient(
            OkHttpClient httpClient,
            GeminiEndpointProvider endpointProvider,
            Listener listener) {
        if (httpClient == null || endpointProvider == null || listener == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        this.httpClient = httpClient;
        this.endpointProvider = endpointProvider;
        this.listener = listener;
    }

    public void connect() {
        final long generation;
        synchronized (lock) {
            closed = false;
            setupComplete = false;
            pendingInput.clear();
            webSocket = null;
            firstAudioSentNotified = false;
            firstOutputNotified = false;
            generation = ++connectionGeneration;
        }
        new Thread(() -> openConnection(generation), "AiDubGemini38Connector").start();
    }

    private void openConnection(long generation) {
        try {
            String endpoint = endpointProvider.getWebSocketUrl();
            if (endpoint == null || endpoint.trim().isEmpty()) {
                throw new IllegalStateException("Gemini Live endpoint is empty");
            }
            if (!isCurrentGeneration(generation)) return;

            Request.Builder requestBuilder = new Request.Builder().url(endpoint);
            String apiKeyHeader = endpointProvider.getApiKeyHeader();
            if (apiKeyHeader != null && !apiKeyHeader.trim().isEmpty()) {
                requestBuilder.header("x-goog-api-key", apiKeyHeader.trim());
            }
            WebSocket socket = httpClient.newWebSocket(
                    requestBuilder.build(), new SocketListener(generation));
            if (!isCurrentGeneration(generation)) {
                socket.close(1000, "stale AI dub endpoint");
            }
        } catch (Throwable error) {
            if (isCurrentGeneration(generation)) {
                listener.onError(safeTransportError(error, null));
            }
        }
    }

    public void sendPcm16kMono(byte[] pcm16Le) {
        if (pcm16Le == null || pcm16Le.length == 0 || closed) return;
        synchronized (lock) {
            if (!setupComplete || webSocket == null) {
                if (pendingInput.size() >= AiDubConfig.MAX_PENDING_INPUT_CHUNKS) {
                    pendingInput.removeFirst();
                }
                pendingInput.addLast(pcm16Le.clone());
                return;
            }
        }
        sendRealtimeAudio(pcm16Le);
    }

    public void resetPendingInput() {
        synchronized (lock) {
            pendingInput.clear();
        }
    }

    public void reconnect() {
        close();
        connect();
    }

    public void close() {
        WebSocket socket;
        synchronized (lock) {
            closed = true;
            setupComplete = false;
            pendingInput.clear();
            connectionGeneration++;
            socket = webSocket;
            webSocket = null;
        }
        if (socket != null) {
            socket.close(1000, "AI dub stopped");
        }
    }

    private String createSetupJson() throws JSONException {
        JSONObject prebuiltVoiceConfig = new JSONObject()
                .put("voiceName", AiDubConfig.LIVE_VOICE);
        JSONObject voiceConfig = new JSONObject()
                .put("prebuiltVoiceConfig", prebuiltVoiceConfig);
        JSONObject speechConfig = new JSONObject()
                .put("voiceConfig", voiceConfig);
        JSONObject generationConfig = new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", speechConfig);

        // TV/video audio is continuous and must not barge into/cut off the
        // Turkish sentence that is already being generated.
        JSONObject automaticActivityDetection = new JSONObject()
                .put("disabled", false)
                .put("prefixPaddingMs", 40)
                .put("silenceDurationMs", 420);
        JSONObject realtimeInputConfig = new JSONObject()
                .put("automaticActivityDetection", automaticActivityDetection)
                .put("activityHandling", "NO_INTERRUPTION")
                .put("turnCoverage", "TURN_INCLUDES_ONLY_ACTIVITY");

        String instruction =
                "You are a real-time Turkish dubbing engine for video. " +
                "Listen to the incoming audio and translate only the spoken dialogue into natural spoken Turkish. " +
                "Never answer the speaker, never comment, never explain and never add information. " +
                "Preserve names, meaning, emotion, register and intent. " +
                "Keep each Turkish line concise and close to the source speaking duration. " +
                "Use fluent everyday Turkish with natural rhythm, phrasing and prosody, like a professional film dub. " +
                "Ignore music, ambience, wind, sound effects and non-speech audio; do not describe them. " +
                "If the spoken dialogue is already Turkish, reproduce it naturally without changing its meaning.";
        JSONObject systemInstruction = new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", instruction)));

        JSONObject setup = new JSONObject()
                .put("model", AiDubConfig.MODEL)
                .put("generationConfig", generationConfig)
                .put("systemInstruction", systemInstruction)
                .put("realtimeInputConfig", realtimeInputConfig);
        return new JSONObject().put("setup", setup).toString();
    }

    private void sendRealtimeAudio(byte[] pcm16Le) {
        WebSocket socket = webSocket;
        if (socket == null || closed) return;
        try {
            JSONObject audio = new JSONObject()
                    .put("data", Base64.encodeToString(pcm16Le, Base64.NO_WRAP))
                    .put("mimeType", "audio/pcm;rate=" + AiDubConfig.GEMINI_INPUT_SAMPLE_RATE_HZ);
            JSONObject realtimeInput = new JSONObject().put("audio", audio);
            if (!socket.send(new JSONObject().put("realtimeInput", realtimeInput).toString())) {
                listener.onError(new IOException("Gemini 3.8 Live WebSocket rejected audio frame"));
            } else if (!firstAudioSentNotified) {
                firstAudioSentNotified = true;
                listener.onDiagnostic("Ses Gemini 3.8 Live'a akıyor");
            }
        } catch (JSONException error) {
            listener.onError(error);
        }
    }

    private void markSetupComplete() {
        Deque<byte[]> queued = new ArrayDeque<>();
        synchronized (lock) {
            setupComplete = true;
            queued.addAll(pendingInput);
            pendingInput.clear();
        }
        listener.onReady();
        while (!queued.isEmpty()) {
            sendRealtimeAudio(queued.removeFirst());
        }
    }

    private void handleMessage(String text) {
        try {
            JSONObject root = new JSONObject(text);

            JSONObject apiError = root.optJSONObject("error");
            if (apiError != null) {
                int code = apiError.optInt("code", -1);
                String status = apiError.optString("status", "");
                String message = apiError.optString("message", "");
                String detail = message.isEmpty() ? "" : " - " + sanitize(message);
                listener.onError(new IOException(
                        "Gemini 3.8 Live API " + code +
                                (status.isEmpty() ? "" : " " + status) + detail));
                return;
            }

            if (root.has("setupComplete")) {
                markSetupComplete();
                return;
            }

            if (root.has("goAway")) {
                listener.onDiagnostic("Gemini 3.8 Live oturumu yenileniyor");
                listener.onClosed(1001, "Gemini goAway");
                return;
            }

            JSONObject serverContent = root.optJSONObject("serverContent");
            if (serverContent == null) return;
            if (serverContent.optBoolean("interrupted", false)) {
                listener.onDiagnostic("Gemini 3.8 Live çıktı kesintisi algılandı");
            }

            JSONObject modelTurn = serverContent.optJSONObject("modelTurn");
            if (modelTurn == null) return;
            JSONArray parts = modelTurn.optJSONArray("parts");
            if (parts == null) return;

            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                JSONObject inlineData = part.optJSONObject("inlineData");
                if (inlineData == null) continue;
                String mimeType = inlineData.optString("mimeType", "");
                String data = inlineData.optString("data", "");
                if (data.isEmpty() || !mimeType.startsWith("audio/")) continue;
                byte[] decoded = Base64.decode(data, Base64.DEFAULT);
                if (decoded.length > 0) {
                    if (!firstOutputNotified) {
                        firstOutputNotified = true;
                        listener.onDiagnostic("Gemini 3.8 Live Türkçe native audio başladı");
                    }
                    listener.onTranslatedPcm(decoded);
                }
            }
        } catch (Throwable error) {
            if (!closed) listener.onError(error);
        }
    }

    private void startSetupWatchdog(final long generation) {
        new Thread(() -> {
            try {
                Thread.sleep(SETUP_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            if (isCurrentGeneration(generation) && !setupComplete) {
                listener.onError(new IOException("Gemini 3.8 Live setup timeout (10s)"));
            }
        }, "AiDubGemini38SetupWatchdog").start();
    }

    private static String sanitize(String reason) {
        if (reason == null) return "";
        String value = reason.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ").trim();
        return value.length() <= 180 ? value : value.substring(0, 180);
    }

    private static IOException safeTransportError(Throwable error, Response response) {
        if (response != null) {
            return new IOException("Gemini 3.8 Live WebSocket HTTP " + response.code());
        }
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
        return new IOException("Gemini 3.8 Live WebSocket " + type);
    }

    private boolean isCurrentGeneration(long generation) {
        synchronized (lock) {
            return !closed && generation == connectionGeneration;
        }
    }

    private final class SocketListener extends WebSocketListener {
        private final long generation;

        SocketListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket socket, Response response) {
            synchronized (lock) {
                if (closed || generation != connectionGeneration) {
                    socket.close(1000, "stale AI dub socket");
                    return;
                }
                webSocket = socket;
            }
            listener.onDiagnostic("Gemini 3.8 Live WebSocket açıldı");
            try {
                if (!socket.send(createSetupJson())) {
                    throw new IOException("Gemini 3.8 Live rejected setup frame");
                }
                listener.onDiagnostic("Gemini 3.8 Live setup gönderildi");
                startSetupWatchdog(generation);
            } catch (Throwable error) {
                listener.onError(new IOException("Gemini 3.8 Live setup frame failed"));
                socket.close(1011, "setup failed");
            }
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            if (isCurrentGeneration(generation)) handleMessage(text);
        }

        @Override
        public void onMessage(WebSocket socket, ByteString bytes) {
            if (isCurrentGeneration(generation)) handleMessage(bytes.utf8());
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            socket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
            if (!isCurrentGeneration(generation)) return;
            final boolean wasSetupComplete = setupComplete;
            synchronized (lock) {
                setupComplete = false;
                webSocket = null;
            }
            String phase = wasSetupComplete ? "after setupComplete" : "during setup";
            listener.onClosed(code, phase + (reason == null || reason.trim().isEmpty()
                    ? ""
                    : " - " + sanitize(reason)));
        }

        @Override
        public void onFailure(WebSocket socket, Throwable t, Response response) {
            if (!isCurrentGeneration(generation)) return;
            synchronized (lock) {
                setupComplete = false;
                webSocket = null;
            }
            listener.onError(safeTransportError(t, response));
        }
    }
}
