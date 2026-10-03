package com.liskovsoft.smartyoutubetv2.common.aidub;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

public final class AiDubSession implements AiDubRuntime.PcmSink,
        GeminiNaturalDubClient.Listener {

    public interface Listener {
        void onStateChanged(AiDubState state, Throwable error);
        default void onDiagnostic(String message) {}
    }

    private static final long TRANSCRIBE_ROTATION_MS = 9L * 60L * 1000L;
    private static final int MAX_RECONNECT_ATTEMPTS = 6;
    private static final long RECONNECT_BASE_DELAY_MS = 300L;
    private static final long PIPELINE_BACKOFF_BASE_MS = 750L;

    private static final class CapturedPcm {
        final byte[] data;
        final int sampleRateHz;
        final int channelCount;

        CapturedPcm(byte[] data, int sampleRateHz, int channelCount) {
            this.data = data;
            this.sampleRateHz = sampleRateHz;
            this.channelCount = channelCount;
        }
    }

    private final Listener listener;
    private final GeminiNaturalDubClient naturalDubClient;
    private final AiDubAudioPlayer audioPlayer = new AiDubAudioPlayer();
    private final Pcm16Resampler resampler =
            new Pcm16Resampler(AiDubConfig.GEMINI_INPUT_SAMPLE_RATE_HZ);
    private final PcmChunker chunker;
    private final LinkedBlockingDeque<CapturedPcm> captureQueue =
            new LinkedBlockingDeque<>(AiDubConfig.MAX_PENDING_INPUT_CHUNKS * 2);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private volatile Thread captureWorker;
    private volatile AiDubState state = AiDubState.OFF;
    private volatile boolean firstCapturedPcmNotified;
    private volatile boolean firstTranslatedPcmNotified;
    private volatile boolean needsReconnect;
    private volatile int reconnectAttempts;
    private volatile int transientPipelineErrors;
    private volatile long rotationGeneration;

    public AiDubSession(
            OkHttpClient httpClient,
            GeminiEndpointProvider endpointProvider,
            Listener listener) {
        this.listener = listener;
        this.naturalDubClient = new GeminiNaturalDubClient(httpClient, endpointProvider, this);
        this.chunker = new PcmChunker(
                AiDubConfig.INPUT_CHUNK_BYTES,
                naturalDubClient::sendPcm16kMono);
    }

    public synchronized void start() {
        if (running.get()) return;
        paused.set(false);
        reconnecting.set(false);
        needsReconnect = false;
        reconnectAttempts = 0;
        transientPipelineErrors = 0;
        rotationGeneration++;
        firstCapturedPcmNotified = false;
        firstTranslatedPcmNotified = false;
        setState(AiDubState.CONNECTING, null);
        try {
            audioPlayer.start();
            running.set(true);
            AiDubRuntime.setActiveSink(this);
            captureWorker = new Thread(this::runCaptureWorker, "AiDubCaptureWorker");
            captureWorker.start();
            naturalDubClient.connect();
        } catch (Throwable error) {
            running.set(false);
            AiDubRuntime.clearActiveSink(this);
            captureWorker = null;
            audioPlayer.stop();
            setState(AiDubState.ERROR, error);
        }
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        paused.set(false);
        reconnecting.set(false);
        needsReconnect = false;
        reconnectAttempts = 0;
        transientPipelineErrors = 0;
        rotationGeneration++;
        AiDubRuntime.clearActiveSink(this);
        Thread worker = captureWorker;
        captureWorker = null;
        if (worker != null) worker.interrupt();
        resetPipeline();
        naturalDubClient.close();
        audioPlayer.stop();
        setState(AiDubState.OFF, null);
    }

    public synchronized void setPaused(boolean pause) {
        if (!running.get() || paused.getAndSet(pause) == pause) return;
        if (pause) {
            audioPlayer.setPaused(true);
            resetPipeline();
        } else {
            audioPlayer.setPaused(false);
            if (needsReconnect) {
                requestReconnect("oynatma devam etti");
            }
        }
    }

    public synchronized void flush() {
        if (running.get()) resetPipeline();
    }

    public synchronized void restartAfterDiscontinuity() {
        if (!running.get() || state == AiDubState.ERROR) return;
        rotationGeneration++;
        reconnecting.set(false);
        needsReconnect = false;
        reconnectAttempts = 0;
        transientPipelineErrors = 0;
        resetPipeline();
        setState(AiDubState.CONNECTING, null);
        naturalDubClient.reconnect();
    }

    @Override
    public void onPcm16(byte[] pcm16Le, int sampleRateHz, int channelCount) {
        if (!running.get() || paused.get() || state == AiDubState.ERROR
                || pcm16Le == null || pcm16Le.length == 0) return;
        if (!firstCapturedPcmNotified) {
            firstCapturedPcmNotified = true;
            listener.onDiagnostic("SmartTube PCM yakalandı: " + sampleRateHz + " Hz / " + channelCount + " kanal");
        }
        CapturedPcm captured = new CapturedPcm(pcm16Le, sampleRateHz, channelCount);
        if (!captureQueue.offerLast(captured)) {
            captureQueue.pollFirst();
            captureQueue.offerLast(captured);
        }
    }

    @Override
    public void onAudioPipelineFlushed() {
        // ExoPlayer may flush individual audio processors during ordinary
        // renderer maintenance. Real seeks/timeline jumps are handled by the
        // controller's onPositionDiscontinuity() path.
    }

    @Override
    public void onReady() {
        needsReconnect = false;
        reconnectAttempts = 0;
        reconnecting.set(false);
        listener.onDiagnostic(firstTranslatedPcmNotified
                ? "Doğal dublaj transkripsiyonu yeniden bağlandı"
                : "Doğal dublaj transkripsiyonu hazır");

        // A reconnect must not make the UI/mute policy briefly fall back to the
        // original language. Once dubbing has started, keep the DUBBING state
        // across WebSocket rotations and resume feeding Turkish PCM seamlessly.
        if (firstTranslatedPcmNotified) {
            if (state != AiDubState.DUBBING) {
                setState(AiDubState.DUBBING, null);
            }
        } else {
            setState(AiDubState.READY, null);
        }
        scheduleTranscribeRotation();
    }

    @Override
    public void onTranslatedPcm(byte[] pcm24kMono16Le) {
        if (running.get() && !paused.get()
                && (state == AiDubState.READY || state == AiDubState.DUBBING)
                && pcm24kMono16Le != null && pcm24kMono16Le.length > 0) {
            transientPipelineErrors = 0;
            if (!firstTranslatedPcmNotified) {
                firstTranslatedPcmNotified = true;
                listener.onDiagnostic("Doğal Türkçe dublaj sesi geldi");
            }
            if (state == AiDubState.READY) {
                setState(AiDubState.DUBBING, null);
            }
            audioPlayer.enqueue(pcm24kMono16Le);
        }
    }

    @Override
    public void onError(Throwable error) {
        if (!running.get() || state == AiDubState.ERROR) return;
        if (isRecoverableTranscribeError(error)) {
            needsReconnect = true;
            reconnecting.set(false);
            requestReconnect(error == null ? "bağlantı hatası" : safeMessage(error));
            return;
        }
        if (isRecoverablePipelineServiceError(error)) {
            int failures = Math.min(++transientPipelineErrors, 4);
            long delayMs = Math.min(6_000L,
                    PIPELINE_BACKOFF_BASE_MS * (1L << Math.max(0, failures - 1)));
            listener.onDiagnostic("Gemini servis yoğunluğu; doğal dublaj devam edecek");
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        enterFatalError(error);
    }

    @Override
    public void onDiagnostic(String message) {
        listener.onDiagnostic(message);
    }

    @Override
    public void onClosed(int code, String reason) {
        if (!running.get() || state == AiDubState.ERROR) return;
        needsReconnect = true;
        reconnecting.set(false);
        String detail = reason == null || reason.trim().isEmpty()
                ? ""
                : " - " + reason.trim();
        requestReconnect("Gemini Transcribe closed (" + code + ")" + detail);
    }

    private void runCaptureWorker() {
        while (running.get()) {
            try {
                CapturedPcm captured = captureQueue.pollFirst(250, TimeUnit.MILLISECONDS);
                if (captured == null || paused.get()) continue;
                byte[] converted = resampler.toMono(
                        captured.data,
                        captured.sampleRateHz,
                        captured.channelCount);
                chunker.offer(converted);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                enterFatalError(error);
            }
        }
    }

    private void scheduleTranscribeRotation() {
        final long generation = ++rotationGeneration;
        new Thread(() -> {
            try {
                Thread.sleep(TRANSCRIBE_ROTATION_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get() || state == AiDubState.ERROR || generation != rotationGeneration) {
                return;
            }
            if (paused.get()) {
                needsReconnect = true;
                listener.onDiagnostic("Gemini Transcribe oturumu oynatma devam edince yenilenecek");
                return;
            }
            needsReconnect = true;
            reconnecting.set(false);
            requestReconnect("planlı 9 dakikalık oturum yenileme");
        }, "AiDubTranscribeRotation").start();
    }

    private void requestReconnect(String reason) {
        if (!running.get() || state == AiDubState.ERROR) return;
        if (paused.get()) {
            needsReconnect = true;
            return;
        }
        if (!reconnecting.compareAndSet(false, true)) return;

        final int attempt = ++reconnectAttempts;
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            reconnecting.set(false);
            enterFatalError(new IllegalStateException(
                    "Gemini Transcribe yeniden bağlanamadı: " + reason));
            return;
        }

        long multiplier = 1L << Math.min(attempt - 1, 3);
        final long delayMs = Math.min(2_400L, RECONNECT_BASE_DELAY_MS * multiplier);
        listener.onDiagnostic("Gemini Transcribe bağlantısı yenileniyor (" + attempt + "/" +
                MAX_RECONNECT_ATTEMPTS + ")");

        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                reconnecting.set(false);
                return;
            }

            synchronized (AiDubSession.this) {
                if (!running.get() || paused.get() || state == AiDubState.ERROR) {
                    reconnecting.set(false);
                    return;
                }

                // Drop only source-ASR state. Do not flush AudioTrack: Turkish
                // audio already synthesized before the rotation should finish
                // playing instead of being cut off mid-sentence.
                captureQueue.clear();
                resampler.reset();
                chunker.reset();
                rotationGeneration++;
                needsReconnect = false;
                naturalDubClient.reconnect();
            }
        }, "AiDubTranscribeReconnect").start();
    }

    private boolean isRecoverableTranscribeError(Throwable error) {
        String message = safeMessage(error).toLowerCase();
        if (message.contains("401") || message.contains("403")
                || message.contains("api key") || message.contains("permission_denied")) {
            return false;
        }
        return message.contains("gemini websocket")
                || message.contains("transcribe setup")
                || message.contains("live transcribe 429")
                || message.contains("live transcribe 500")
                || message.contains("live transcribe 502")
                || message.contains("live transcribe 503")
                || message.contains("live transcribe 504");
    }

    private boolean isRecoverablePipelineServiceError(Throwable error) {
        String message = safeMessage(error).toLowerCase();
        boolean pipelineStage = message.contains("gemini translation http")
                || message.contains("gemini tts http");
        if (!pipelineStage) return false;
        return message.contains("429")
                || message.contains("500")
                || message.contains("502")
                || message.contains("503")
                || message.contains("504");
    }

    private synchronized void enterFatalError(Throwable error) {
        if (!running.get() || state == AiDubState.ERROR) return;
        reconnecting.set(false);
        needsReconnect = false;
        rotationGeneration++;
        AiDubRuntime.clearActiveSink(this);
        resetPipeline();
        naturalDubClient.close();
        setState(AiDubState.ERROR, error);
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null) return "bilinmeyen hata";
        String message = error.getMessage().replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ").trim();
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    private void resetPipeline() {
        captureQueue.clear();
        resampler.reset();
        chunker.reset();
        naturalDubClient.resetPendingInput();
        audioPlayer.flush();
    }

    private void setState(AiDubState next, Throwable error) {
        state = next;
        listener.onStateChanged(next, error);
    }
}
