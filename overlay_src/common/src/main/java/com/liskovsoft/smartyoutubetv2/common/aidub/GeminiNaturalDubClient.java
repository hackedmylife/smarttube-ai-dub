package com.liskovsoft.smartyoutubetv2.common.aidub;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Premium natural-dubbing cascade:
 *
 * 1) Gemini 3.5 Transcribe Live produces low-latency interim/final source text.
 * 2) Stable interim prefixes are committed in short phrases before the speaker
 *    finishes the full sentence, keeping latency bounded.
 * 3) Gemini 3.5 Flash-Lite translates only the committed phrase to Turkish.
 * 4) Gemini 3.8 Flash-Lite TTS renders controllable, natural 24 kHz PCM.
 *
 * The older direct Live Translate client remains in the tree as a rollback path,
 * but this cascade is intentionally independent from its audio-generation quirks.
 */
public final class GeminiNaturalDubClient {
    public interface Listener {
        void onReady();
        void onTranslatedPcm(byte[] pcm24kMono16Le);
        void onError(Throwable error);
        void onClosed(int code, String reason);
        default void onDiagnostic(String message) {}
    }

    private static final long SETUP_TIMEOUT_MS = 8_000L;
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");
    private static final String GENERATE_CONTENT_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final class PhraseTask {
        final String sourceText;
        final long pipelineGeneration;

        PhraseTask(String sourceText, long pipelineGeneration) {
            this.sourceText = sourceText;
            this.pipelineGeneration = pipelineGeneration;
        }
    }

    private final OkHttpClient httpClient;
    private final GeminiEndpointProvider endpointProvider;
    private final Listener listener;
    private final Object lock = new Object();
    private final Deque<byte[]> pendingInput = new ArrayDeque<>();
    private final LinkedBlockingDeque<PhraseTask> phraseQueue =
            new LinkedBlockingDeque<>(AiDubConfig.MAX_PENDING_NATURAL_PHRASES);
    private final AtomicBoolean phraseWorkerRunning = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile Thread phraseWorkerThread;
    private volatile boolean setupComplete;
    private volatile boolean closed;
    private volatile boolean firstAudioSentNotified;
    private volatile boolean firstTranscriptNotified;
    private volatile boolean firstTtsNotified;

    private long connectionGeneration;
    private long pipelineGeneration;

    // Current ASR turn segmentation state. The server emits a full interim
    // hypothesis, not just a delta, so common-prefix words are safe candidates.
    private List<String> lastInterimWords = Collections.emptyList();
    private int committedWordCount;

    // Short context improves pronoun/name continuity without letting the model
    // rewrite already-spoken Turkish audio.
    private String previousSourceContext = "";
    private String previousTurkishContext = "";

