package com.devilved.tonetunner;

import com.devilved.tonetunner.voice_translation.neural_networks.voice.Utils;
import org.junit.Test;
import static org.junit.Assert.*;

public class UtilsSoftmaxTest {

    @Test
    public void testSoftmaxCorrectness() {
        float[] neuronValues = new float[]{1.0f, 2.0f, 3.0f};
        float input = 3.0f;
        double expectedTotal = Math.exp(1.0 - 3.0) + Math.exp(2.0 - 3.0) + Math.exp(3.0 - 3.0);
        double expected = Math.exp(3.0 - 3.0) / expectedTotal;

        double result = Utils.softmax(input, neuronValues);
        assertEquals(expected, result, 1e-6);
    }

    @Test
    public void testSoftmaxNumericalStabilityWithLargeValues() {
        float[] neuronValues = new float[]{800.0f, 801.0f, 802.0f};
        float input = 802.0f;

        double result = Utils.softmax(input, neuronValues);
        assertFalse("Result should not be NaN", Double.isNaN(result));
        assertTrue("Result should be greater than 0", result > 0);
        assertTrue("Result should be less than or equal to 1.0", result <= 1.0);
    }

    @Test
    public void testSoftmaxNullOrEmpty() {
        assertEquals(0.0, Utils.softmax(1.0f, null), 1e-6);
        assertEquals(0.0, Utils.softmax(1.0f, new float[]{}), 1e-6);
    }
}
