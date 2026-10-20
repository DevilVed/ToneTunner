package com.devilved.tonetunner.cloud;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Wraps the recorder's 16 kHz mono PCM16 samples in a WAV container. */
public final class AudioWav {
    public static final int SAMPLE_RATE_HZ = 16000;
    public static final int HEADER_BYTES = 44;
    public static final int MAX_DURATION_SECONDS = 30;
    public static final int MAX_PCM_BYTES = SAMPLE_RATE_HZ * 2 * MAX_DURATION_SECONDS;
    public static final int MAX_WAV_BYTES = HEADER_BYTES + MAX_PCM_BYTES;

    private AudioWav() {
    }

    public static byte[] encodePcm16Mono(byte[] pcm) {
        if (pcm == null || (pcm.length & 1) != 0) {
            throw new IllegalArgumentException("PCM16 audio must contain complete samples.");
        }
        if (pcm.length > MAX_PCM_BYTES) {
            throw new IllegalArgumentException("PCM16 audio exceeds the supported file size.");
        }

        byte[] wav = new byte[HEADER_BYTES + pcm.length];
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        putAscii(header, "RIFF");
        header.putInt(wav.length - 8);
        putAscii(header, "WAVE");
        putAscii(header, "fmt ");
        header.putInt(16);                 // PCM format chunk size
        header.putShort((short) 1);        // PCM encoding
        header.putShort((short) 1);        // mono
        header.putInt(SAMPLE_RATE_HZ);
        header.putInt(SAMPLE_RATE_HZ * 2); // byte rate
        header.putShort((short) 2);        // block alignment
        header.putShort((short) 16);       // bits per sample
        putAscii(header, "data");
        header.putInt(pcm.length);
        System.arraycopy(pcm, 0, wav, HEADER_BYTES, pcm.length);
        return wav;
    }

    private static void putAscii(ByteBuffer target, String value) {
        for (int i = 0; i < value.length(); i++) {
            target.put((byte) value.charAt(i));
        }
    }
}
