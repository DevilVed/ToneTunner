package com.devilved.tonetunner.asr;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/** Conservative cloud policy for the focused editor, without reading any of its text. */
public final class InputPolicy {
    private InputPolicy() {}

    public static boolean allowsDictation(int inputType, int imeOptions, boolean sendsToCloud) {
        if (inputType == InputType.TYPE_NULL) return false;
        int kind = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (kind == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return false;
        if (kind == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return false;
        return !sendsToCloud || (imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
    }
}
