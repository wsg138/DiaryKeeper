package com.p2wn.diary.util;

public final class DiaryTextValidator {

    private DiaryTextValidator() {
    }

    public static boolean isAsciiOnly(String text) {
        if (text == null) {
            return true;
        }

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                continue;
            }
            if (ch < 0x20 || ch > 0x7E) {
                return false;
            }
        }

        return true;
    }
}
