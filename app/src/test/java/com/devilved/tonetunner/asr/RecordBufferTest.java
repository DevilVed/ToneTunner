package com.devilved.tonetunner.asr;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordBufferTest {
    @After public void clearBuffer() { RecordBuffer.takeOutputBuffer(); }

    @Test public void audioCanBeClaimedByOnlyOneOperation() {
        byte[] pcm = {1, 2, 3, 4};
        RecordBuffer.setOutputBuffer(pcm);
        assertSame(pcm, RecordBuffer.takeOutputBuffer());
        assertNull(RecordBuffer.getOutputBuffer());
        assertNull(RecordBuffer.takeOutputBuffer());
    }

    @Test public void littleEndianSamplesPreserveSignsAndNormalize() {
        float[] samples = RecordBuffer.samples(new byte[]{0, (byte)0x80, 0, 0x40});
        assertArrayEquals(new float[]{-1f, .5f}, samples, .00001f);
    }

    @Test public void lowAmplitudeSamplesKeepTheirPcm16Level() {
        float[] samples = RecordBuffer.samples(new byte[]{0, 0x10, 0, 0x08});
        assertArrayEquals(new float[]{.125f, .0625f}, samples, .00001f);
    }

    @Test public void silenceDoesNotDivideByZero() {
        assertArrayEquals(new float[]{0f, 0f}, RecordBuffer.samples(new byte[4]), 0f);
    }

    @Test public void absentAndOddTailAreSafe() {
        assertEquals(0, RecordBuffer.samples(null).length);
        assertArrayEquals(new float[]{0f}, RecordBuffer.samples(new byte[]{0, 0, 1}), 0f);
    }
}
