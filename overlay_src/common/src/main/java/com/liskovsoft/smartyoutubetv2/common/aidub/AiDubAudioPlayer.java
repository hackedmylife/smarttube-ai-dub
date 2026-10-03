package com.liskovsoft.smartyoutubetv2.common.aidub;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AiDubAudioPlayer {
    private final LinkedBlockingDeque<byte[]> queue =
            new LinkedBlockingDeque<>(AiDubConfig.MAX_PENDING_OUTPUT_CHUNKS);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicInteger playbackGeneration = new AtomicInteger(0);

    private volatile AudioTrack audioTrack;
    private volatile Thread workerThread;
    private volatile boolean playbackStarted;
    private volatile int prebufferedBytes;

    public synchronized void start() {
        if (running.get()) return;

        int minBuffer = AudioTrack.getMinBufferSize(
                AiDubConfig.GEMINI_OUTPUT_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) minBuffer = AiDubConfig.GEMINI_OUTPUT_SAMPLE_RATE_HZ;

        int bufferSize = Math.max(
                minBuffer * 2,
                AiDubConfig.OUTPUT_AUDIO_TRACK_BUFFER_BYTES);

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

        queue.clear();
        prebufferedBytes = 0;
        playbackStarted = false;
        paused.set(false);
        running.set(true);
        playbackGeneration.incrementAndGet();

        // Do not call play() yet. We first seed AudioTrack with a small amount
        // of translated PCM so network jitter does not become audible gaps.
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
            if (pause) {
                track.pause();
            } else if (playbackStarted) {
                track.play();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    public synchronized void flush() {
        queue.clear();
        prebufferedBytes = 0;
        playbackStarted = false;
        playbackGeneration.incrementAndGet();

        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        paused.set(false);
        queue.clear();
        playbackStarted = false;
        prebufferedBytes = 0;
        playbackGeneration.incrementAndGet();

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
                if (data == null) {
                    // A short line can end before the prebuffer target. Play
                    // its tail rather than waiting forever for another line.
                    if (!playbackStarted && prebufferedBytes > 0) {
                        synchronized (this) {
                            if (running.get() && !paused.get() && audioTrack != null
                                    && !playbackStarted && prebufferedBytes > 0) {
                                audioTrack.play();
                                playbackStarted = true;
                            }
                        }
                    }
                    continue;
                }

                AudioTrack track = audioTrack;
                if (track == null) continue;
                int generation = playbackGeneration.get();
                writeChunk(track, data, generation);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeChunk(AudioTrack track, byte[] data, int generation) {
        int offset = 0;
        while (running.get()
                && !paused.get()
                && generation == playbackGeneration.get()
                && offset < data.length) {
            int written;
            try {
                // AudioTrack.write is blocking. Before play(), never write
                // a whole large packet: a full stopped track cannot drain.
                int writeBytes = data.length - offset;
                if (!playbackStarted) {
                    writeBytes = Math.min(writeBytes,
                            AiDubConfig.OUTPUT_PREBUFFER_BYTES - prebufferedBytes);
                }
                written = track.write(data, offset, writeBytes);
            } catch (IllegalStateException error) {
                return;
            }
            if (written <= 0) return;
            offset += written;

            if (!playbackStarted && generation == playbackGeneration.get()) {
                prebufferedBytes += written;
                if (prebufferedBytes >= AiDubConfig.OUTPUT_PREBUFFER_BYTES && !paused.get()) {
                    try {
                        track.play();
                        playbackStarted = true;
                    } catch (IllegalStateException ignored) {
                        return;
                    }
                }
            }
        }
    }
}
