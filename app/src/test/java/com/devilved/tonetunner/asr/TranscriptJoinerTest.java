package com.devilved.tonetunner.asr;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranscriptJoinerTest {
    @Test public void latinWordsNeedASeparator() { assertEquals("hello world", join("hello", "world")); }
    @Test public void cjkWindowsDoNotInventSpaces() { assertEquals("你好世界", join("你好", "世界")); }
    @Test public void punctuationAttachesToPreviousWord() { assertEquals("hello, world", join("hello", ", world")); }
    @Test public void repeatedWordsArePreserved() { assertEquals("very very good", join("very", "very good")); }
    private static String join(String left, String right) {
        StringBuilder result = new StringBuilder(left);
        TranscriptJoiner.append(result, right);
        return result.toString();
    }
}
