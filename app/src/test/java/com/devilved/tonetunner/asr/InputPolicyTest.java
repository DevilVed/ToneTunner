package com.devilved.tonetunner.asr;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class InputPolicyTest {
    @Test public void normalTextCanUseCloud() {
        assertTrue(InputPolicy.allowsDictation(InputType.TYPE_CLASS_TEXT, 0, true));
    }
    @Test public void allPasswordVariationsRejectLocalAndCloud() {
        int[] inputs = {
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        };
        for (int input : inputs) {
            assertFalse(InputPolicy.allowsDictation(input, 0, false));
            assertFalse(InputPolicy.allowsDictation(input, 0, true));
        }
    }
    @Test public void noPersonalizedLearningAllowsOnlyEntirelyLocalProcessing() {
        assertFalse(InputPolicy.allowsDictation(InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, true));
        assertTrue(InputPolicy.allowsDictation(InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false));
    }
    @Test public void nullEditorIsNotAnInputDestination() {
        assertFalse(InputPolicy.allowsDictation(InputType.TYPE_NULL, 0, false));
    }
    @Test public void emailAndMultilineAreNotMistakenForPasswords() {
        assertTrue(InputPolicy.allowsDictation(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0, true));
    }
}
