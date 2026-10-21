package com.devilved.tonetunner.asr;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** Microphone preferences from Settings; defaults are phone microphone, maximum noise reduction and boost. */
public final class MicSettings {
    public static final String KEY_INPUT = "micInput";
    public static final String KEY_NOISE_REDUCTION = "micNoiseReduction";
    public static final String KEY_BOOST = "micBoost";

    public enum NoiseReduction {
        /** Plain voice-recognition capture. */
        OFF("off"),
        /** Voice-recognition capture, the platform noise suppressor and an 80 Hz high-pass filter. */
        STANDARD("standard"),
        /** Call-grade capture (multi-microphone noise cancellation) plus everything in STANDARD. */
        MAXIMUM("maximum");

        public final String key;

        NoiseReduction(String key) { this.key = key; }

        public static NoiseReduction fromKey(String key) {
            for (NoiseReduction value : values()) if (value.key.equals(key)) return value;
            return MAXIMUM;
        }
    }

    public final MicRouter.Mode input;
    public final NoiseReduction noiseReduction;
    public final MicProcessor.Boost boost;

    public MicSettings(MicRouter.Mode input, NoiseReduction noiseReduction, MicProcessor.Boost boost) {
        this.input = input;
        this.noiseReduction = noiseReduction;
        this.boost = boost;
    }

    public static MicSettings read(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return new MicSettings(
                MicRouter.Mode.fromKey(preferences.getString(KEY_INPUT, MicRouter.Mode.BUILTIN.key)),
                NoiseReduction.fromKey(preferences.getString(KEY_NOISE_REDUCTION, NoiseReduction.MAXIMUM.key)),
                MicProcessor.Boost.fromKey(preferences.getString(KEY_BOOST, MicProcessor.Boost.MAXIMUM.key)));
    }
}
