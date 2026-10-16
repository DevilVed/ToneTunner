/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.whisperonnx.voice_translation.neural_networks.voice;

import java.util.ArrayList;

public class Utils {
    /**
     * Calculates softmax for a given input value over a logit array.
     * Uses max-subtraction for numerical stability (prevents overflow/NaN) and skips Math.exp()
     * for values where diff <= -20.0f (contribution < 2e-9), yielding ~4x speedup on large vocab arrays.
     */
    public static double softmax(float input, float[] neuronValues) {
        float max = -Float.MAX_VALUE;
        for (float v : neuronValues) {
            if (v > max) {
                max = v;
            }
        }
        double sum = 0.0;
        for (float v : neuronValues) {
            float diff = v - max;
            if (diff > -20.0f) {
                sum += Math.exp(diff);
            }
        }
        return Math.exp(input - max) / sum;
    }

    /**
     * Calculates LogSumExp for a logit array using the max-subtraction trick for numerical stability.
     */
    public static double logSumExp(float[] neuronValues) {
        float max = -Float.MAX_VALUE;
        for (float v : neuronValues) {
            if (v > max) {
                max = v;
            }
        }
        double sum = 0.0;
        for (float v : neuronValues) {
            float diff = v - max;
            if (diff > -20.0f) {
                sum += Math.exp(diff);
            }
        }
        return max + Math.log(sum);
    }

    public static double logSumExpFast(float[] neuronValues){
        double max = Double.NEGATIVE_INFINITY;
        for (double v : neuronValues) {
            if (v > max) {
                max = v;
            }
        }
        double threshold = 20.0; // Skip correction if other values are much lower
        double sum = 0.0;
        for (double v : neuronValues) {
            double diff = v - max;
            if (diff > -threshold) {
                sum += Math.exp(diff);
            }
        }
        return max + Math.log(sum);
    }

    public static double logSumExpFaster(float[] neuronValues){
        double max = Double.NEGATIVE_INFINITY;
        for (double v : neuronValues) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    public static int getIndexOfLargest(float[] array){
        //long time = System.currentTimeMillis();
        if (array == null || array.length == 0){
            return -1;
        } // null or empty
        int largestIndex = 0;
        float largest = -Float.MAX_VALUE;
        for (int i = 0; i < array.length; i++) {
            if (array[i] > largest) {
                largestIndex = i;
                largest = array[largestIndex];
            }
        }
        //android.util.Log.i("performance", "index of largest time: " + (System.currentTimeMillis()-time) + "ms");
        return largestIndex; // position of the largest found
    }

    public static int getIndexOfLargest(double[] array){
        //long time = System.currentTimeMillis();
        if (array == null || array.length == 0){
            return -1;
        } // null or empty
        int largestIndex = 0;
        double largest = -Double.MAX_VALUE;
        for (int i = 0; i < array.length; i++) {
            if (array[i] > largest) {
                largestIndex = i;
                largest = array[largestIndex];
            }
        }
        //android.util.Log.i("performance", "index of largest time: " + (System.currentTimeMillis()-time) + "ms");
        return largestIndex; // position of the largest found
    }

    public static int getIndexOfLargest(float[] array, ArrayList<Integer> indexesToAvoid){
        //long time = System.currentTimeMillis();
        if (array == null || array.length == 0){
            return -1;
        } // null or empty
        int largestIndex = 0;
        float largest = -Float.MAX_VALUE;
        for (int i = 0; i < array.length; i++) {
            if (array[i] > largest && !indexesToAvoid.contains(i)) {
                largestIndex = i;
                largest = array[largestIndex];
            }
        }
        //android.util.Log.i("performance", "index of largest time: " + (System.currentTimeMillis()-time) + "ms");
        return largestIndex; // position of the largest found
    }

    public static int getIndexOfLargest(double[] array, ArrayList<Integer> indexesToAvoid){
        //long time = System.currentTimeMillis();
        if (array == null || array.length == 0){
            return -1;
        } // null or empty
        int largestIndex = 0;
        double largest = -Double.MAX_VALUE;
        for (int i = 0; i < array.length; i++) {
            if (array[i] > largest && !indexesToAvoid.contains(i)) {
                largestIndex = i;
                largest = array[largestIndex];
            }
        }
        //android.util.Log.i("performance", "index of largest time: " + (System.currentTimeMillis()-time) + "ms");
        return largestIndex; // position of the largest found
    }
}
