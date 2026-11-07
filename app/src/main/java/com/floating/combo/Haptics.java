package com.floating.combo;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.os.VibrationEffect;
import android.os.Vibrator;

public final class Haptics {

    private static final int AMP = 255;
    private static final int AMP_LIGHT = 110;

    private static Vibrator v = null;
    private static boolean enabled = true;
    private static View view = null;

    private static String lastError = "";

    private Haptics() {
    }

    public static void init(Context c, boolean on) {
        enabled = on;
        if (v != null) {
            return;
        }
        try {
            v = (Vibrator) c.getApplicationContext()
                    .getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) {
            v = null;
        }
        Log.i("haptic", "初始化 " + (v == null ? "拿不到 Vibrator" : "ok")
                + " 系统说有马达=" + hasVibrator()
                + " 可控振幅=" + amplitudeControl(v));
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static void attach(View vw) {
        view = vw;
    }

    private static boolean viaView(int constant) {
        View vw = view;
        if (vw == null) {
            return false;
        }
        try {
            return vw.performHapticFeedback(constant);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean available() {
        return v != null;
    }

    public static String diag() {
        if (v == null) {
            return "拿不到 Vibrator（系统没给）";
        }
        return (hasVibrator() ? "系统说有马达" : "系统说无马达（仍会尝试震动）")
                + " · 振幅可控：" + (amplitudeControl(v) ? "是" : "否");
    }

    public static String lastError() {
        return lastError;
    }

    public static void tap() {
        buzz("tap", HapticFeedbackConstants.KEYBOARD_TAP,
                new long[]{0, 25}, AMP_LIGHT);
    }

    public static void zoom() {
        buzz("zoom", HapticFeedbackConstants.LONG_PRESS,
                new long[]{0, 80}, AMP);
    }

    public static void error() {
        buzzRaw("error", new long[]{0, 200}, AMP);
    }

    public static String test() {
        boolean was = enabled;
        enabled = true;
        zoom();
        enabled = was;
        return lastError;
    }

    private static boolean hasVibrator() {
        try {
            return v != null && v.hasVibrator();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean amplitudeControl(Vibrator vv) {
        try {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && vv != null && vv.hasAmplitudeControl();
        } catch (Throwable t) {
            return false;
        }
    }

    private static AudioAttributes attrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .build();
    }

    private static void buzz(String name, int viewConstant, long[] timings, int amp) {
        if (viaView(viewConstant)) {
            lastError = "";
            Log.i("haptic", name + "（系统触感通道）");
            return;
        }
        buzzRaw(name, timings, amp);
    }

    private static void buzzRaw(String name, long[] timings, int amp) {
        if (!enabled) {
            Log.i("haptic", name + " 跳过（开关关着）");
            return;
        }
        final Vibrator vv = v;
        if (vv == null) {
            lastError = "没有 Vibrator 实例";
            Log.w("haptic", name + " 跳过（没有 Vibrator 实例）");
            return;
        }
        try {
            boolean done = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    VibrationEffect fx;
                    if (timings.length <= 2) {
                        fx = VibrationEffect.createOneShot(
                                timings[timings.length - 1],
                                amplitudeControl(vv) ? amp : VibrationEffect.DEFAULT_AMPLITUDE);
                    } else {
                        int n = timings.length;
                        int[] amps = new int[n];
                        amps[0] = 0;
                        for (int i = 1; i < n; i++) {
                            amps[i] = amplitudeControl(vv) ? amp
                                    : VibrationEffect.DEFAULT_AMPLITUDE;
                        }
                        fx = VibrationEffect.createWaveform(timings, amps, -1);
                    }
                    vv.vibrate(fx, attrs());
                    done = true;
                } catch (Throwable t) {
                    Log.w("haptic", name + " VibrationEffect 失败，退回老接口：" + t);
                }
            }
            if (!done) {
                vv.vibrate(timings, -1);
            }
            lastError = "";
            Log.i("haptic", name + " " + timings[timings.length - 1] + "ms");
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            Log.w("haptic", name + " 失败：" + t);
        }
    }
}
