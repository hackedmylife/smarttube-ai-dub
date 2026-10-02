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

    private volatile Thread captureWorker;
    private volatile AiDubState state = AiDubState.OFF;
    private volatile boolean firstCapturedPcmNotified;
    private volatile boolean firstTranslatedPcmNotified;

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
        }
    }

    public synchronized void flush() {
        if (running.get()) resetPipeline();
    }

    public synchronized void restartAfterDiscontinuity() {
        if (!running.get() || state == AiDubState.ERROR) return;
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
        listener.onDiagnostic("Doğal dublaj transkripsiyonu hazır");
        setState(AiDubState.READY, null);
    }

    @Override
    public void onTranslatedPcm(byte[] pcm24kMono16Le) {
        if (running.get() && !paused.get()
                && (state == AiDubState.READY || state == AiDubState.DUBBING)
                && pcm24kMono16Le != null && pcm24kMono16Le.length > 0) {
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
        if (running.get() && state != AiDubState.ERROR) {
            AiDubRuntime.clearActiveSink(this);
            resetPipeline();
            naturalDubClient.close();
            setState(AiDubState.ERROR, error);
        }
    }

    @Override
    public void onDiagnostic(String message) {
        listener.onDiagnostic(message);
    }

    @Override
    public void onClosed(int code, String reason) {
        if (running.get() && state != AiDubState.ERROR) {
            String detail = reason == null || reason.trim().isEmpty()
                    ? ""
                    : " - " + reason.trim();
            onError(new IllegalStateException(
                    "Gemini Transcribe closed (" + code + ")" + detail));
        }
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
                onError(error);
            }
        }
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
