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
        assertEquals("She said 'hello'", join("She said", "'hello'"));
        assertEquals("She said 'hello", join("She said", "'hello"));
        assertEquals("I'm going", join("I", "'m going"));
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

    @Test public void enhancedWindowsRestoreParagraphBreaksFromOriginalTextWhenModelTrimsThem() {
        String original = "First paragraph.\n\nSecond paragraph.";
        java.util.List<String> pieces = TextWindows.split(original, 18);
        assertEquals(2, pieces.size());

        StringBuilder result = new StringBuilder();
        TranscriptJoiner.appendEnhanced(result, pieces.get(0), "First paragraph.");
        TranscriptJoiner.appendEnhanced(result, pieces.get(1), "Second paragraph.");

        assertEquals(original, result.toString());
    }

    @Test public void enhancedWindowsRestoreLeadingParagraphBreaksFromTheirOriginalPiece() {
        StringBuilder result = new StringBuilder();
        TranscriptJoiner.appendEnhanced(result, "First paragraph.", "First paragraph.");
        TranscriptJoiner.appendEnhanced(result, "\n\nSecond paragraph.", "Second paragraph.");

        assertEquals("First paragraph.\n\nSecond paragraph.", result.toString());
    }

    @Test public void overlappingAudioWindowsRemoveOnlySufficientBoundaryOverlap() {
        StringBuilder result = new StringBuilder("We crossed the blue bridge");
        TranscriptJoiner.appendOverlapping(result,
                "the blue bridge, then we crossed the blue bridge again");

        assertEquals("We crossed the blue bridge, then we crossed the blue bridge again", result.toString());
    }

    @Test public void overlappingAudioWindowsRemoveTwoWordBoundaryMatchesOfAtLeastEightCharacters() {
        StringBuilder result = new StringBuilder("First words at the boundary");
        TranscriptJoiner.appendOverlapping(result, "at the boundary then more");

        assertEquals("First words at the boundary then more", result.toString());
    }

    @Test public void overlappingAudioWindowsKeepShortTwoWordMatches() {
        StringBuilder result = new StringBuilder("We are");
        TranscriptJoiner.appendOverlapping(result, "we are ready");

        assertEquals("We are we are ready", result.toString());
    }

    @Test public void overlappingAudioWindowsPreserveARepeatedSingleWord() {
        StringBuilder result = new StringBuilder("I said very");
        TranscriptJoiner.appendOverlapping(result, "very clearly");

        assertEquals("I said very very clearly", result.toString());
    }

    @Test public void overlappingAudioWindowsCompleteAConfidentPartialWord() {
        StringBuilder result = new StringBuilder("The result is transcrip");
        TranscriptJoiner.appendOverlapping(result, "transcription finished today");

        assertEquals("The result is transcription finished today", result.toString());
    }

    @Test public void overlappingAudioWindowsCompleteAPartialWordAfterAConfidentPhraseMatch() {
        StringBuilder result = new StringBuilder("This is micro");
        TranscriptJoiner.appendOverlapping(result, "is microphone testing");

        assertEquals("This is microphone testing", result.toString());
    }

    @Test public void overlapMatchingUsesOnlyTheBoundedTailAndReplacesAtItsAbsoluteOffset() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            result.append("filler ");
        }
        String prefix = result.toString();
        result.append("This is micro");

        TranscriptJoiner.appendOverlapping(result, "is microphone testing");

        assertEquals(prefix + "This is microphone testing", result.toString());
    }

    private static String join(String left, String right) {
        StringBuilder result = new StringBuilder(left);
        TranscriptJoiner.append(result, right);
        return result.toString();
    }
}
