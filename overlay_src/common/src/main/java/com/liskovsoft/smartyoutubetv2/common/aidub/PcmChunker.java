package com.liskovsoft.smartyoutubetv2.common.aidub;

import java.util.Arrays;

/** Collects arbitrary PCM byte blocks and emits exact fixed-size chunks. */
public final class PcmChunker {
    public interface Listener {
        void onChunk(byte[] chunk);
    }

    private final int chunkBytes;
    private final Listener listener;
    private byte[] pending;
    private int pendingSize;

    public PcmChunker(int chunkBytes, Listener listener) {
        if (chunkBytes <= 0) {
            throw new IllegalArgumentException("chunkBytes must be > 0");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener == null");
        }
        this.chunkBytes = chunkBytes;
        this.listener = listener;
        this.pending = new byte[chunkBytes * 2];
    }

    public synchronized void offer(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        ensureCapacity(pendingSize + data.length);
        System.arraycopy(data, 0, pending, pendingSize, data.length);
        pendingSize += data.length;

        int offset = 0;
        while (pendingSize - offset >= chunkBytes) {
            listener.onChunk(Arrays.copyOfRange(pending, offset, offset + chunkBytes));
            offset += chunkBytes;
        }

        if (offset > 0) {
            int remainder = pendingSize - offset;
            if (remainder > 0) {
                System.arraycopy(pending, offset, pending, 0, remainder);
            }
            pendingSize = remainder;
        }
    }

    public synchronized void reset() {
        pendingSize = 0;
    }

    private void ensureCapacity(int capacity) {
        if (capacity <= pending.length) {
            return;
        }
        int newSize = pending.length;
        while (newSize < capacity) {
            newSize *= 2;
        }
        pending = Arrays.copyOf(pending, newSize);
    }
}
