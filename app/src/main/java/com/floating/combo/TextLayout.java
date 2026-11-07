package com.floating.combo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class TextLayout {

    public static final class Node {
        public final String text;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;
        public final boolean body;
        public int charStart;

        public Node(String text, int left, int top, int right, int bottom) {
            this(text, left, top, right, bottom, true, 0);
        }

        public Node(String text, int left, int top, int right, int bottom,
                    boolean body, int charStart) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.body = body;
            this.charStart = charStart;
        }
    }

    private static final class Band {
        final List<Node> items = new ArrayList<Node>();
        int top;
        int bottom;

        Band(Node n) {
            items.add(n);
            top = n.top;
            bottom = n.bottom;
        }

        void add(Node n) {
            items.add(n);
            if (n.top < top) {
                top = n.top;
            }
            if (n.bottom > bottom) {
                bottom = n.bottom;
            }
        }
    }

    private static final Comparator<Node> TOP_LEFT = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            if (a.top != b.top) {
                return a.top < b.top ? -1 : 1;
            }
            if (a.left != b.left) {
                return a.left < b.left ? -1 : 1;
            }
            return 0;
        }
    };

    private static final Comparator<Node> LEFT = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            if (a.left != b.left) {
                return a.left < b.left ? -1 : 1;
            }
            return 0;
        }
    };

    private TextLayout() {
    }

    public static boolean sameRow(Node a, Node b) {
        int top = Math.max(a.top, b.top);
        int bottom = Math.min(a.bottom, b.bottom);
        int overlap = bottom - top;
        if (overlap <= 0) {
            return false;
        }
        int ha = a.bottom - a.top;
        int hb = b.bottom - b.top;
        int minH = Math.min(ha, hb);
        if (minH <= 0) {
            return false;
        }
        return overlap * 2 >= minH;
    }

    public static void sortReadingOrder(List<Node> list) {
        if (list == null || list.size() < 2) {
            return;
        }
        Collections.sort(list, TOP_LEFT);

        List<Band> bands = new ArrayList<Band>();
        for (int i = 0; i < list.size(); i++) {
            Node n = list.get(i);
            Band last = bands.isEmpty() ? null : bands.get(bands.size() - 1);
            if (last != null && bandHits(last, n)) {
                last.add(n);
            } else {
                bands.add(new Band(n));
            }
        }

        list.clear();
        for (int i = 0; i < bands.size(); i++) {
            Band b = bands.get(i);
            Collections.sort(b.items, LEFT);
            list.addAll(b.items);
        }
    }

    private static boolean bandHits(Band b, Node n) {
        int top = Math.max(b.top, n.top);
        int bottom = Math.min(b.bottom, n.bottom);
        int overlap = bottom - top;
        if (overlap <= 0) {
            return false;
        }
        int hb = b.bottom - b.top;
        int hn = n.bottom - n.top;
        int minH = Math.min(hb, hn);
        if (minH <= 0) {
            return false;
        }
        return overlap * 2 >= minH;
    }

    public static int leadingWhitespace(String s) {
        if (s == null) {
            return 0;
        }
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    public static int trimmedLength(String s) {
        if (s == null) {
            return 0;
        }
        int a = 0;
        int b = s.length();
        while (a < b && Character.isWhitespace(s.charAt(a))) {
            a++;
        }
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) {
            b--;
        }
        return b - a;
    }

    public static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() == 0 ? null : t;
    }

    private static final int SENTENCE_LOOKBACK = 160;

    public static int sentenceStart(String text, int offset) {
        if (text == null) {
            return 0;
        }
        if (offset <= 0) {
            return 0;
        }
        if (offset > text.length()) {
            offset = text.length();
        }
        int floor = Math.max(0, offset - SENTENCE_LOOKBACK);
        int s = scanBack(text, offset, "。！？；\n.!?", floor);
        if (s > 0) {
            return s;
        }
        s = scanBack(text, offset, "，、：,:", floor);
        if (s > 0) {
            return s;
        }
        return offset;
    }

    public static int scanBack(String text, int offset, String puncts) {
        return scanBack(text, offset, puncts, 0);
    }

    public static int scanBack(String text, int offset, String puncts, int floor) {
        if (text == null || puncts == null) {
            return 0;
        }
        if (offset > text.length()) {
            offset = text.length();
        }
        if (floor < 0) {
            floor = 0;
        }
        for (int i = offset - 1; i >= floor; i--) {
            if (puncts.indexOf(text.charAt(i)) < 0) {
                continue;
            }
            int s = i + 1;
            while (s < text.length() && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            if (s >= text.length()) {
                return Math.max(0, offset - 1);
            }
            return s;
        }
        return 0;
    }
}
