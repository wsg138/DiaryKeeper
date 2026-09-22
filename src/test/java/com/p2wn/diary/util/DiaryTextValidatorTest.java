package com.p2wn.diary.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DiaryTextValidatorTest {
    @Test
    void nullEmptyPrintableAsciiAndBookWhitespaceAreAllowed() {
        assertTrue(DiaryTextValidator.isAsciiOnly(null));
        assertTrue(DiaryTextValidator.isAsciiOnly(""));
        assertTrue(DiaryTextValidator.isAsciiOnly("Diary #42: Hello, world! ~{}[]"));
        assertTrue(DiaryTextValidator.isAsciiOnly("line one\nline two\r\n\tindented"));
        assertTrue(DiaryTextValidator.isAllowedBookText("plain ASCII\nnext page text"));
    }

    @Test
    void disallowedControlCharactersAreRejected() {
        assertFalse(DiaryTextValidator.isAsciiOnly("before\u0000after"));
        assertFalse(DiaryTextValidator.isAsciiOnly("before\bafter"));
        assertFalse(DiaryTextValidator.isAllowedBookText("before\u001Fafter"));
    }

    @Test
    void nonAsciiCharactersAreRejectedIncludingSurrogatePairs() {
        assertFalse(DiaryTextValidator.isAsciiOnly("caf\u00E9"));
        assertFalse(DiaryTextValidator.isAsciiOnly("smart \u201Cquote\u201D"));
        assertFalse(DiaryTextValidator.isAllowedBookText("emoji \uD83D\uDCD6"));
    }
}
