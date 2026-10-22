package com.devilved.tonetunner.asr;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/** Software noise filter and microphone boost: loud enough for transcription, never clipped. */
public class MicProcessorTest {
    private static final int RATE = 16_000;
    private static final int FRAME = 480;

    @Test public void offAndOffLeavesAudioByteForByte() {
        MicProcessor processor = new MicProcessor(RATE, false, MicProcessor.Boost.OFF);
        byte[] audio = sine(300, -30, RATE);
        byte[] original = audio.clone();
        processAll(processor, audio, FRAME);
        assertArrayEquals(original, audio);
        assertFalse(processor.isActive());
    }

    @Test public void maximumBoostBringsQuietSpeechToTheTargetLevel() {
        MicProcessor processor = new MicProcessor(RATE, true, MicProcessor.Boost.MAXIMUM);
        byte[] audio = speechLike(-42, 4 * RATE, 1);
        processAll(processor, audio, FRAME);
        double tail = rmsDb(audio, 3 * RATE, 4 * RATE);
        assertEquals("quiet speech should end near -18 dBFS", -18, tail, 3);
        assertTrue("never above -1 dBFS", peakDb(audio, 0, audio.length / 2) <= -0.9);
        assertTrue(processor.getAverageGainDb() > 10);
    }

    @Test public void gainRisesQuicklyEnoughForShortDictations() {
        MicProcessor processor = new MicProcessor(RATE, true, MicProcessor.Boost.MAXIMUM);
        byte[] audio = speechLike(-42, 2 * RATE, 2);
        processAll(processor, audio, FRAME);
        assertTrue("most of the boost must be reached within two seconds",
                rmsDb(audio, (int) (1.5 * RATE), 2 * RATE) > -24);
    }

    @Test public void mediumBoostIsCappedAtTwelveDecibels() {
        MicProcessor processor = new MicProcessor(RATE, false, MicProcessor.Boost.MEDIUM);
        byte[] audio = speechLike(-55, 5 * RATE, 3);
        double before = rmsDb(audio, 4 * RATE, 5 * RATE);
        processAll(processor, audio, FRAME);
        assertEquals(before + 12, rmsDb(audio, 4 * RATE, 5 * RATE), 1.0);
    }

    @Test public void loudInputIsNeverAmplifiedIntoClipping() {
        MicProcessor processor = new MicProcessor(RATE, true, MicProcessor.Boost.MAXIMUM);
        byte[] audio = speechLike(-6, 3 * RATE, 4);
        processAll(processor, audio, FRAME);
        assertTrue(peakDb(audio, 0, audio.length / 2) <= -0.9);
        assertEquals(0, processor.getClippedRatio(), 0);
    }

    @Test public void steadyBackgroundNoiseIsNotPumpedUp() {
        MicProcessor processor = new MicProcessor(RATE, false, MicProcessor.Boost.MAXIMUM);
        byte[] audio = noise(-50, 5 * RATE, 5);
        double before = rmsDb(audio, 0, 5 * RATE);
        processAll(processor, audio, FRAME);
        assertEquals("noise alone must stay near its own level", before, rmsDb(audio, 0, 5 * RATE), 2);
    }

    @Test public void gainHoldsThroughPausesInsteadOfClimbingOnNoise() {
        MicProcessor processor = new MicProcessor(RATE, false, MicProcessor.Boost.MAXIMUM);
        byte[] speech = speechLike(-30, 3 * RATE, 6);
        processAll(processor, speech, FRAME);
        double gainAfterSpeech = processor.getCurrentGainDb();
        byte[] pause = noise(-60, 3 * RATE, 7);
        processAll(processor, pause, FRAME);
        assertTrue(processor.getCurrentGainDb() <= gainAfterSpeech + 0.01);
    }

    @Test public void noiseAfterSpeechIsNotAmplified() {
        MicProcessor processor = new MicProcessor(RATE, false, MicProcessor.Boost.MAXIMUM);
        processAll(processor, speechLike(-42, 5 * RATE, 9), FRAME);
        assertTrue("quiet speech was boosted", processor.getCurrentGainDb() > 20);
        short[] s = new short[3 * RATE];
        Random random = new Random(123);
        for (int i = 0; i < s.length; i++) s[i] = (short) (random.nextInt(41) - 20);
        byte[] pause = bytes(s);
        double before = rmsDb(pause, 0, s.length);
        processAll(processor, pause, FRAME);
        assertEquals("pause noise must stay at its raw level", before, rmsDb(pause, 0, s.length), 3);
    }

    @Test public void limiterCatchesHighPassOvershootOfInRangeInput() {
        for (MicProcessor.Boost boost : MicProcessor.Boost.values()) {
            MicProcessor processor = new MicProcessor(RATE, true, boost);
            short[] s = new short[FRAME];
            for (int i = 0; i < FRAME; i++) s[i] = (short) (i < FRAME / 2 ? 30_000 : -30_000);
            byte[] audio = bytes(s);
            processor.process(audio, 0, audio.length);
            assertEquals(0, processor.getClippedRatio(), 0);
            assertTrue(boost + ": filter overshoot must be limited to -1 dBFS",
                    peakDb(audio, 0, FRAME) <= -0.99);
        }
    }

