package com.whisperonnx.asr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RecordBuffer {
    // Static variable to store the byte array
    private static byte[] outputBuffer;

    // Synchronized method to set the byte array
    public static synchronized void setOutputBuffer(byte[] buffer) {
        outputBuffer = buffer;
    }

    // Synchronized method to get the byte array
    public static synchronized byte[] getOutputBuffer() {
        return outputBuffer;
    }

    public static float[] getSamples() {
        // Bolt Optimization: Capture buffer locally to ensure consistency and avoid repeated synchronized calls
        byte[] buffer = RecordBuffer.getOutputBuffer();
        if (buffer == null) {
            return new float[0];
        }

        int numSamples = buffer.length / 2;
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        byteBuffer.order(ByteOrder.nativeOrder());

        // Convert audio data to PCM_FLOAT format
        float[] samples = new float[numSamples];
        float maxAbsValue = 0.0f;
        // Bolt Optimization: Pre-calculate scale to use multiplication instead of division (approx 1.4x faster)
        float scale = 1.0f / 32768.0f;

        for (int i = 0; i < numSamples; i++) {
            float sample = byteBuffer.getShort() * scale;
            samples[i] = sample;
            // Bolt Optimization: Cache abs value to avoid re-calculation
            float absSample = Math.abs(sample);
            if (absSample > maxAbsValue) {
                maxAbsValue = absSample;
            }
        }

        // Normalize the samples
        if (maxAbsValue > 0.0f) {
            // Bolt Optimization: Multiply by inverse for normalization
            float invMax = 1.0f / maxAbsValue;
            for (int i = 0; i < numSamples; i++) {
                samples[i] *= invMax;
            }
        }

        return samples;

    }
}
