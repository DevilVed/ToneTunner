package com.devilved.tonetunner.asr;

import java.util.ArrayList;
import java.util.List;

/** Bounds enhancement inputs, preferring sentence/whitespace boundaries without losing text. */
public final class TextWindows {
    private TextWindows() {}
    public static List<String> split(String text, int maxCharacters) {
        if (maxCharacters < 2) throw new IllegalArgumentException("Window must hold a Unicode code point");
        List<String> pieces = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int end = Math.min(text.length(), offset + maxCharacters);
            if (end < text.length()) {
                int floor = offset + maxCharacters / 2;
                int boundary = -1;
                for (int index = end - 1; index >= floor; --index) {
                    char c = text.charAt(index);
                    if (c == '\n' || c == '.' || c == '!' || c == '?' || c == '\u3002') {
                        boundary = index + 1; break;
                    }
                }
                if (boundary < 0) {
                    for (int index = end - 1; index >= floor; --index) {
                        if (Character.isWhitespace(text.charAt(index))) { boundary = index + 1; break; }
                    }
                }
                if (boundary > offset) end = boundary;
                if (Character.isHighSurrogate(text.charAt(end - 1))
                        && Character.isLowSurrogate(text.charAt(end))) --end;
            }
            pieces.add(text.substring(offset, end));
            offset = end;
        }
        return pieces;
    }
}
