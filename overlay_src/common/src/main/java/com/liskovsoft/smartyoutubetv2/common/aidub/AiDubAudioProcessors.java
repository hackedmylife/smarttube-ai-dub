package com.liskovsoft.smartyoutubetv2.common.aidub;

import com.google.android.exoplayer2.audio.AudioProcessor;

import java.util.Arrays;

public final class AiDubAudioProcessors {
    private AiDubAudioProcessors() {
    }

    public static AudioProcessor[] appendTap(AudioProcessor[] existing) {
        AudioProcessor[] base = existing == null ? new AudioProcessor[0] : existing;
        for (AudioProcessor processor : base) {
            if (processor instanceof AiDubPcmTapAudioProcessor) {
                return base;
            }
        }
        AudioProcessor[] result = Arrays.copyOf(base, base.length + 1);
        result[base.length] = new AiDubPcmTapAudioProcessor();
        return result;
    }
}
