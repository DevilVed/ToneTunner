package com.devilved.tonetunner.asr;

/**
 * In-place processing of 16-bit little-endian mono PCM between the microphone and the saved
 * recording: an 80 Hz high-pass filter, then an automatic gain control with a peak limiter.
 *
 * The speech gain only moves toward its target on speech-like frames (6 dB above a tracked noise
 * floor), falls fast and rises at most 40 dB/s. It is applied to speech-like frames only: pauses and
 * background noise pass at unity gain. Every processed frame is then limited so its peak stays at
 * -1 dBFS, which also catches overshoot from the high-pass filter itself.
 * Levels use the reference desktop app's meter: 20 * log10(max(x, 1e-6)).
 */
public final class MicProcessor {

    public enum Boost {
        OFF("off", 0, 0), MEDIUM("medium", -24, 12), MAXIMUM("maximum", -18, 30);

        public final String key;
        final double targetDb;
        final double maxGainDb;

        Boost(String key, double targetDb, double maxGainDb) {
            this.key = key;
            this.targetDb = targetDb;
            this.maxGainDb = maxGainDb;
        }

        public static Boost fromKey(String key) {
            for (Boost boost : values()) if (boost.key.equals(key)) return boost;
            return MAXIMUM;
        }
    }

    private static final double CEILING_DB = -1.0;
    private static final double SPEECH_ABOVE_FLOOR_DB = 6.0;
    private static final double SILENCE_DB = -65.0;
    private static final double RISE_DB_PER_SECOND = 40.0;
    private static final double FLOOR_RISE_DB_PER_SECOND = 3.0;
    private static final double HIGH_PASS_HZ = 80.0;

    private final boolean highPass;
    private final Boost boost;
    private final int sampleRate;

    // Biquad high-pass coefficients (RBJ cookbook, Butterworth Q) and state.
    private final double b0, b1, b2, a1, a2;
    private double x1, x2, y1, y2;

    private double gainDb;
    private double appliedGain = 1.0;
    private double floorDb = Double.NaN;

    private long samples;
    private long clippedSamples;
    private long speechFrames;
    private double speechLevelSumDb;
    private double gainSumDb;
    private double peakDb = Double.NEGATIVE_INFINITY;
    private float[] work = new float[0];

    public MicProcessor(int sampleRate, boolean highPass, Boost boost) {
        this.sampleRate = sampleRate;
        this.highPass = highPass;
        this.boost = boost == null ? Boost.OFF : boost;
        double w0 = 2 * Math.PI * HIGH_PASS_HZ / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * Math.sqrt(0.5));
        double a0 = 1 + alpha;
        b0 = (1 + cos) / 2 / a0;
        b1 = -(1 + cos) / a0;
        b2 = (1 + cos) / 2 / a0;
        a1 = -2 * cos / a0;
        a2 = (1 - alpha) / a0;
    }

    /** Whether processing changes the audio at all; with everything off the bytes pass through. */
    public boolean isActive() {
        return highPass || boost != Boost.OFF;
    }

    public void process(byte[] pcm, int offset, int length) {
        int count = Math.max(0, length) / 2;
        if (count == 0) return;
        if (work.length < count) work = new float[count];
        double sum = 0;
        double peak = 0;
        for (int i = 0; i < count; i++) {
            int raw = (short) ((pcm[offset + 2 * i] & 0xff) | (pcm[offset + 2 * i + 1] << 8));
            if (raw >= Short.MAX_VALUE || raw <= Short.MIN_VALUE) clippedSamples++;
            double x = raw / 32768.0;
            if (highPass) {
                double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = x;
                y2 = y1; y1 = y;
                x = y;
            }
            work[i] = (float) x;
            sum += x * x;
            peak = Math.max(peak, Math.abs(x));
        }
        samples += count;
        double levelDb = db(Math.sqrt(sum / count));
        double framePeakDb = db(peak);
        peakDb = Math.max(peakDb, framePeakDb);
        double seconds = (double) count / sampleRate;

        // Noise floor: follows quiet frames down quickly, creeps up slowly through speech.
        if (Double.isNaN(floorDb) || levelDb < floorDb) floorDb = levelDb;
        else floorDb = Math.min(levelDb, floorDb + FLOOR_RISE_DB_PER_SECOND * seconds);
        boolean speech = levelDb > SILENCE_DB && levelDb >= floorDb + SPEECH_ABOVE_FLOOR_DB;

        if (boost != Boost.OFF && speech) {
            double desired = clamp(boost.targetDb - levelDb, 0, boost.maxGainDb);
            if (desired < gainDb) gainDb += (desired - gainDb) * 0.5;
            else gainDb = Math.min(desired, gainDb + RISE_DB_PER_SECOND * seconds);
        }
        // The limiter may attenuate (negative gain): the filter can overshoot an in-range input.
        double frameGainDb = Math.min(boost != Boost.OFF && speech ? gainDb : 0, CEILING_DB - framePeakDb);
        if (speech) {
            speechFrames++;
            speechLevelSumDb += levelDb;
            gainSumDb += frameGainDb;
        }
        if (!isActive()) return;

        double target = Math.pow(10, frameGainDb / 20);
        // Rising gain is spread over the frame (no click); falling gain applies at once (no overshoot).
        double start = target < appliedGain ? target : appliedGain;
        for (int i = 0; i < count; i++) {
            double gain = start + (target - start) * (i + 1) / count;
            long value = Math.round(work[i] * gain * 32768.0);
            if (value > Short.MAX_VALUE) value = Short.MAX_VALUE;
            if (value < Short.MIN_VALUE) value = Short.MIN_VALUE;
            pcm[offset + 2 * i] = (byte) value;
            pcm[offset + 2 * i + 1] = (byte) (value >> 8);
        }
        appliedGain = target;
    }

    public double getCurrentGainDb() { return gainDb; }

    /** Mean input level of speech-like frames before the boost, or NaN when there were none. */
    public double getAverageSpeechLevelDb() {
        return speechFrames == 0 ? Double.NaN : speechLevelSumDb / speechFrames;
    }

    /** Highest input frame peak, in dBFS. */
    public double getPeakLevelDb() { return peakDb; }

    /** Share of input samples at full scale: the microphone itself was overloaded. */
    public double getClippedRatio() { return samples == 0 ? 0 : (double) clippedSamples / samples; }

    /** Mean boost applied to speech-like frames, in dB. */
    public double getAverageGainDb() { return speechFrames == 0 ? 0 : gainSumDb / speechFrames; }

    private static double db(double linear) {
        return 20 * Math.log10(Math.max(linear, 0.000001));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
