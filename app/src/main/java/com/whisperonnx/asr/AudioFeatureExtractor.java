package com.whisperonnx.asr;

import android.util.Log;

import org.jtransforms.fft.FloatFFT_1D;

import java.util.Arrays;

/**
 * Native Java implementation of Whisper's audio feature extraction.
 * Replaces Whisper_initializer.onnx for models that don't include it.
 * 
 * Implements: PCM audio -> Log Mel Spectrogram
 * 
 * DSP Parameters (from preprocessor_config.json):
 * - Sample rate: 16000 Hz
 * - N_FFT: 400
 * - Hop length: 160 (10ms frames)
 * - Mel bins: 80 (Small) or 128 (Large V3)
 * - Max frames: 3000 (30 seconds)
 */
public class AudioFeatureExtractor {

    private static final String TAG = "AudioFeatureExtractor";

    // Audio parameters
    public static final int SAMPLE_RATE = 16000;
    public static final int N_FFT = 400;
    public static final int HOP_LENGTH = 160;
    public static final int MAX_FRAMES = 3000; // 30 seconds
    public static final int CHUNK_SAMPLES = 480000; // 30 seconds @ 16kHz

    // Mel filterbank
    private final int numMelBins;
    private final float[][] melFilterbank;
    private final FloatFFT_1D fft;

    // Hann window
    private final float[] hannWindow;

    public AudioFeatureExtractor(int numMelBins) {
        this.numMelBins = numMelBins;
        this.fft = new FloatFFT_1D(N_FFT);
        this.hannWindow = createHannWindow(N_FFT);
        this.melFilterbank = createMelFilterbank(numMelBins, N_FFT, SAMPLE_RATE);
        Log.d(TAG, "Initialized AudioFeatureExtractor with " + numMelBins + " mel bins");
    }

    /**
     * Extract log mel spectrogram features from audio.
     * 
     * @param audio PCM audio samples, normalized to [-1.0, 1.0]
     * @return Log mel spectrogram [1][numMelBins][numFrames] padded to MAX_FRAMES
     */
    public float[][][] extractFeatures(float[] audio) {
        // Pad audio to ensure we get MAX_FRAMES
        int requiredSamples = (MAX_FRAMES - 1) * HOP_LENGTH + N_FFT;
        float[] paddedAudio;
        if (audio.length < requiredSamples) {
            paddedAudio = new float[requiredSamples];
            System.arraycopy(audio, 0, paddedAudio, 0, audio.length);
            // Rest is already zero-padded
        } else {
            paddedAudio = audio;
        }

        // Calculate number of frames
        int numFrames = Math.min(MAX_FRAMES, (paddedAudio.length - N_FFT) / HOP_LENGTH + 1);

        // Compute STFT and mel spectrogram
        float[][] melSpectrogram = new float[numMelBins][numFrames];

        for (int frame = 0; frame < numFrames; frame++) {
            int start = frame * HOP_LENGTH;

            // Extract frame and apply Hann window
            float[] windowedFrame = new float[N_FFT * 2]; // Complex array for JTransforms
            for (int i = 0; i < N_FFT; i++) {
                windowedFrame[i] = paddedAudio[start + i] * hannWindow[i];
            }

            // Perform FFT
            fft.realForwardFull(windowedFrame);

            // Compute power spectrum (magnitude squared)
            float[] powerSpectrum = new float[N_FFT / 2 + 1];
            for (int i = 0; i <= N_FFT / 2; i++) {
                float real = windowedFrame[2 * i];
                float imag = windowedFrame[2 * i + 1];
                powerSpectrum[i] = real * real + imag * imag;
            }

            // Apply mel filterbank
            for (int mel = 0; mel < numMelBins; mel++) {
                float sum = 0;
                for (int k = 0; k < powerSpectrum.length; k++) {
                    sum += melFilterbank[mel][k] * powerSpectrum[k];
                }
                // Log compression with epsilon for numerical stability
                melSpectrogram[mel][frame] = (float) Math.log10(Math.max(sum, 1e-10));
            }
        }

        // Pad to MAX_FRAMES if needed
        if (numFrames < MAX_FRAMES) {
            for (int mel = 0; mel < numMelBins; mel++) {
                float[] padded = new float[MAX_FRAMES];
                System.arraycopy(melSpectrogram[mel], 0, padded, 0, numFrames);
                // Remaining frames are zero (log(epsilon) would be very negative, but we use 0)
                melSpectrogram[mel] = padded;
            }
        }

        // Return as [1][numMelBins][MAX_FRAMES]
        float[][][] result = new float[1][numMelBins][MAX_FRAMES];
        result[0] = melSpectrogram;

        Log.d(TAG, "Extracted features: shape [1][" + numMelBins + "][" + MAX_FRAMES + "]");
        return result;
    }

