package com.devilved.tonetunner.asr;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class TextWindowsTest {
    @Test public void shortDictationIsNotSplit() {
        assertEquals(java.util.Collections.singletonList("Keep my words."), TextWindows.split("Keep my words.", 30));
    }
    @Test public void longTextKeepsEveryCharacterInOrder() {
        String text = "A sentence. Another sentence follows. Names, numbers 123 and punctuation!";
        List<String> pieces = TextWindows.split(text, 24);
        StringBuilder reconstructed = new StringBuilder();
        for (String piece : pieces) { assertTrue(piece.length() <= 24); reconstructed.append(piece); }
        assertEquals(text, reconstructed.toString());
    }
    @Test public void splitDoesNotCutASurrogatePair() {
        String text = "abc\uD83D\uDE00def\uD83D\uDE00ghi";
        StringBuilder reconstructed = new StringBuilder();
        for (String piece : TextWindows.split(text, 4)) {
            assertFalse(Character.isHighSurrogate(piece.charAt(piece.length() - 1)));
            assertFalse(Character.isLowSurrogate(piece.charAt(0)));
            reconstructed.append(piece);
        }
        assertEquals(text, reconstructed.toString());
    }
    @Test public void noWhitespaceScriptsStillMakeBoundedProgress() {
        String text = "这是一段没有空格的连续文本这是一段没有空格的连续文本";
        assertEquals(text, String.join("", TextWindows.split(text, 7)));
        assertThrows(IllegalArgumentException.class, () -> TextWindows.split(text, 1));
    }
}
