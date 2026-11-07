package com.floating.combo;

public final class TextCompare {
    private TextCompare() {
    }

    public static boolean sameLine(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equals(b.trim());
    }
}
