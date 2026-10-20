package com.devilved.tonetunner.asr;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranscriptJoinerTest {
    @Test public void latinWordsNeedASeparator() { assertEquals("hello world", join("hello", "world")); }
    @Test public void cjkWindowsDoNotInventSpaces() { assertEquals("你好世界", join("你好", "世界")); }
    @Test public void punctuationAttachesToPreviousWord() { assertEquals("hello, world", join("hello", ", world")); }
    @Test public void repeatedWordsArePreserved() { assertEquals("very very good", join("very", "very good")); }

    @Test public void openingSpanishPunctuationIsSeparatedFromThePreviousWindow() {
        assertEquals("He asked ¿dónde?", join("He asked", "¿dónde?"));
        assertEquals("Then ¡corre!", join("Then", "¡corre!"));
    }

    @Test public void openingQuotesAreSeparatedWhileClosingQuotesAttach() {
        assertEquals("She said \"hello\"", join("She said", "\"hello\""));
        assertEquals("hello\"", join("hello", "\""));
    }

    @Test public void symbolLedTokensAreSeparatedFromThePreviousWindow() {
        assertEquals("word #tag", join("word", "#tag"));
        assertEquals("word @mention", join("word", "@mention"));
        assertEquals("cats & dogs", join("cats", "& dogs"));
        assertEquals("word *emphasis*", join("word", "*emphasis*"));
        assertEquals("discount % off", join("discount", "% off"));
    }

    @Test public void enhancementParagraphBoundariesSurviveWindowJoining() {
        StringBuilder result = new StringBuilder();
        TranscriptJoiner.append(result, "First paragraph.\n\n");
        TranscriptJoiner.append(result, "Second paragraph.");

        assertEquals("First paragraph.\n\nSecond paragraph.", result.toString());
    }

    private static String join(String left, String right) {
        StringBuilder result = new StringBuilder(left);
        TranscriptJoiner.append(result, right);
        return result.toString();
    }
}
