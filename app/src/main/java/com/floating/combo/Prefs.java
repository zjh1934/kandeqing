package com.floating.combo;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {

    public static final int[] COLORS = new int[]{
            0xFFF4F7FA,
            0xFF6FA8CF,
            0xFF79C2AE,
            0xFFE0B873,
            0xFFE08E86,
            0xFF9C90D8,
            0xFF6E8B9B,
    };
    public static final String[] COLOR_NAMES = new String[]{
            "月白", "霁蓝", "青瓷", "麦金", "藕红", "鸢尾", "岩青"
    };

    private static final String P = "cfg";

    public static final int DEF_SIZE_DP = 56;
    public static final float DEF_ALPHA = 1.0f;
    public static final float DEF_IDLE_ALPHA = 1.0f;
    public static final boolean DEF_SNAP_EDGE = false;
    public static final int DEF_COLOR_INDEX = 0;

    public static final int READ_ONE = 0;
    public static final int READ_SEG = 1;
    public static final int READ_SCREEN = 2;
    public static final int DEF_READ_SCOPE = READ_SCREEN;

    public static final String[] READ_SCOPE_LABELS = {
            "只读一句", "读到段末", "读到屏底"
    };

    public static final boolean DEF_CLIP_READ = true;
    public static final boolean DEF_CLIP_AUTO_READ = false;

    private static final String K_BALL_ON = "ball_on";
    private static final String K_SIZE = "size";
    private static final String K_COLOR = "color";
    private static final String K_ALPHA = "alpha";
    private static final String K_IDLE_ALPHA = "idle_alpha";
    private static final String K_SCALE = "scale";
    private static final String K_HOLD = "hold_ms";
    private static final String K_SNAP = "snap";
    private static final String K_COMP = "compensate";
    private static final String K_BALL_X = "ball_x";
    private static final String K_BALL_Y = "ball_y";
    private static final String K_RATE = "rate";
    private static final String K_RATE_MIG_33 = "rate_mig_33";
    private static final String K_ALPHA_MIG_34 = "alpha_mig_34";
    private static final String K_PITCH = "pitch";
    private static final String K_HAPTIC = "haptic";
    private static final String K_THEME_MODE = "theme_mode";
    private static final String K_READ_SCOPE = "read_scope";
    private static final String K_CLIP_READ = "clip_read";
    private static final String K_CLIP_AUTO_READ = "clip_auto_read";
    private static final String K_CLIP_BALL_X = "clip_ball_x";
    private static final String K_CLIP_BALL_Y = "clip_ball_y";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
        migrateRate33();
        migrateAlpha34();
    }

    private void migrateRate33() {
        if (sp.getBoolean(K_RATE_MIG_33, false)) {
            return;
        }
        sp.edit().putBoolean(K_RATE_MIG_33, true).apply();
        if (!sp.contains(K_RATE)) {
            return;
        }
        float v = sp.getFloat(K_RATE, 1.0f);
        if (v == 1.0f) {
            sp.edit().putFloat(K_RATE, 2.0f).apply();
        }
    }

    private void migrateAlpha34() {
        if (sp.getBoolean(K_ALPHA_MIG_34, false)) {
            return;
        }
        sp.edit().putBoolean(K_ALPHA_MIG_34, true).apply();
        boolean hasA = sp.contains(K_ALPHA);
        boolean hasI = sp.contains(K_IDLE_ALPHA);
        if (!hasA && !hasI) {
            return;
        }
        boolean onDefaultA = !hasA || sp.getFloat(K_ALPHA, 0.92f) == 0.92f;
        boolean onDefaultI = !hasI || sp.getFloat(K_IDLE_ALPHA, 0.30f) == 0.30f;
        if (!onDefaultA || !onDefaultI) {
            return;
        }
        SharedPreferences.Editor ed = sp.edit();
        if (hasA) {
            ed.putFloat(K_ALPHA, 1.0f);
        }
        if (hasI) {
            ed.putFloat(K_IDLE_ALPHA, 1.0f);
        }
        ed.apply();
    }

    public boolean ballOn() {
        return sp.getBoolean(K_BALL_ON, true);
    }

    public void setBallOn(boolean v) {
        sp.edit().putBoolean(K_BALL_ON, v).apply();
    }

    public int sizeDp() {
        return sp.getInt(K_SIZE, DEF_SIZE_DP);
    }

    public void setSizeDp(int v) {
        sp.edit().putInt(K_SIZE, v).apply();
    }

    public int colorIndex() {
        int i = sp.getInt(K_COLOR, DEF_COLOR_INDEX);
        if (i < 0 || i >= COLORS.length) {
            i = 0;
        }
        return i;
    }

    public void setColorIndex(int v) {
        sp.edit().putInt(K_COLOR, v).apply();
    }

    public int color() {
        return COLORS[colorIndex()];
    }

    public float alpha() {
        return sp.getFloat(K_ALPHA, DEF_ALPHA);
    }

    public void setAlpha(float v) {
        sp.edit().putFloat(K_ALPHA, v).apply();
    }

    public float idleAlpha() {
        return sp.getFloat(K_IDLE_ALPHA, DEF_IDLE_ALPHA);
    }

    public void setIdleAlpha(float v) {
        sp.edit().putFloat(K_IDLE_ALPHA, v).apply();
    }

    public float scale() {
        float v = sp.getFloat(K_SCALE, 2.5f);
        if (v < 1.5f) {
            return 1.5f;
        }
        if (v > 5.0f) {
            return 5.0f;
        }
        return v;
    }

    public void setScale(float v) {
        sp.edit().putFloat(K_SCALE, v).apply();
    }

    public int holdMs() {
        return sp.getInt(K_HOLD, 260);
    }

    public void setHoldMs(int v) {
        sp.edit().putInt(K_HOLD, v).apply();
    }

    public boolean snapEdge() {
        return sp.getBoolean(K_SNAP, DEF_SNAP_EDGE);
    }

    public void setSnapEdge(boolean v) {
        sp.edit().putBoolean(K_SNAP, v).apply();
    }

    public boolean compensate() {
        return sp.getBoolean(K_COMP, true);
    }

    public void setCompensate(boolean v) {
        sp.edit().putBoolean(K_COMP, v).apply();
    }

    public boolean hasBallPos() {
        return sp.contains(K_BALL_X) && sp.contains(K_BALL_Y);
    }

    public int ballX() {
        return sp.getInt(K_BALL_X, 0);
    }

    public int ballY() {
        return sp.getInt(K_BALL_Y, 0);
    }

    public void setBallPos(int x, int y) {
        sp.edit().putInt(K_BALL_X, x).putInt(K_BALL_Y, y).apply();
    }

    public float rate() {
        float v = sp.getFloat(K_RATE, 2.0f);
        if (v < 0.5f) {
            return 0.5f;
        }
        if (v > 3.0f) {
            return 3.0f;
        }
        return v;
    }

    public void setRate(float v) {
        sp.edit().putFloat(K_RATE, v).apply();
    }

    public float pitch() {
        return sp.getFloat(K_PITCH, 1.0f);
    }

    public void setPitch(float v) {
        sp.edit().putFloat(K_PITCH, v).apply();
    }

    public boolean haptics() {
        return sp.getBoolean(K_HAPTIC, true);
    }

    public void setHaptics(boolean v) {
        sp.edit().putBoolean(K_HAPTIC, v).apply();
    }

    public int themeMode() {
        int v = sp.getInt(K_THEME_MODE, Theme.MODE_DEFAULT);
        if (v < 0 || v > 2) {
            return Theme.MODE_DEFAULT;
        }
        return v;
    }

    public void setThemeMode(int v) {
        sp.edit().putInt(K_THEME_MODE, v).apply();
    }

    public int readScope() {
        int v = sp.getInt(K_READ_SCOPE, DEF_READ_SCOPE);
        if (v < READ_ONE || v > READ_SCREEN) {
            return DEF_READ_SCOPE;
        }
        return v;
    }

    public void setReadScope(int v) {
        sp.edit().putInt(K_READ_SCOPE, v).apply();
    }

    public boolean clipRead() {
        return sp.getBoolean(K_CLIP_READ, DEF_CLIP_READ);
    }

    public void setClipRead(boolean v) {
        sp.edit().putBoolean(K_CLIP_READ, v).apply();
    }

    public boolean clipAutoRead() {
        return sp.getBoolean(K_CLIP_AUTO_READ, DEF_CLIP_AUTO_READ);
    }

    public void setClipAutoRead(boolean v) {
        sp.edit().putBoolean(K_CLIP_AUTO_READ, v).apply();
    }

    public boolean hasClipBallPos() {
        return sp.contains(K_CLIP_BALL_X) && sp.contains(K_CLIP_BALL_Y);
    }

    public int clipBallX() {
        return sp.getInt(K_CLIP_BALL_X, 0);
    }

    public int clipBallY() {
        return sp.getInt(K_CLIP_BALL_Y, 0);
    }

    public void setClipBallPos(int x, int y) {
        sp.edit().putInt(K_CLIP_BALL_X, x).putInt(K_CLIP_BALL_Y, y).apply();
    }
}
