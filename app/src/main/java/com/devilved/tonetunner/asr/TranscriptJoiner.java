package com.devilved.tonetunner.asr;

/** Joins disjoint windows without inventing spaces in scripts that do not normally use them. */
final class TranscriptJoiner {
    private TranscriptJoiner() {}
    static void append(StringBuilder result, String part) {
        if (part == null || part.trim().isEmpty()) return;
        String next = part.trim();
        if (result.length() > 0) {
            int left = result.codePointBefore(result.length());
            int right = next.codePointAt(0);
            int type = Character.getType(right);
            boolean punctuation = type == Character.END_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION;
            if (!Character.isWhitespace(left) && !punctuation && !(unspaced(left) && unspaced(right)))
                result.append(' ');
        }
        result.append(next);
    }
    private static boolean unspaced(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.THAI;
    }
}