    @Test public void highPassRemovesDcOffsetAndKeepsSpeechFrequencies() {
        MicProcessor processor = new MicProcessor(RATE, true, MicProcessor.Boost.OFF);
        byte[] dc = constant(5000, RATE);
        processAll(processor, dc, FRAME);
        assertTrue("DC offset removed", Math.abs(mean(dc, RATE / 2, RATE)) < 50);

        MicProcessor tone = new MicProcessor(RATE, true, MicProcessor.Boost.OFF);
        byte[] voice = sine(1000, -20, RATE);
        double before = rmsDb(voice, RATE / 2, RATE);
        processAll(tone, voice, FRAME);
        assertEquals(before, rmsDb(voice, RATE / 2, RATE), 0.5);

        MicProcessor rumble = new MicProcessor(RATE, true, MicProcessor.Boost.OFF);
        byte[] low = sine(20, -20, RATE);
        double lowBefore = rmsDb(low, RATE / 2, RATE);
        processAll(rumble, low, FRAME);
        assertTrue("20 Hz rumble attenuated by more than 15 dB", rmsDb(low, RATE / 2, RATE) < lowBefore - 15);
    }

    @Test public void partialOddAndEmptyFramesAreHandled() {
        MicProcessor processor = new MicProcessor(RATE, true, MicProcessor.Boost.MAXIMUM);
        byte[] audio = speechLike(-40, RATE, 8);
        int offset = 0;
        int[] sizes = {240, 2, 0, 958, 480 * 2, 14};
        int i = 0;
        while (offset < audio.length) {
            int size = Math.min(sizes[i++ % sizes.length], audio.length - offset);
            processor.process(audio, offset, size);
            offset += size;
        }
        assertTrue(peakDb(audio, 0, audio.length / 2) <= -0.9);
        processor.process(new byte[0], 0, 0);
        processor.process(new byte[3], 0, 3); // A trailing odd byte is ignored, not read past.
    }

    @Test public void clippedInputIsCountedForTheDashboard() {
        MicProcessor processor = new MicProcessor(RATE, false, MicProcessor.Boost.OFF);
        byte[] clipped = constant(Short.MAX_VALUE, FRAME);
        processor.process(clipped, 0, clipped.length);
        assertEquals(1.0, processor.getClippedRatio(), 1e-9);
        assertTrue(processor.getPeakLevelDb() > -0.1);
    }

    @Test public void statisticsAreEmptyBeforeAudio() {
        MicProcessor processor = new MicProcessor(RATE, true, MicProcessor.Boost.MAXIMUM);
        assertTrue(Double.isNaN(processor.getAverageSpeechLevelDb()));
        assertEquals(0, processor.getClippedRatio(), 0);
        assertEquals(0, processor.getAverageGainDb(), 0);
    }

    // ---- signal helpers ----

    private static void processAll(MicProcessor processor, byte[] audio, int frameSamples) {
        for (int offset = 0; offset < audio.length; offset += frameSamples * 2) {
            processor.process(audio, offset, Math.min(frameSamples * 2, audio.length - offset));
        }
    }

    private static byte[] sine(double hz, double dbfs, int samples) {
        double amp = 32767 * Math.pow(10, dbfs / 20) * Math.sqrt(2);
        short[] s = new short[samples];
        for (int i = 0; i < samples; i++) s[i] = (short) Math.round(amp * Math.sin(2 * Math.PI * hz * i / RATE));
        return bytes(s);
    }

    /** Syllable-like bursts of a voiced tone mix with short gaps, at an overall speech RMS of dbfs. */
    private static byte[] speechLike(double dbfs, int samples, long seed) {
        Random random = new Random(seed);
        double amp = 32767 * Math.pow(10, dbfs / 20);
        short[] s = new short[samples];
        for (int i = 0; i < samples; i++) {
            double t = (double) i / RATE;
            double envelope = 0.6 + 0.4 * Math.sin(2 * Math.PI * 4 * t);
            double voiced = Math.sin(2 * Math.PI * 180 * t) + 0.5 * Math.sin(2 * Math.PI * 540 * t)
                    + 0.3 * Math.sin(2 * Math.PI * 1200 * t);
            s[i] = (short) Math.round(amp * envelope * voiced + amp * 0.02 * random.nextGaussian());
        }
        return bytes(s);
    }

    private static byte[] noise(double dbfs, int samples, long seed) {
        Random random = new Random(seed);
        double amp = 32767 * Math.pow(10, dbfs / 20);
        short[] s = new short[samples];
        for (int i = 0; i < samples; i++) s[i] = (short) Math.round(amp * random.nextGaussian());
        return bytes(s);
    }

    private static byte[] constant(int value, int samples) {
        short[] s = new short[samples];
        java.util.Arrays.fill(s, (short) value);
        return bytes(s);
    }

    private static byte[] bytes(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            b[2 * i] = (byte) s[i];
            b[2 * i + 1] = (byte) (s[i] >> 8);
        }
        return b;
    }

    private static int sample(byte[] b, int i) {
        return (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
    }

    private static double rmsDb(byte[] b, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) { double v = sample(b, i) / 32768.0; sum += v * v; }
        return 20 * Math.log10(Math.sqrt(sum / (to - from)) + 1e-12);
    }

    private static double peakDb(byte[] b, int from, int to) {
        int peak = 0;
        for (int i = from; i < to; i++) peak = Math.max(peak, Math.abs(sample(b, i)));
        return 20 * Math.log10(peak / 32768.0 + 1e-12);
    }

    private static double mean(byte[] b, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += sample(b, i);
        return sum / (to - from);
    }
}
