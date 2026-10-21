package com.devilved.tonetunner.asr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Joins transcript windows while preserving their punctuation and source paragraph breaks. */
final class TranscriptJoiner {
    private static final int MIN_OVERLAP_WORDS = 2;
    private static final int MIN_OVERLAP_CHARACTERS = 8;
    private static final int MAX_OVERLAP_CHARACTERS = 256;
    private static final int MIN_UNSPACED_OVERLAP_LETTERS = 2;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");

    private TranscriptJoiner() {}

    static void append(StringBuilder result, String part) {
        if (part == null || part.trim().isEmpty()) {
            return;
        }
        appendPrepared(result, part.trim(), edgeLineBreaks(part, true), edgeLineBreaks(part, false));
    }

    /**
     * Appends enhanced text and restores line breaks present at the source window's edges. Enhancement
     * providers often trim their output, so those source boundaries are the reliable paragraph signal.
     */
    static void appendEnhanced(StringBuilder result, String originalPiece, String enhancedPiece) {
        if (enhancedPiece == null || enhancedPiece.trim().isEmpty()) {
            return;
        }
        String leadingBreaks = edgeLineBreaks(originalPiece, true);
        String trailingBreaks = edgeLineBreaks(originalPiece, false);
        if (leadingBreaks.isEmpty()) {
            leadingBreaks = edgeLineBreaks(enhancedPiece, true);
        }
        if (trailingBreaks.isEmpty()) {
            trailingBreaks = edgeLineBreaks(enhancedPiece, false);
        }
        appendPrepared(result, enhancedPiece.trim(), leadingBreaks, trailingBreaks);
    }

    /** Removes a sufficiently strong repeated suffix/prefix caused by overlapping audio windows. */
    static void appendOverlapping(StringBuilder result, String part) {
        if (part == null || part.trim().isEmpty()) {
            return;
        }
        String next = part.trim();
        if (result.length() == 0) {
            append(result, part);
            return;
        }

        int previousContentEnd = trailingClosingPunctuationStart(result);
        if (previousContentEnd == 0) {
            append(result, part);
            return;
        }
        int previousLastCodePoint = Character.codePointBefore(result, previousContentEnd);
        int nextFirstCodePoint = next.codePointAt(0);
        if (unspaced(previousLastCodePoint) && unspaced(nextFirstCodePoint)) {
            int consumed = unspacedOverlapEnd(result, previousContentEnd, next);
            if (consumed > 0) {
                // Exact repeated phrases of two or more script letters at a hard cut are
                // inherently ambiguous with audio-window overlap.
                consumed = consumeDuplicatePunctuation(
                        result.toString(), previousContentEnd, next, consumed);
                appendUnspacedOverlapRemainder(result, next.substring(consumed));
                return;
            }
        }

        int previousOffset = boundedTailStart(result, MAX_OVERLAP_CHARACTERS);
        String previous = result.substring(previousOffset);
        int nextScanEnd = boundedPrefixEnd(next, MAX_OVERLAP_CHARACTERS);
        String nextPrefix = next.substring(0, nextScanEnd);
        List<Word> previousWords = words(previous);
        List<Word> nextWords = words(nextPrefix);
        if (previousOffset > 0 && startsInsideWord(result, previousOffset)
                && !previousWords.isEmpty() && previousWords.get(0).start == 0) {
            previousWords.remove(0);
        }
        if (nextScanEnd < next.length() && endsInsideWord(next, nextScanEnd)
                && !nextWords.isEmpty() && nextWords.get(nextWords.size() - 1).end == nextScanEnd) {
            nextWords.remove(nextWords.size() - 1);
        }
        if (previousWords.isEmpty() || nextWords.isEmpty() || nextWords.get(0).start != 0) {
            append(result, part);
            return;
        }

        Word previousLast = previousWords.get(previousWords.size() - 1);
        Word nextFirst = nextWords.get(0);
        if (!onlyBoundaryCharacters(previous, previousLast.end, previous.length(), true)) {
            append(result, part);
            return;
        }

        if (completePartialPhrase(result, previousOffset, previous, previousWords,
                next, nextWords)) {
            return;
        }

        int overlapWordCount = findWordOverlap(previous, previousWords, nextWords);
        if (overlapWordCount >= MIN_OVERLAP_WORDS) {
            int consumed = nextWords.get(overlapWordCount - 1).end;
            consumed = consumeDuplicatePunctuation(previous, previousLast.end, next, consumed);
            append(result, next.substring(consumed));
            return;
        }

        if (completePartialWord(result, previousOffset, previous, previousLast, next, nextFirst)) {
            return;
        }
        append(result, part);
    }

