package com.floating.combo;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class Log {

    private static final String TAG = "看得清";
    private static final String NAME = "qiuqiu.log";
    private static final int MAX_BYTES = 150 * 1024;
    public static final int MAX_RENDER = 60;

    private static File file = null;
    private static HandlerThread thread = null;
    private static Handler h = null;
    private static boolean inited = false;
    private static String appVer = "?";
    private static final ArrayList<String> recent = new ArrayList<String>();

    public static synchronized void init(Context ctx) {
        try {
            appVer = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }

        if (inited) {
            return;
        }
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                dir = ctx.getFilesDir();
            }
            file = new File(dir, NAME);
        } catch (Exception e) {
            file = null;
        }
        try {
            thread = new HandlerThread("log");
            thread.start();
            h = new Handler(thread.getLooper());
        } catch (Exception e) {
            h = null;
        }
        inited = true;
        i("app", "日志开始 · " + deviceInfo());
    }

    private static String deviceInfo() {
        return "看得清=" + appVer
                + " SDK=" + android.os.Build.VERSION.SDK_INT
                + " 设备=" + android.os.Build.MANUFACTURER
                + " " + android.os.Build.MODEL
                + " 系统=" + android.os.Build.VERSION.RELEASE;
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        write("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        write("E", tag, msg + " | " + String.valueOf(t));
        if (t != null) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                t.printStackTrace(pw);
                pw.flush();
                String[] lines = sw.toString().split("\n");
                for (int i = 0; i < lines.length && i < 12; i++) {
                    write("E", tag, "  " + lines[i]);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void gesture(String msg) {
        write("G", "gesture", msg);
    }

    public static void state(String from, String to, String why) {
        write("S", "state", from + " → " + to + (why == null ? "" : "  (" + why + ")"));
    }

    private static synchronized void write(String level, String tag, String msg) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
                .format(new Date());
        final String line = time + " " + level + "/" + tag + ": " + msg;

        android.util.Log.d(TAG, line);

        synchronized (recent) {
            recent.add(line);
            while (recent.size() > 60) {
                recent.remove(0);
            }
        }

        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                append(line);
                trim();
            }
        });
    }

    private static synchronized void append(String line) {
        if (file == null) {
            return;
        }
        try {
            FileOutputStream fos = new FileOutputStream(file, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(line);
            w.write("\n");
            w.flush();
            w.close();
        } catch (Exception ignored) {
        }
    }

    private static synchronized void trim() {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            if (file.length() < MAX_BYTES) {
                return;
            }
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            long len = file.length();
            long keep = len / 2;
            raf.seek(keep);
            raf.readLine();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = raf.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            raf.close();
            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(bos.toByteArray());
            fos.close();
        } catch (Exception ignored) {
        }
    }

    public static synchronized ArrayList<String> recent() {
        synchronized (recent) {
            return new ArrayList<String>(recent);
        }
    }

    public static void clear() {
        if (file != null) {
            try {
                FileOutputStream fos = new FileOutputStream(file, false);
                fos.close();
            } catch (Exception ignored) {
            }
        }
        synchronized (recent) {
            recent.clear();
        }
        i("app", "日志已清空");
    }

    public static String content() {
        if (file == null || !file.exists()) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String asMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 看得清 诊断日志\n\n");
        sb.append("- 导出时间：").append(
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date()))
                .append("\n");
        sb.append("- 设备：").append(deviceInfo()).append("\n");
        ArrayList<String> ls = recent();
        sb.append("- 条数：").append(ls.size()).append("（最多保留 60 条）\n\n");
        sb.append("```\n");
        String body = content();
        if (body.length() == 0) {
            body = "(暂无日志)";
        }
        sb.append(body);
        if (!body.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append("```\n");
        return sb.toString();
    }

    public static String defaultName() {
        String stamp = new SimpleDateFormat("MMdd_HHmm", Locale.CHINA).format(new Date());
        return "看得清日志_" + stamp + ".md";
    }

    public static boolean exportTo(Context ctx, Uri uri) {
        if (uri == null) {
            return false;
        }
        try {
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) {
                return false;
            }
            os.write(asMarkdown().getBytes("UTF-8"));
            os.flush();
            os.close();
            i("app", "日志已导出（.md）");
            return true;
        } catch (Exception e) {
            Log.e("log", "写入选择器返回的位置失败", e);
            return false;
        }
    }

    public static String export(Context ctx) {
        if (file == null || !file.exists()) {
            return null;
        }
        String name = defaultName();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "text/markdown");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null && exportTo(ctx, uri)) {
                    return "下载/" + name;
                }
            }
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                dir = ctx.getFilesDir();
            }
            File out = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(asMarkdown().getBytes("UTF-8"));
            fos.close();
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.e("log", "导出失败", e);
            return null;
        }
    }

    public static String path() {
        return file == null ? null : file.getAbsolutePath();
    }
}