    public GeminiNaturalDubClient(
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
            firstTranscriptNotified = false;
            firstTtsNotified = false;
            resetTranscriptStateLocked();
            pipelineGeneration++;
            generation = ++connectionGeneration;
        }
        startPhraseWorker();
        new Thread(() -> openConnection(generation), "AiDubTranscribeConnector").start();
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
            pipelineGeneration++;
            phraseQueue.clear();
            resetTranscriptStateLocked();
            previousSourceContext = "";
            previousTurkishContext = "";
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
            phraseQueue.clear();
            pipelineGeneration++;
            connectionGeneration++;
            resetTranscriptStateLocked();
            socket = webSocket;
            webSocket = null;
        }
        stopPhraseWorker();
        if (socket != null) {
            socket.close(1000, "AI dub stopped");
        }
    }

    private void openConnection(long generation) {
        try {
            String endpoint = endpointProvider.getWebSocketUrl();
            if (endpoint == null || endpoint.trim().isEmpty()) {
                throw new IllegalStateException("Gemini Live endpoint is empty");
            }
            if (!isCurrentConnection(generation)) return;

            Request request = new Request.Builder().url(endpoint).build();
            WebSocket socket = httpClient.newWebSocket(request, new SocketListener(generation));
            if (!isCurrentConnection(generation)) {
                socket.close(1000, "stale AI dub endpoint");
            }
        } catch (Throwable error) {
            if (isCurrentConnection(generation)) {
                listener.onError(safeTransportError(error, null));
            }
        }
    }

    private String createSetupJson() throws JSONException {
        JSONObject generationConfig = new JSONObject()
                .put("responseModalities", new JSONArray().put("TEXT"));

        JSONObject transcriptionConfig = new JSONObject()
                .put("languageCodes", new JSONArray())
                .put("mode", "SMART");

        // 500 ms is intentionally below Gemini's ~800 ms default while staying
        // inside Google's recommended 500-800 ms quality/latency range.
        JSONObject automaticActivityDetection = new JSONObject()
                .put("disabled", false)
                .put("prefixPaddingMs", 40)
                .put("silenceDurationMs", 500);
        JSONObject realtimeInputConfig = new JSONObject()
                .put("automaticActivityDetection", automaticActivityDetection);

        JSONObject setup = new JSONObject()
                .put("model", AiDubConfig.TRANSCRIBE_MODEL)
                .put("generationConfig", generationConfig)
                .put("realtimeInputConfig", realtimeInputConfig)
                .put("inputAudioTranscription", transcriptionConfig);
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
                listener.onError(new IOException("Gemini WebSocket rejected audio frame"));
            } else if (!firstAudioSentNotified) {
                firstAudioSentNotified = true;
                listener.onDiagnostic("Ses Gemini Transcribe'a gönderildi");
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
                throw new IOException(formatApiError("Live Transcribe", apiError));
            }
            if (root.has("setupComplete")) {
                markSetupComplete();
                return;
            }

            JSONObject serverContent = root.optJSONObject("serverContent");
            if (serverContent == null) return;

            JSONObject interim = serverContent.optJSONObject("interimInputTranscription");
            if (interim != null) {
                String interimText = cleanText(interim.optString("text", ""));
                if (!interimText.isEmpty()) {
                    if (!firstTranscriptNotified) {
                        firstTranscriptNotified = true;
                        listener.onDiagnostic("Canlı transkript başladı");
                    }
                    handleInterimTranscript(interimText);
                }
            }

            JSONObject finalized = serverContent.optJSONObject("inputTranscription");
            if (finalized != null) {
                String finalText = cleanText(finalized.optString("text", ""));
                if (!finalText.isEmpty()) {
                    handleFinalTranscript(finalText);
                }
            }
        } catch (Throwable error) {
            if (!closed) listener.onError(error);
        }
    }

    private void handleInterimTranscript(String text) {
        List<String> currentWords = splitWords(text);
        if (currentWords.isEmpty()) return;

        List<String> phrases = new ArrayList<>();
        synchronized (lock) {
            int stablePrefix = commonPrefixWordCount(lastInterimWords, currentWords);
            while (stablePrefix - committedWordCount >= AiDubConfig.NATURAL_DUB_TARGET_WORDS) {
                int cut = choosePhraseCut(currentWords, committedWordCount, stablePrefix);
                if (cut <= committedWordCount) break;
                phrases.add(joinWords(currentWords, committedWordCount, cut));
                committedWordCount = cut;
            }
            lastInterimWords = new ArrayList<>(currentWords);
        }
        for (String phrase : phrases) enqueuePhrase(phrase);
    }

    private void handleFinalTranscript(String text) {
        List<String> finalWords = splitWords(text);
        String remainder = "";
        synchronized (lock) {
            int start = Math.min(committedWordCount, finalWords.size());
            if (start < finalWords.size()) {
                remainder = joinWords(finalWords, start, finalWords.size());
            }
            resetTranscriptStateLocked();
        }
        if (!remainder.isEmpty()) enqueuePhrase(remainder);
    }

    private void enqueuePhrase(String phrase) {
        String cleaned = cleanText(phrase);
        if (cleaned.isEmpty() || closed) return;

        final long generation;
        synchronized (lock) {
            generation = pipelineGeneration;
        }
        PhraseTask task = new PhraseTask(cleaned, generation);
        if (!phraseQueue.offerLast(task)) {
            // Never silently throw away dialogue. Collapse the newest backlog
            // into one translation request if the downstream service is slower.
            PhraseTask tail = phraseQueue.pollLast();
            if (tail != null && tail.pipelineGeneration == generation) {
                task = new PhraseTask(tail.sourceText + " " + cleaned, generation);
            }
            if (!phraseQueue.offerLast(task)) {
                phraseQueue.pollFirst();
                phraseQueue.offerLast(task);
            }
            listener.onDiagnostic("Doğal dublaj kuyruğu sıkıştırıldı");
        }
    }

    private void startPhraseWorker() {
        if (!phraseWorkerRunning.compareAndSet(false, true)) return;
        phraseWorkerThread = new Thread(this::runPhraseWorker, "AiDubNaturalPhraseWorker");
        phraseWorkerThread.start();
    }

    private void stopPhraseWorker() {
        phraseWorkerRunning.set(false);
        Thread worker = phraseWorkerThread;
        phraseWorkerThread = null;
        if (worker != null) worker.interrupt();
    }

    private void runPhraseWorker() {
        while (phraseWorkerRunning.get()) {
            try {
                PhraseTask task = phraseQueue.pollFirst(250, TimeUnit.MILLISECONDS);
                if (task == null) continue;
                if (!isCurrentPipeline(task.pipelineGeneration)) continue;

                String turkish = translateToTurkish(task.sourceText, task.pipelineGeneration);
                if (turkish.isEmpty() || !isCurrentPipeline(task.pipelineGeneration)) continue;

                byte[] pcm = synthesizeTurkish(turkish, task.pipelineGeneration);
                if (pcm.length == 0 || !isCurrentPipeline(task.pipelineGeneration)) continue;

                synchronized (lock) {
                    previousSourceContext = limitContext(task.sourceText, 220);
                    previousTurkishContext = limitContext(turkish, 220);
                }
                if (!firstTtsNotified) {
                    firstTtsNotified = true;
                    listener.onDiagnostic("Doğal Türkçe TTS PCM geldi");
                }
                listener.onTranslatedPcm(pcm);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                if (!closed) listener.onError(error);
            }
        }
    }

    private String translateToTurkish(String sourceText, long generation) throws Exception {
        if (!isCurrentPipeline(generation)) return "";

        String previousSource;
        String previousTurkish;
        synchronized (lock) {
            previousSource = previousSourceContext;
            previousTurkish = previousTurkishContext;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a professional Turkish dubbing translator. ")
                .append("Translate ONLY the CURRENT source phrase into natural spoken Turkish. ")
                .append("Preserve meaning, names, emotion and register. Keep it concise so its spoken duration stays close to the source. ")
                .append("Do not explain, label, quote, censor, summarize, or add anything. Return only the Turkish words to be spoken.\n");
        if (!previousSource.isEmpty() || !previousTurkish.isEmpty()) {
            prompt.append("Previous context (context only; do not repeat):\n")
                    .append("Source: ").append(previousSource).append('\n')
                    .append("Turkish: ").append(previousTurkish).append('\n');
        }
        prompt.append("CURRENT source: ").append(sourceText);

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject()
                                .put("text", prompt.toString())))));

        JSONObject response = executeJsonRequest(
                AiDubConfig.TRANSLATION_MODEL,
                "generateContent",
                body,
                "Translation");

        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IOException("Gemini Translation returned no candidate");
        }
        JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) throw new IOException("Gemini Translation returned no text");

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String value = part.optString("text", "");
            if (!value.isEmpty()) {
                if (out.length() > 0) out.append(' ');
                out.append(value);
            }
        }
        return stripWrappingQuotes(cleanText(out.toString()));
    }

    private byte[] synthesizeTurkish(String turkish, long generation) throws Exception {
        if (!isCurrentPipeline(generation)) return new byte[0];

        JSONObject speechMetadata = new JSONObject().put(
                "style",
                "Natural Turkish film dubbing; warm, smooth and conversational; clear diction; medium-fast pace; expressive but never theatrical or announcer-like.");
        JSONObject part = new JSONObject()
                .put("text", turkish)
                .put("speech_metadata", speechMetadata);
        JSONObject contents = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(part));

        JSONObject responseFormat = new JSONObject().put("audio", new JSONObject()
                .put("mimeType", "AUDIO_L16")
                .put("sampleRate", AiDubConfig.GEMINI_OUTPUT_SAMPLE_RATE_HZ));
        JSONObject speechConfig = new JSONObject().put("voiceConfig", new JSONObject()
                .put("voice", AiDubConfig.TTS_VOICE));
        JSONObject generationConfig = new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("responseFormat", responseFormat)
                .put("speechConfig", speechConfig);
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(contents))
                .put("generationConfig", generationConfig);

        JSONObject response = executeJsonRequest(
                AiDubConfig.TTS_MODEL,
                "generateContent",
                body,
                "TTS");

        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IOException("Gemini TTS returned no candidate");
        }
        JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) throw new IOException("Gemini TTS returned no audio");

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject responsePart = parts.optJSONObject(i);
            if (responsePart == null) continue;
            JSONObject inlineData = responsePart.optJSONObject("inlineData");
            if (inlineData == null) continue;
            String data = inlineData.optString("data", "");
            if (data.isEmpty()) continue;
            byte[] decoded = Base64.decode(data, Base64.DEFAULT);
            pcm.write(decoded, 0, decoded.length);
        }
        return pcm.toByteArray();
    }

    private JSONObject executeJsonRequest(
            String model,
            String method,
            JSONObject body,
            String stage) throws Exception {
        String key = endpointProvider.getApiKey();
        RequestBody requestBody = RequestBody.create(JSON_MEDIA_TYPE, body.toString());
        Request request = new Request.Builder()
                .url(GENERATE_CONTENT_BASE + model + ":" + method)
                .header("x-goog-api-key", key)
                .header("Accept", "application/json")
                .post(requestBody)
                .build();

        Response response = httpClient.newCall(request).execute();
        try {
            String responseText = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                String detail = "";
                try {
                    JSONObject errorRoot = new JSONObject(responseText);
                    JSONObject apiError = errorRoot.optJSONObject("error");
                    if (apiError != null) detail = " - " + sanitize(apiError.optString("message", ""));
                } catch (Throwable ignored) {
                }
                throw new IOException("Gemini " + stage + " HTTP " + response.code() + detail);
            }
            if (responseText.trim().isEmpty()) {
                throw new IOException("Gemini " + stage + " returned an empty response");
            }
            return new JSONObject(responseText);
        } finally {
            response.close();
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
            if (isCurrentConnection(generation) && !setupComplete) {
                listener.onError(new IOException("Gemini Transcribe setup timeout (8s)"));
            }
        }, "AiDubTranscribeSetupWatchdog").start();
    }

    private boolean isCurrentConnection(long generation) {
        synchronized (lock) {
            return !closed && generation == connectionGeneration;
        }
    }

    private boolean isCurrentPipeline(long generation) {
        synchronized (lock) {
            return !closed && generation == pipelineGeneration;
        }
    }

    private void resetTranscriptStateLocked() {
        lastInterimWords = Collections.emptyList();
        committedWordCount = 0;
    }

    private static int commonPrefixWordCount(List<String> a, List<String> b) {
        int count = Math.min(a.size(), b.size());
        int i = 0;
        while (i < count && comparableWord(a.get(i)).equals(comparableWord(b.get(i)))) {
            i++;
        }
        return i;
    }

    private static int choosePhraseCut(List<String> words, int start, int stablePrefix) {
        int target = Math.min(stablePrefix, start + AiDubConfig.NATURAL_DUB_TARGET_WORDS);
        int max = Math.min(stablePrefix, start + AiDubConfig.NATURAL_DUB_MAX_WORDS);
        int minPunctuation = Math.min(max, start + AiDubConfig.NATURAL_DUB_MIN_PUNCTUATION_WORDS);
        for (int i = max - 1; i >= minPunctuation - 1; i--) {
            if (endsPhrase(words.get(i))) return i + 1;
        }
        return target;
    }

    private static boolean endsPhrase(String word) {
        if (word == null || word.isEmpty()) return false;
        char c = word.charAt(word.length() - 1);
        return c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == '…';
    }

    private static List<String> splitWords(String text) {
        String cleaned = cleanText(text);
        if (cleaned.isEmpty()) return Collections.emptyList();
        return Arrays.asList(cleaned.split(" "));
    }

    private static String joinWords(List<String> words, int start, int end) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(words.get(i));
        }
        return cleanText(out.toString());
    }

    private static String comparableWord(String word) {
        if (word == null) return "";
        String normalized = word.replaceAll("[^\\p{L}\\p{N}']+", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String cleanText(String text) {
        return text == null ? "" : text.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String stripWrappingQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    private static String limitContext(String value, int maxChars) {
        String cleaned = cleanText(value);
        if (cleaned.length() <= maxChars) return cleaned;
        return cleaned.substring(cleaned.length() - maxChars);
    }

    private static String formatApiError(String stage, JSONObject apiError) {
        int code = apiError.optInt("code", -1);
        String status = apiError.optString("status", "");
        String message = sanitize(apiError.optString("message", ""));
        return "Gemini " + stage + " " + code +
                (status.isEmpty() ? "" : " " + status) +
                (message.isEmpty() ? "" : " - " + message);
    }

    private static String sanitize(String value) {
        String cleaned = cleanText(value);
        return cleaned.length() <= 180 ? cleaned : cleaned.substring(0, 180);
    }

    private static IOException safeTransportError(Throwable error, Response response) {
        if (response != null) {
            return new IOException("Gemini WebSocket HTTP " + response.code());
        }
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
        return new IOException("Gemini WebSocket " + type);
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
            listener.onDiagnostic("Gemini Transcribe WebSocket açıldı");
            try {
                if (!socket.send(createSetupJson())) {
                    throw new IOException("Gemini WebSocket rejected setup frame");
                }
                listener.onDiagnostic("Gemini Transcribe setup gönderildi");
                startSetupWatchdog(generation);
            } catch (Throwable error) {
                listener.onError(new IOException("Gemini Transcribe setup frame failed"));
                socket.close(1011, "setup failed");
            }
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            if (isCurrentConnection(generation)) handleMessage(text);
        }

        @Override
        public void onMessage(WebSocket socket, ByteString bytes) {
            if (isCurrentConnection(generation)) handleMessage(bytes.utf8());
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            socket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
            if (!isCurrentConnection(generation)) return;
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
            if (!isCurrentConnection(generation)) return;
            synchronized (lock) {
                setupComplete = false;
                webSocket = null;
            }
            listener.onError(safeTransportError(t, response));
        }
    }
}