    /** Matches a bounded code-point suffix/prefix for scripts without whitespace word breaks. */
    private static int unspacedOverlapEnd(CharSequence previous, int previousEnd, String next) {
        int previousStart = boundedTailStart(previous, MAX_OVERLAP_CHARACTERS, previousEnd);
        int previousCodePoints = Character.codePointCount(previous, previousStart, previousEnd);
        int nextEnd = boundedPrefixEnd(next, MAX_OVERLAP_CHARACTERS);
        int nextCodePoints = next.codePointCount(0, nextEnd);
        int maximum = Math.min(previousCodePoints, nextCodePoints);
        for (int count = maximum; count > 0; count--) {
            int leftStart = Character.offsetByCodePoints(previous, previousEnd, -count);
            int rightEnd = next.offsetByCodePoints(0, count);
            if (countUnspacedLetters(previous, leftStart, previousEnd)
                    >= MIN_UNSPACED_OVERLAP_LETTERS
                    && sameCodePoints(previous, leftStart, previousEnd, next, 0, rightEnd)) {
                return rightEnd;
            }
        }
        return 0;
    }

    private static int trailingClosingPunctuationStart(CharSequence text) {
        int end = text.length();
        while (end > 0) {
            int codePoint = Character.codePointBefore(text, end);
            if (!isClosingPunctuation(codePoint, 0, "")) break;
            end -= Character.charCount(codePoint);
        }
        return end;
    }

