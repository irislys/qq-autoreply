package com.tff.qq;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import io.github.libxposed.api.XposedModule;

public class TffLogger {

    public static final int MODE_NONE = 0;
    public static final int MODE_1 = 1;
    public static final int MODE_2 = 2;

    private static final String TAG = "TFFQQBot";
    private static final String MODE_FILE = "/data/user/0/com.tff.qq/files/mode";

    private final XposedModule module;
    private final int mode;

    public TffLogger(@NonNull XposedModule module, int mode) {
        this.module = module;
        this.mode = mode;
    }

    /**
     * Writes to the LSPosed framework log with an obvious per-mode prefix.
     */
    public void log(@NonNull String msg) {
        try {
            module.log(4, TAG, prefix(mode) + " " + msg);
        } catch (Throwable ignored) {
        }
    }

    public static String prefix(int mode) {
        return mode == MODE_2 ? "[TFFQQ][模式2]" : "[TFFQQ][模式1]";
    }

    // ------------------------------------------------------------------
    // mode file: written by the module UI process, read once by QQ process
    // ------------------------------------------------------------------

    /**
     * Reads the mode file. Called once per process start.
     * Missing / unreadable file falls back to the default mode 1.
     */
    public static int readMode() {
        try {
            File f = new File(MODE_FILE);
            if (!f.exists() || !f.canRead()) {
                return MODE_1;
            }
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8];
                int n = in.read(buf);
                if (n <= 0) {
                    return MODE_1;
                }
                String s = new String(buf, 0, n, "UTF-8").trim();
                if ("2".equals(s)) {
                    return MODE_2;
                }
                return MODE_1;
            }
        } catch (Throwable t) {
            return MODE_1;
        }
    }

    /**
     * Writes the mode file (UI process, own uid). Also relaxes the files dir
     * permission so the QQ process can read it, and normalizes the file mode.
     */
    public static boolean writeMode(int mode) {
        try {
            File dir = new File("/data/user/0/com.tff.qq/files");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            dir.setExecutable(true, false);
            dir.setReadable(true, false);
            File f = new File(MODE_FILE);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(String.valueOf(mode).getBytes("UTF-8"));
                out.flush();
            }
            f.setReadable(true, false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
