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
        // Optimization: Local reference to avoid synchronized overhead and race conditions
        byte[] buffer = getOutputBuffer();
        if (buffer == null) return new float[0];

        int numSamples = buffer.length / 2;
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        byteBuffer.order(ByteOrder.nativeOrder());

        // Convert audio data to PCM_FLOAT format
        float[] samples = new float[numSamples];
        float maxAbsValue = 0.0f;

        // Optimization: Pre-compute inverse constant
        final float INV_SHORT_MAX = 1.0f / 32768.0f;

        for (int i = 0; i < numSamples; i++) {
            // Optimization: Multiplication instead of division
            float sample = byteBuffer.getShort() * INV_SHORT_MAX;
            samples[i] = sample;

            // Optimization: Cache Math.abs result
            float absSample = Math.abs(sample);
            if (absSample > maxAbsValue) {
                maxAbsValue = absSample;
            }
        }

        // Normalize the samples
        if (maxAbsValue > 0.0f) {
            // Optimization: Inverse multiplication for normalization
            float invMaxAbsValue = 1.0f / maxAbsValue;
            for (int i = 0; i < numSamples; i++) {
                samples[i] *= invMaxAbsValue;
            }
        }

        return samples;

    }
}
