package com.floating.combo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TtsManager {

    public static class Engine {
        public String name;
        public String pkg;

        public Engine(String name, String pkg) {
            this.name = name;
            this.pkg = pkg;
        }
    }

    public static class Voice {
        public String name;
        public String label;

        public Voice(String name, String label) {
            this.name = name;
            this.label = label;
        }
    }

    private static final String PREF = "cfg";
    private static final String KEY_ENGINE = "engine_pkg";
    private static final String KEY_VOICE = "engine_voice";
    private static final int MAX_CHUNK = 3600;

    private static TextToSpeech tts = null;

    private static volatile int serial = 0;
    private static volatile boolean ready = false;
    private static volatile boolean failed = false;
    private static volatile boolean cnOk = false;
    private static String currentPkg = "";
    private static String defaultPkg = "";
    private static Context appCtx = null;
    private static OnInitListener listener = null;
    private static SpeechListener speechListener = null;

    private static volatile int session = 0;
    private static volatile int chunkTotal = 0;
    private static long lastRetry = 0;

    public interface OnInitListener {
        void onEngineReady(boolean ready, boolean cnOk, String pkg);
    }

    public interface SpeechListener {
        void onDone();

        void onError();

        void onChunk();
    }

    public static void setListener(OnInitListener l) {
        listener = l;
    }

    public static void setSpeechListener(SpeechListener l) {
        speechListener = l;
    }

    private static void notifyListener() {
        if (listener != null) {
            try {
                listener.onEngineReady(ready, cnOk, currentPkg);
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static boolean isFailed() {
        return failed;
    }

    public static boolean isCnOk() {
        return cnOk;
    }

    public static String getCurrentPkg() {
        return currentPkg;
    }

    public static List<Engine> enumerate(Context ctx) {
        List<Engine> out = new ArrayList<Engine>();

        TextToSpeech probe = null;
        try {
            probe = new TextToSpeech(ctx.getApplicationContext(),
                    new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int status) {
                        }
                    });
            List<TextToSpeech.EngineInfo> es = probe.getEngines();
            Log.i("tts", "getEngines() 命中 " + (es == null ? 0 : es.size()) + " 个");
            if (es != null) {
                for (TextToSpeech.EngineInfo e : es) {
                    if (e == null || e.name == null || containsPkg(out, e.name)) {
                        continue;
                    }
                    String label = e.label;
                    if (label == null || label.isEmpty()) {
                        label = e.name;
                    }
                    out.add(new Engine(label, e.name));
                }
            }
            try {
                String d = probe.getDefaultEngine();
                defaultPkg = (d == null) ? "" : d;
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        } finally {
            if (probe != null) {
                try {
                    probe.shutdown();
                } catch (Exception ignored) {
                }
            }
        }
        Log.i("tts", "系统默认引擎 = " + (defaultPkg.length() == 0 ? "(未知)" : defaultPkg));

        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            PackageManager pm = ctx.getPackageManager();
            int flag = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flag = PackageManager.MATCH_ALL;
            }
            List<ResolveInfo> list = pm.queryIntentServices(intent, flag);
            int before = out.size();
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.serviceInfo == null) {
                        continue;
                    }
                    String pkg = ri.serviceInfo.packageName;
                    if (pkg == null || pkg.length() == 0 || containsPkg(out, pkg)) {
                        continue;
                    }
                    String label;
                    try {
                        label = String.valueOf(ri.loadLabel(pm));
                    } catch (Exception e) {
                        label = pkg;
                    }
                    out.add(new Engine(label, pkg));
                }
            }
            Log.i("tts", "包查询 TTS_SERVICE 命中 "
                    + (list == null ? 0 : list.size()) + " 个，补齐后共 "
                    + out.size() + " 个（新增 " + (out.size() - before) + "）");
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String defaultEnginePkg() {
        return defaultPkg == null ? "" : defaultPkg;
    }

    private static boolean containsPkg(List<Engine> list, String pkg) {
        for (Engine e : list) {
            if (e.pkg != null && e.pkg.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    public static List<Voice> listVoicesZhFirst() {
        List<Voice> out = new ArrayList<Voice>();
        if (tts == null || !ready) {
            return out;
        }
        List<Voice> zh = new ArrayList<Voice>();
        List<Voice> other = new ArrayList<Voice>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                if (vs != null && !vs.isEmpty()) {
                    for (android.speech.tts.Voice v : vs) {
                        if (v == null || v.getName() == null) {
                            continue;
                        }
                        Locale l = null;
                        try {
                            l = v.getLocale();
                        } catch (Exception ignored) {
                        }
                        String tag = v.getName();
                        String label = friendlyVoice(tag, l);
                        Voice item = new Voice(tag, label);
                        if (l != null && "zh".equals(l.getLanguage())) {
                            zh.add(item);
                        } else {
                            other.add(item);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (zh.isEmpty() && other.isEmpty()) {
            try {
                java.util.Set<Locale> ls = tts.getAvailableLanguages();
                if (ls != null) {
                    for (Locale l : ls) {
                        Voice v = new Voice(l.toLanguageTag(),
                                l.getDisplayName(Locale.SIMPLIFIED_CHINESE));
                        if ("zh".equals(l.getLanguage())) {
                            zh.add(v);
                        } else {
                            other.add(v);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        out.addAll(zh);
        out.addAll(other);
        return out;
    }

    private static String friendlyVoice(String tag, Locale l) {
        if (tag == null) {
            return "";
        }
        String t = tag.toLowerCase(Locale.ROOT).replace("_", "-");
        String who = null;
        if (t.contains("xiaoxiao")) {
            who = "晓晓 · 女声";
        } else if (t.contains("yunxi")) {
            who = "云希 · 男声";
        } else if (t.contains("yunyang")) {
            who = "云扬 · 男声";
        } else if (t.contains("xiaoyi")) {
            who = "晓伊 · 女声";
        } else if (t.contains("yunjian")) {
            who = "云健 · 男声";
        } else if (t.contains("xiaoshuang")) {
            who = "晓双 · 女声";
        } else if (t.contains("yunxia")) {
            who = "云夏 · 童声";
        } else if (t.contains("not-set") || t.equals("not_set")) {
            who = "系统默认";
        }
        if (who != null) {
            return who;
        }
        if (l != null) {
            return tag + "　" + l.getDisplayName(Locale.SIMPLIFIED_CHINESE);
        }
        return tag;
    }

    public static boolean applyVoice(String name) {
        if (tts == null || !ready) {
            return false;
        }
        try {
            if (name != null && name.length() > 0
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                if (vs != null) {
                    for (android.speech.tts.Voice v : vs) {
                        if (v != null && name.equals(v.getName())) {
                            int r = tts.setVoice(v);
                            if (r == TextToSpeech.SUCCESS) {
                                return true;
                            }
                        }
                    }
                }
            }
            Locale l = Locale.CHINA;
            if (name != null && name.length() > 0) {
                try {
                    l = Locale.forLanguageTag(name);
                } catch (Exception ignored) {
                    l = Locale.CHINA;
                }
            }
            int r = tts.setLanguage(l);
            if (r == TextToSpeech.LANG_MISSING_DATA
                    || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                return setup(tts);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void applySavedVoice() {
        if (appCtx == null) {
            return;
        }
        applyVoice(getSavedVoice(appCtx));
    }

    public static void saveEngine(Context ctx, String pkg) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_ENGINE, pkg).apply();
        } catch (Exception ignored) {
        }
    }

    public static String getSavedEngine(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String v = sp.getString(KEY_ENGINE, "");
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public static void saveVoice(Context ctx, String tag) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_VOICE, tag == null ? "" : tag).apply();
        } catch (Exception ignored) {
        }
    }

    public static String getSavedVoice(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String v = sp.getString(KEY_VOICE, "");
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        if (tts != null && !failed) {
            notifyListener();
            return;
        }
        bind();
    }

    public static void rebind(Context ctx) {
        if (appCtx == null && ctx != null) {
            appCtx = ctx.getApplicationContext();
        }
        release();
        bind();
    }

    public static void retryIfFailed() {
        if (!failed || appCtx == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRetry < 3000) {
            return;
        }
        lastRetry = now;
        Log.w("tts", "引擎初始化失败，重试一次");
        rebind(appCtx);
    }

    private static void bind() {
        if (appCtx == null) {
            return;
        }
        session++;
        chunkTotal = 0;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        ready = false;
        failed = false;
        final int mySerial = ++serial;
        TextToSpeech.OnInitListener l = new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (mySerial != serial) {
                    return;
                }
                if (status != TextToSpeech.SUCCESS) {
                    failed = true;
                    ready = false;
                    Log.e("tts", "引擎初始化失败 status=" + status
                            + " pkg=" + currentPkg);
                    notifyListener();
                    return;
                }
                cnOk = setup(tts);
                ready = true;
                failed = false;
                attachProgress();
                applySavedVoice();
                Log.i("tts", "引擎就绪 pkg=" + (currentPkg.length() == 0 ? "(系统默认)" : currentPkg)
                        + " 中文=" + cnOk + " 音色数=" + listVoicesZhFirst().size());
                notifyListener();
            }
        };
        String pkg = getSavedEngine(appCtx);
        if (pkg != null && pkg.length() > 0 && !installed(appCtx, pkg)) {
            Log.w("tts", "保存的引擎已不存在：" + pkg + "，改回系统默认引擎");
            saveEngine(appCtx, "");
            pkg = "";
        }
        try {
            if (pkg != null && pkg.length() > 0) {
                tts = new TextToSpeech(appCtx, l, pkg);
                currentPkg = pkg;
            } else {
                tts = new TextToSpeech(appCtx, l);
                currentPkg = "";
            }
        } catch (Exception e) {
            tts = null;
            failed = true;
            Log.e("tts", "new TextToSpeech 抛异常", e);
        }
    }

    private static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void attachProgress() {
        if (tts == null) {
            return;
        }
        try {
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    onChunkEnd(id, false);
                }

                @Override
                public void onError(String id) {
                    onChunkEnd(id, true);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void onChunkEnd(String id, boolean error) {
        int s = sessionOf(id);
        if (s == -1) {
            Log.w("tts", "回调没有可识别的 id，按本次结束处理：" + id);
            notifyEnd(error);
            return;
        }
        if (s != session) {
            Log.i("tts", "丢弃旧会话的回调 id=" + id);
            return;
        }
        if (error) {
            notifyEnd(true);
            return;
        }
        if (!isLastChunk(id)) {
            if (speechListener != null) {
                speechListener.onChunk();
            }
            return;
        }
        notifyEnd(false);
    }

    private static void notifyEnd(boolean error) {
        if (speechListener == null) {
            return;
        }
        if (error) {
            speechListener.onError();
        } else {
            speechListener.onDone();
        }
    }

    private static boolean isLastChunk(String id) {
        int i = (id == null) ? -1 : id.indexOf('#');
        if (i < 0) {
            return true;
        }
        try {
            return Integer.parseInt(id.substring(i + 1)) >= chunkTotal - 1;
        } catch (Exception e) {
            return true;
        }
    }

    private static int sessionOf(String id) {
        if (id == null || id.length() == 0) {
            return -1;
        }
        int i = id.indexOf('#');
        String head = (i < 0) ? id : id.substring(0, i);
        try {
            return Integer.parseInt(head);
        } catch (Exception e) {
            return -1;
        }
    }

    public static void release() {
        serial++;
        session++;
        chunkTotal = 0;
        ready = false;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        tts = null;
    }

    public static boolean setup(TextToSpeech t) {
        if (t == null) {
            return false;
        }
        try {
            int r = t.setLanguage(Locale.CHINA);
            boolean ok = !(r == TextToSpeech.LANG_MISSING_DATA
                    || r == TextToSpeech.LANG_NOT_SUPPORTED);
            if (!ok) {
                t.setLanguage(Locale.getDefault());
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean speak(String text, float rate, float pitch) {
        if (tts == null || !ready || text == null) {
            return false;
        }
        try {
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            java.util.List<String> parts = split(text);
            if (parts.isEmpty()) {
                return false;
            }
            chunkTotal = parts.size();
            int mySession = ++session;
            Log.i("tts", "speak " + text.length() + " 字 → " + parts.size()
                    + " 段 rate=" + rate + " pitch=" + pitch);
            for (int i = 0; i < parts.size(); i++) {
                String id = mySession + "#" + i;
                int mode = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                int r;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Bundle b = new Bundle();
                    b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
                    r = tts.speak(parts.get(i), mode, b, id);
                } else {
                    r = tts.speak(parts.get(i), mode, null);
                }
                if (r != TextToSpeech.SUCCESS) {
                    Log.e("tts", "引擎拒收第 " + i + " 段，返回 " + r);
                    session++;
                    chunkTotal = 0;
                    try {
                        tts.stop();
                    } catch (Exception ignored) {
                    }
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            Log.e("tts", "speak 抛异常", e);
            chunkTotal = 0;
            return false;
        }
    }

    private static java.util.List<String> split(String text) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String t = text.trim();
        while (t.length() > 0) {
            if (t.length() <= MAX_CHUNK) {
                out.add(t);
                break;
            }
            int cut = -1;
            for (int i = MAX_CHUNK - 1; i >= MAX_CHUNK / 2; i--) {
                char c = t.charAt(i);
                if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                        || c == '.' || c == '!' || c == '?' || c == ';'
                        || c == '，' || c == ',' || c == '、') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut <= 0) {
                cut = MAX_CHUNK;
            }
            out.add(t.substring(0, cut));
            t = t.substring(cut);
        }
        return out;
    }

    public static void stop() {
        session++;
        chunkTotal = 0;
        if (tts == null) {
            return;
        }
        try {
            tts.stop();
        } catch (Exception ignored) {
        }
    }

    public static void forceStop() {
        if (tts == null) {
            return;
        }
        boolean still = false;
        try {
            still = tts.isSpeaking();
        } catch (Exception ignored) {
        }
        if (!still) {
            return;
        }
        String pkg = currentPkg;
        release();
        bind();
        currentPkg = pkg;
    }

    public static TextToSpeech get() {
        return tts;
    }
}
