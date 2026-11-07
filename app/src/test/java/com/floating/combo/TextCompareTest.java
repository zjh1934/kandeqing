package com.floating.combo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextCompareTest {

    @Test
    public void equalLinesAreSame() {
        assertTrue(TextCompare.sameLine("你好世界", "你好世界"));
        assertTrue(TextCompare.sameLine("  你好世界  ", "你好世界"));
    }

    @Test
    public void subStringMustNotBeTreatedAsDuplicate() {
        assertFalse(TextCompare.sameLine("你好世界", "世界"));
        assertFalse(TextCompare.sameLine("世界", "你好世界"));
        assertFalse(TextCompare.sameLine("你好世界", "你好地球"));
        assertFalse(TextCompare.sameLine("一段长文本", "文本"));
    }

    @Test
    public void nullSafe() {
        assertFalse(TextCompare.sameLine(null, "x"));
        assertFalse(TextCompare.sameLine("x", null));
        assertFalse(TextCompare.sameLine(null, null));
    }
}
