package com.liskovsoft.smartyoutubetv2.common.aidub;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AiDubAudioPlayer {
    private final LinkedBlockingDeque<byte[]> queue =
            new LinkedBlockingDeque<>(AiDubConfig.MAX_PENDING_OUTPUT_CHUNKS);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile AudioTrack audioTrack;
    private volatile Thread workerThread;

    public synchronized void start() {
        if (running.get()) return;

        int minBuffer = AudioTrack.getMinBufferSize(
                AiDubConfig.GEMINI_OUTPUT_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) minBuffer = AiDubConfig.GEMINI_OUTPUT_SAMPLE_RATE_HZ;
        int bufferSize = Math.max(minBuffer * 2, AiDubConfig.GEMINI_OUTPUT_SAMPLE_RATE_HZ);

        audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                AiDubConfig.GEMINI_OUTPUT_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM);

        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            audioTrack.release();
            audioTrack = null;
            throw new IllegalStateException("Unable to initialize AI dub AudioTrack");
        }

        paused.set(false);
        running.set(true);
        audioTrack.play();
        workerThread = new Thread(this::runWriter, "AiDubAudioWriter");
        workerThread.start();
    }

    public void enqueue(byte[] pcm24kMono16Le) {
        if (!running.get() || pcm24kMono16Le == null || pcm24kMono16Le.length == 0) return;
        byte[] copy = pcm24kMono16Le.clone();
        if (!queue.offerLast(copy)) {
            queue.pollFirst();
            queue.offerLast(copy);
        }
    }

    public synchronized void setPaused(boolean pause) {
        if (!running.get() || paused.getAndSet(pause) == pause) return;
        AudioTrack track = audioTrack;
        if (track == null) return;
        try {
            if (pause) track.pause(); else track.play();
        } catch (IllegalStateException ignored) {
        }
    }

    public synchronized void flush() {
        queue.clear();
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                if (running.get() && !paused.get()) track.play();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        paused.set(false);
        queue.clear();
        Thread worker = workerThread;
        workerThread = null;
        if (worker != null) worker.interrupt();

        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
        }
    }

    private void runWriter() {
        while (running.get()) {
            try {
                if (paused.get()) {
                    Thread.sleep(20L);
                    continue;
                }
                byte[] data = queue.pollFirst(250, TimeUnit.MILLISECONDS);
                if (data == null) continue;
                AudioTrack track = audioTrack;
                if (track == null) continue;
                int offset = 0;
                while (running.get() && !paused.get() && offset < data.length) {
                    int written = track.write(data, offset, data.length - offset);
                    if (written <= 0) break;
                    offset += written;
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
