package com.devilved.tonetunner.asr;

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

    // Transfer ownership to one operation and stop retaining raw audio globally.
    public static synchronized byte[] takeOutputBuffer() {
        byte[] result = outputBuffer;
        outputBuffer = null;
        return result;
    }

    public static float[] getSamples() {
        return samples(getOutputBuffer());
    }

    public static float[] samples(byte[] pcm) {
        if (pcm == null) return new float[0];
        int numSamples = pcm.length / 2;
        ByteBuffer byteBuffer = ByteBuffer.wrap(pcm);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

        // Convert audio data to PCM_FLOAT format
        float[] samples = new float[numSamples];

        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) (byteBuffer.getShort() / 32768.0);
        }

        return samples;

    }
}