    private static int countUnspacedLetters(CharSequence text, int start, int end) {
        int count = 0;
        for (int offset = start; offset < end;) {
            int codePoint = Character.codePointAt(text, offset);
            if (unspaced(codePoint) && Character.isLetter(codePoint)) count++;
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    private static void appendUnspacedOverlapRemainder(StringBuilder result, String remainder) {
        if (remainder.isEmpty()) return;
        int right = remainder.codePointAt(0);
        if (Character.isWhitespace(right) || unspaced(right)) {
            result.append(remainder);
            return;
        }
        if (result.length() > 0) {
            int left = result.codePointBefore(result.length());
            if (needsSpace(left, right, remainder, result)) result.append(' ');
        }
        result.append(remainder);
    }

    private static boolean sameCodePoints(CharSequence left, int leftStart, int leftEnd,
                                          String right, int rightStart, int rightEnd) {
        int leftOffset = leftStart;
        int rightOffset = rightStart;
        while (leftOffset < leftEnd && rightOffset < rightEnd) {
            int leftCodePoint = Character.toLowerCase(Character.codePointAt(left, leftOffset));
            int rightCodePoint = Character.toLowerCase(right.codePointAt(rightOffset));
            if (leftCodePoint != rightCodePoint) return false;
            leftOffset += Character.charCount(Character.codePointAt(left, leftOffset));
            rightOffset += Character.charCount(right.codePointAt(rightOffset));
        }
        return leftOffset == leftEnd && rightOffset == rightEnd;
    }

    // The overlap always starts at the next window's first word, so only non-word characters can
    // precede it there; the previous side is checked for trailing text below.
    private static int findWordOverlap(String previous, List<Word> previousWords, List<Word> nextWords) {
        int maximum = Math.min(previousWords.size(), nextWords.size());
        for (int count = maximum; count >= MIN_OVERLAP_WORDS; count--) {
            int previousStartIndex = previousWords.size() - count;
            Word first = previousWords.get(previousStartIndex);
            Word last = previousWords.get(previousWords.size() - 1);
            int overlapLength = previous.codePointCount(first.start, last.end);
            if (overlapLength < MIN_OVERLAP_CHARACTERS || overlapLength > MAX_OVERLAP_CHARACTERS) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < count; i++) {
                String left = previousWords.get(previousStartIndex + i).normalized;
                String right = nextWords.get(i).normalized;
                if (!left.equals(right)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            if (!onlyBoundaryCharacters(previous, last.end, previous.length(), true)) {
                continue;
            }
            return count;
        }
        return 0;
    }

    private static boolean completePartialPhrase(StringBuilder result, int previousOffset,
                                                 String previous, List<Word> previousWords,
                                                 String next, List<Word> nextWords) {
        int maximum = Math.min(previousWords.size(), nextWords.size());
        for (int count = maximum; count >= MIN_OVERLAP_WORDS; count--) {
            int previousStartIndex = previousWords.size() - count;
            Word firstPrevious = previousWords.get(previousStartIndex);
            Word lastPrevious = previousWords.get(previousWords.size() - 1);
            Word lastNext = nextWords.get(count - 1);
            int prefixLength = previous.codePointCount(firstPrevious.start, lastPrevious.end);
            String partial = previous.substring(lastPrevious.start, lastPrevious.end);
            String complete = next.substring(nextWords.get(count - 1).start, lastNext.end);
            int partialLength = partial.codePointCount(0, partial.length());
            if (prefixLength < MIN_OVERLAP_CHARACTERS || prefixLength > MAX_OVERLAP_CHARACTERS
                    || partialLength < 3
                    || complete.codePointCount(0, complete.length()) <= partialLength
                    || !complete.toLowerCase(Locale.ROOT).startsWith(partial.toLowerCase(Locale.ROOT))
                    || !onlyBoundaryCharacters(previous, lastPrevious.end, previous.length(), false)) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < count - 1; i++) {
                if (!previousWords.get(previousStartIndex + i).normalized.equals(nextWords.get(i).normalized)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }

            result.replace(previousOffset + lastPrevious.start, previousOffset + lastPrevious.end, complete);
            append(result, next.substring(lastNext.end));
            return true;
        }
        return false;
    }

    private static boolean completePartialWord(StringBuilder result, int previousOffset,
                                               String previous, Word previousLast,
                                               String next, Word nextFirst) {
        String partial = previous.substring(previousLast.start, previousLast.end);
        String complete = next.substring(nextFirst.start, nextFirst.end);
        int partialLength = partial.codePointCount(0, partial.length());
        if (partialLength < MIN_OVERLAP_CHARACTERS || partialLength > MAX_OVERLAP_CHARACTERS
                || complete.codePointCount(0, complete.length()) <= partial.codePointCount(0, partial.length())
                || !complete.toLowerCase(Locale.ROOT).startsWith(partial.toLowerCase(Locale.ROOT))
                || !onlyBoundaryCharacters(previous, previousLast.end, previous.length(), false)) {
            return false;
        }

        result.replace(previousOffset + previousLast.start,
                previousOffset + previousLast.end, complete);
        append(result, next.substring(nextFirst.end));
        return true;
    }

    private static int boundedTailStart(StringBuilder text, int maxCodePoints) {
        return boundedTailStart(text, maxCodePoints, text.length());
    }

    private static int boundedTailStart(CharSequence text, int maxCodePoints, int end) {
        int start = end;
        int count = 0;
        while (start > 0 && count < maxCodePoints) {
            start -= Character.charCount(Character.codePointBefore(text, start));
            count++;
        }
        return start;
    }

    private static int boundedPrefixEnd(String text, int maxCodePoints) {
        int end = 0;
        int count = 0;
        while (end < text.length() && count < maxCodePoints) {
            end += Character.charCount(Character.codePointAt(text, end));
            count++;
        }
        return end;
    }

    private static boolean startsInsideWord(CharSequence text, int offset) {
        return offset < text.length()
                && isWordCharacter(Character.codePointBefore(text, offset))
                && isWordCharacter(Character.codePointAt(text, offset));
    }

    private static boolean endsInsideWord(String text, int offset) {
        return offset > 0 && offset < text.length()
                && isWordCharacter(text.codePointBefore(offset))
                && isWordCharacter(text.codePointAt(offset));
    }

    private static boolean isWordCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint);
    }

    private static int consumeDuplicatePunctuation(String previous, int previousWordEnd,
                                                  String next, int nextWordEnd) {
        int left = previousWordEnd;
        int right = nextWordEnd;
        while (left < previous.length() && right < next.length()) {
            int leftCodePoint = Character.codePointAt(previous, left);
            int rightCodePoint = Character.codePointAt(next, right);
            if (leftCodePoint != rightCodePoint || !isPunctuation(leftCodePoint)) break;
            left += Character.charCount(leftCodePoint);
            right += Character.charCount(rightCodePoint);
        }
        return right;
    }

    private static List<Word> words(String text) {
        List<Word> result = new ArrayList<>();
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            result.add(new Word(matcher.start(), matcher.end(),
                    matcher.group().toLowerCase(Locale.ROOT)));
        }
        return result;
    }

