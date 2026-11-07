package com.floating.combo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.MagnificationController;
import android.os.Build;

public class ZoomController {

    private final AccessibilityService svc;
    private final Prefs prefs;

    private int screenW = 0, screenH = 0;
    private float curScale = 1f;
    private float appliedScale = 0f;
    private float curCx = 0, curCy = 0;
    private boolean zoomOn = false;

    public ZoomController(AccessibilityService svc, Prefs prefs) {
        this.svc = svc;
        this.prefs = prefs;
    }

    public void setScreen(int w, int h) {
        screenW = w;
        screenH = h;
        if (curCx <= 0 || curCy <= 0) {
            curCx = w / 2f;
            curCy = h / 2f;
        }
    }

    public boolean isOn() {
        return zoomOn;
    }

    public float scale() {
        return curScale;
    }

    public void zoomInAt(float fx, float fy) {
        curScale = prefs.scale();
        zoomOn = true;
        panTo(fx, fy);
    }

    public void zoomOut() {
        curScale = 1f;
        zoomOn = false;
        appliedScale = 0f;
        reset(false);
    }

    public void panTo(float fx, float fy) {
        if (!zoomOn || curScale <= 1.01f || screenW <= 0) {
            return;
        }
        curCx = fx - (fx - screenW / 2f) / curScale;
        curCy = fy - (fy - screenH / 2f) / curScale;
        clampCenter();
        commit(false);
    }

    private void clampCenter() {
        if (curScale <= 1.01f || screenW <= 0) {
            return;
        }
        float hw = screenW / (2f * curScale);
        float hh = screenH / (2f * curScale);
        curCx = Math.max(hw, Math.min(screenW - hw, curCx));
        curCy = Math.max(hh, Math.min(screenH - hh, curCy));
    }

    private MagnificationController mag() {
        try {
            return svc.getMagnificationController();
        } catch (Exception e) {
            return null;
        }
    }

    private void commit(boolean animate) {
        MagnificationController c = mag();
        if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            if (Math.abs(appliedScale - curScale) > 0.01f) {
                c.setScale(curScale, animate);
                appliedScale = curScale;
            }
            c.setCenter(curCx, curCy, animate);
        } catch (Exception ignored) {
        }
    }

    private void reset(boolean animate) {
        MagnificationController c = mag();
        if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            c.reset(animate);
        } catch (Exception ignored) {
            try {
                c.setScale(1f, animate);
                c.setCenter(screenW / 2f, screenH / 2f, animate);
            } catch (Exception ignored2) {
            }
        }
    }
}
