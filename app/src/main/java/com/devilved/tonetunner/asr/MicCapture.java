package com.devilved.tonetunner.asr;

/** How one recording was captured, for the usage dashboard. Contains no audio. */
public final class MicCapture {
    /** "builtin", "bluetooth", "wired", "other" or "automatic" (Android chose). */
    public final String device;
    public final boolean fellBack;
    public final String noiseReduction;
    public final String boost;
    /** Mean speech level before the boost in dBFS, or NaN without speech. */
    public final double speechLevelDb;
    public final double peakLevelDb;
    public final double clippedRatio;
    public final double averageGainDb;

    public MicCapture(String device, boolean fellBack, String noiseReduction, String boost,
                      double speechLevelDb, double peakLevelDb, double clippedRatio, double averageGainDb) {
        this.device = device;
        this.fellBack = fellBack;
        this.noiseReduction = noiseReduction;
        this.boost = boost;
        this.speechLevelDb = speechLevelDb;
        this.peakLevelDb = peakLevelDb;
        this.clippedRatio = clippedRatio;
        this.averageGainDb = averageGainDb;
    }
}
