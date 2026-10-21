package com.devilved.tonetunner.metrics;

import java.text.BreakIterator;
import java.util.Locale;

/** Counts words like the reference app's word tokenizer: word segments containing a letter or digit. */
public final class Words {
    private Words() {}

    public static int count(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        BreakIterator words = BreakIterator.getWordInstance(Locale.ROOT);
        words.setText(text);
        int count = 0;
        int start = words.first();
        for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            for (int i = start; i < end; i = text.offsetByCodePoints(i, 1)) {
                if (Character.isLetterOrDigit(text.codePointAt(i))) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** The reference app's token estimate for text without reported usage: about four characters per token. */
    public static int estimatedTokens(int characters) {
        return characters <= 0 ? 0 : Math.max(1, (characters + 3) / 4);
    }
}