    /**
     * Create Hann window for STFT
     */
    private float[] createHannWindow(int length) {
        float[] window = new float[length];
        for (int i = 0; i < length; i++) {
            window[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / (length - 1))));
        }
        return window;
    }

    /**
     * Create Mel filterbank matrix.
     * Approximates librosa.filters.mel(sr=16000, n_fft=400, n_mels=numMelBins)
     * Uses Slaney normalization (area normalization of triangular filters)
     */
    private float[][] createMelFilterbank(int numMels, int nFft, int sampleRate) {
        int numBins = nFft / 2 + 1; // 201 frequency bins
        float[][] filterbank = new float[numMels][numBins];

        // Mel scale conversion
        float fMin = 0;
        float fMax = sampleRate / 2.0f; // Nyquist frequency = 8000 Hz

        float melMin = hzToMel(fMin);
        float melMax = hzToMel(fMax);

        // Create evenly spaced mel points
        float[] melPoints = new float[numMels + 2];
        for (int i = 0; i < numMels + 2; i++) {
            melPoints[i] = melMin + (melMax - melMin) * i / (numMels + 1);
        }

        // Convert mel points back to Hz
        float[] hzPoints = new float[numMels + 2];
        for (int i = 0; i < numMels + 2; i++) {
            hzPoints[i] = melToHz(melPoints[i]);
        }

        // Convert Hz to FFT bin indices
        float[] binPoints = new float[numMels + 2];
        for (int i = 0; i < numMels + 2; i++) {
            binPoints[i] = hzPoints[i] * nFft / sampleRate;
        }

        // Create triangular filters
        for (int mel = 0; mel < numMels; mel++) {
            float leftBin = binPoints[mel];
            float centerBin = binPoints[mel + 1];
            float rightBin = binPoints[mel + 2];

            for (int bin = 0; bin < numBins; bin++) {
                if (bin >= leftBin && bin <= centerBin) {
                    // Rising edge
                    filterbank[mel][bin] = (bin - leftBin) / (centerBin - leftBin);
                } else if (bin > centerBin && bin <= rightBin) {
                    // Falling edge
                    filterbank[mel][bin] = (rightBin - bin) / (rightBin - centerBin);
                }
            }

            // Slaney normalization: normalize by filter bandwidth
            float bandwidth = hzPoints[mel + 2] - hzPoints[mel];
            if (bandwidth > 0) {
                for (int bin = 0; bin < numBins; bin++) {
                    filterbank[mel][bin] *= 2.0f / bandwidth;
                }
            }
        }

        return filterbank;
    }

    /**
     * Convert frequency in Hz to Mel scale
     */
    private float hzToMel(float hz) {
        return 2595.0f * (float) Math.log10(1.0f + hz / 700.0f);
    }

    /**
     * Convert Mel scale to frequency in Hz
     */
    private float melToHz(float mel) {
        return 700.0f * ((float) Math.pow(10.0, mel / 2595.0f) - 1.0f);
    }

    /**
     * Get a flat float array suitable for ONNX tensor creation.
     * 
     * @param features The 3D feature array [1][numMelBins][numFrames]
     * @return Flattened 1D array
     */
    public float[] flatten(float[][][] features) {
        int total = features[0].length * features[0][0].length;
        float[] flat = new float[total];
        int idx = 0;
        for (int mel = 0; mel < features[0].length; mel++) {
            for (int frame = 0; frame < features[0][0].length; frame++) {
                flat[idx++] = features[0][mel][frame];
            }
        }
        return flat;
    }
}
