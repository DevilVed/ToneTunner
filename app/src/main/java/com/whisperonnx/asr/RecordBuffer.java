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
        byte[] buffer = getOutputBuffer();
        if (buffer == null) {
            return new float[0];
        }

        int numSamples = buffer.length / 2;
        float[] samples = new float[numSamples];
        if (numSamples == 0) {
            return samples;
        }

        // Bolt Optimization: Direct byte unpacking avoiding ByteBuffer object allocations and float operations in 1st pass
        boolean isLittleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        int maxShort = 0;

        if (isLittleEndian) {
            for (int i = 0, idx = 0; i < numSamples; i++, idx += 2) {
                int s = (short) ((buffer[idx] & 0xFF) | ((buffer[idx + 1] & 0xFF) << 8));
                int absS = s < 0 ? -s : s;
                if (absS > maxShort) {
                    maxShort = absS;
                }
            }
            if (maxShort > 0) {
                float invMax = 1.0f / maxShort;
                for (int i = 0, idx = 0; i < numSamples; i++, idx += 2) {
                    int s = (short) ((buffer[idx] & 0xFF) | ((buffer[idx + 1] & 0xFF) << 8));
                    samples[i] = s * invMax;
                }
            }
        } else {
            for (int i = 0, idx = 0; i < numSamples; i++, idx += 2) {
                int s = (short) (((buffer[idx] & 0xFF) << 8) | (buffer[idx + 1] & 0xFF));
                int absS = s < 0 ? -s : s;
                if (absS > maxShort) {
                    maxShort = absS;
                }
            }
            if (maxShort > 0) {
                float invMax = 1.0f / maxShort;
                for (int i = 0, idx = 0; i < numSamples; i++, idx += 2) {
                    int s = (short) (((buffer[idx] & 0xFF) << 8) | (buffer[idx + 1] & 0xFF));
                    samples[i] = s * invMax;
                }
            }
        }

        return samples;
    }
}