    private static boolean onlyBoundaryCharacters(String value, int start, int end,
                                                  boolean punctuationAllowed) {
        for (int offset = start; offset < end;) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)
                    && !(punctuationAllowed && isPunctuation(codePoint))) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static String edgeLineBreaks(String value, boolean leading) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int start = leading ? 0 : value.length();
        int end = start;
        if (leading) {
            while (end < value.length() && Character.isWhitespace(value.charAt(end))) {
                end++;
            }
            start = 0;
        } else {
            while (start > 0 && Character.isWhitespace(value.charAt(start - 1))) {
                start--;
            }
            end = value.length();
        }
        StringBuilder breaks = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                breaks.append(c);
            }
        }
        return breaks.toString();
    }

    private static void appendPrepared(StringBuilder result, String next,
                                       String leadingBreaks, String trailingBreaks) {
        if (next.isEmpty()) {
            return;
        }
        if (!leadingBreaks.isEmpty()) {
            result.append(leadingBreaks);
        } else if (result.length() > 0) {
            int left = result.codePointBefore(result.length());
            int right = next.codePointAt(0);
            if (needsSpace(left, right, next, result) && !(unspaced(left) && unspaced(right))) {
                result.append(' ');
            }
        }
        result.append(next);
        if (!trailingBreaks.isEmpty()) {
            result.append(trailingBreaks);
        }
    }

    private static boolean needsSpace(int left, int right, String next, StringBuilder result) {
        if (Character.isWhitespace(left) || isOpeningPunctuation(left)
                || isOpeningAsciiQuoteAtEnd(left, result)) {
            return false;
        }
        if (isClosingPunctuation(right, left, next)) {
            return false;
        }
        return true;
    }

    private static boolean isOpeningPunctuation(int codePoint) {
        return codePoint == '\u00a1' || codePoint == '\u00bf'
                || Character.getType(codePoint) == Character.START_PUNCTUATION
                || Character.getType(codePoint) == Character.INITIAL_QUOTE_PUNCTUATION
                || codePoint == '#' || codePoint == '@';
    }

    private static boolean isOpeningAsciiQuoteAtEnd(int codePoint, StringBuilder result) {
        if (codePoint == '*' && result.length() > 1) {
            int beforeStar = result.codePointBefore(result.length() - 1);
            if (Character.isWhitespace(beforeStar)) {
                return true;
            }
        }
        if (codePoint != '"' && codePoint != '\'') {
            return false;
        }
        int quoteOffset = result.length() - 1;
        if (quoteOffset == 0) {
            return true;
        }
        int before = result.codePointBefore(quoteOffset);
        return Character.isWhitespace(before) || isOpeningPunctuation(before);
    }

    private static boolean isClosingPunctuation(int codePoint, int left, String next) {
        int type = Character.getType(codePoint);
        if (type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION) {
            return true;
        }
        if (codePoint == '"' || codePoint == '\'') {
            return isClosingAsciiQuote(codePoint, next, left);
        }
        if (codePoint == '%' && Character.isDigit(left)) {
            return true;
        }
        switch (codePoint) {
            case ',':
            case '.':
            case '!':
            case '?':
            case ':':
            case ';':
            case '\u2025':
            case '\u2026':
            case '\u3001':
            case '\u3002':
            case '\ufe10':
            case '\ufe11':
            case '\ufe12':
            case '\ufe13':
            case '\ufe14':
            case '\ufe15':
            case '\ufe16':
            case '\ufe19':
            case '\uff0c':
            case '\uff0e':
            case '\uff01':
            case '\uff1f':
            case '\uff1a':
            case '\uff1b':
                return true;
            default:
                return false;
        }
    }

    private static boolean isClosingAsciiQuote(int quote, String next, int left) {
        int quoteWidth = Character.charCount(quote);
        int matchingQuote = next.indexOf((char) quote, quoteWidth);
        if (matchingQuote >= quoteWidth) {
            return false;
        }
        if (quote == '\'' && Character.isLetterOrDigit(left) && isContractionTail(next, quoteWidth)) {
            return true;
        }
        return next.length() == quoteWidth;
    }

    private static boolean isContractionTail(String text, int quoteWidth) {
        int end = quoteWidth;
        while (end < text.length() && Character.isLetter(text.codePointAt(end))) {
            end += Character.charCount(text.codePointAt(end));
        }
        String suffix = text.substring(quoteWidth, end).toLowerCase(Locale.ROOT);
        if (!("s".equals(suffix) || "m".equals(suffix) || "t".equals(suffix)
                || "d".equals(suffix) || "ll".equals(suffix) || "re".equals(suffix)
                || "ve".equals(suffix))) {
            return false;
        }
        return end == text.length() || !Character.isLetterOrDigit(text.codePointAt(end));
    }

    private static boolean isPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean unspaced(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.THAI;
    }

    private static final class Word {
        final int start;
        final int end;
        final String normalized;

        Word(int start, int end, String normalized) {
            this.start = start;
            this.end = end;
            this.normalized = normalized;
        }
    }
}
