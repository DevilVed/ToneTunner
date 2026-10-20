package com.devilved.tonetunner.cloud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public class AudioWavTest {

    @Test
    public void wrapsPcm16MonoInAValidWaveHeader() {
        byte[] pcm = new byte[] {0x01, 0x02, (byte) 0xff, (byte) 0x80};

        byte[] wav = AudioWav.encodePcm16Mono(pcm);

        assertEquals(48, wav.length);
        assertEquals("RIFF", ascii(wav, 0, 4));
        assertEquals(40, littleInt(wav, 4));
        assertEquals("WAVE", ascii(wav, 8, 4));
        assertEquals("fmt ", ascii(wav, 12, 4));
        assertEquals(16, littleInt(wav, 16));
        assertEquals(1, littleShort(wav, 20));
        assertEquals(1, littleShort(wav, 22));
        assertEquals(16000, littleInt(wav, 24));
        assertEquals(32000, littleInt(wav, 28));
        assertEquals(2, littleShort(wav, 32));
        assertEquals(16, littleShort(wav, 34));
        assertEquals("data", ascii(wav, 36, 4));
        assertEquals(4, littleInt(wav, 40));
        assertArrayEquals(pcm, new byte[] {wav[44], wav[45], wav[46], wav[47]});
    }

    @Test
    public void rejectsNullOrIncompletePcmSamples() {
        assertThrows(IllegalArgumentException.class, () -> AudioWav.encodePcm16Mono(null));
        assertThrows(IllegalArgumentException.class,
                () -> AudioWav.encodePcm16Mono(new byte[] {0x01}));
    }

    @Test
    public void rejectsAudioLongerThanOneThirtySecondRequestChunk() {
        byte[] pcm = new byte[AudioWav.MAX_PCM_BYTES + 2];

        assertThrows(IllegalArgumentException.class, () -> AudioWav.encodePcm16Mono(pcm));
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int littleInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int littleShort(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }
}
