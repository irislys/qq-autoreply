package com.tff.qq;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;

public class TffLogger {

    public static final String CFG_DIR = "/data/adb/TFFQQ";
    public static final String MARK_1 = CFG_DIR + "/1";
    public static final String MARK_2 = CFG_DIR + "/2";
    public static final String LOG_1 = "/cache/qq-1";
    public static final String LOG_2 = "/cache/qq-2";
    public static final long MAX_LOG_SIZE = 5L * 1024 * 1024;

    public static final int MODE_NONE = 0;
    public static final int MODE_1 = 1;
    public static final int MODE_2 = 2;
    public static final int MODE_NO_ROOT = -1;
    public static final int MODE_CONFLICT = -2;
    public static final int MODE_INIT_FAIL = -3;

    private static final String TAG = "TFFQQBot";

    private final XposedModule module;
    private final int mode;
    private final String targetPath;

    private Thread writerThread;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(1024);
    private volatile boolean running;
    private volatile boolean writeWarned;

    public TffLogger(@NonNull XposedModule module, int mode) {
        this.module = module;
        this.mode = mode;
        this.targetPath = mode == MODE_1 ? LOG_1 : LOG_2;
    }

    public void start(@NonNull String processName) {
        running = true;
        writerThread = new Thread(this::writerLoop, "TFFWriter");
        writerThread.setDaemon(true);
        writerThread.start();
        log("========== [TFFQQ] 模式" + mode + " 启动 进程=" + processName + " ==========");
    }

    public void stop() {
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            writerThread = null;
        }
    }

    public void log(@NonNull String msg) {
        if (!running) {
            return;
        }
        if (!queue.offer(msg)) {
            queue.poll();
            queue.offer(msg);
        }
    }

    private void writerLoop() {
        while (running || !queue.isEmpty()) {
            String msg;
            try {
                msg = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (msg == null) {
                continue;
            }
            appendLine(timestamp() + " | " + msg);
        }
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private void appendLine(@NonNull String line) {
        try {
            File f = new File(targetPath);
            if (f.length() > MAX_LOG_SIZE) {
                try (OutputStream os = new FileOutputStream(f)) {
                    // truncate: keep log size under the hard cap
                }
            }
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(line.getBytes("UTF-8"));
                fos.write('\n');
                fos.flush();
            }
        } catch (Throwable t) {
            if (!writeWarned) {
                writeWarned = true;
                try {
                    module.log(5, TAG, "日志写入失败: " + targetPath + " (" + t + ")");
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // root detection / mode detection / one-shot init (run via su)
    // ------------------------------------------------------------------

    public static boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            boolean ok = line != null && line.contains("uid=0");
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Root check + config dir init + mode detection + log file init.
     * Must only be called by the main process; other processes should use {@link #readMode}.
     *
     * @return MODE_1 / MODE_2 / MODE_NO_ROOT / MODE_CONFLICT / MODE_INIT_FAIL
     */
    public static int detectAndInit(@NonNull XposedModule module) {
        if (!hasRoot()) {
            module.log(5, TAG, "权限不足(root无法获取)，TFFQQ 无法初始化，模块已停止");
            return MODE_NO_ROOT;
        }
        String state = runSu(true);
        if (state == null) {
            module.log(5, TAG, "初始化失败：su 执行异常，模块已停止");
            return MODE_INIT_FAIL;
        }
        if ("11".equals(state)) {
            module.log(5, TAG, "模式冲突：/data/adb/TFFQQ/1 与 /data/adb/TFFQQ/2 同时存在，"
                    + "TFFQQ 已停止工作，请删除其一后重启 QQ");
            return MODE_CONFLICT;
        }
        return "01".equals(state) ? MODE_2 : MODE_1;
    }

    /**
     * Mode detection only, no file changes. Used by non-main processes and hot reload.
     */
    public static int readMode(@NonNull XposedModule module) {
        if (!hasRoot()) {
            module.log(5, TAG, "权限不足(root无法获取)");
            return MODE_NO_ROOT;
        }
        String state = runSu(false);
        if (state == null) {
            return MODE_INIT_FAIL;
        }
        if ("11".equals(state)) {
            module.log(5, TAG, "模式冲突：/data/adb/TFFQQ/1 与 /data/adb/TFFQQ/2 同时存在，"
                    + "TFFQQ 已停止工作，请删除其一后重启 QQ");
            return MODE_CONFLICT;
        }
        return "01".equals(state) ? MODE_2 : MODE_1;
    }

    /**
     * Runs one su script. When init is enabled (main process only):
     * creates the config dir, creates the default mode-1 marker when no marker exists,
     * creates / clears both log files and relaxes permissions so the app process can
     * write them directly. Always prints STATE:h1h2 to stdout.
     */
    private static String runSu(boolean init) {
        try {
            StringBuilder script = new StringBuilder();
            script.append("mkdir -p ").append(CFG_DIR).append('\n');
            script.append("chmod 777 ").append(CFG_DIR).append('\n');
            script.append("H1=0; H2=0\n");
            script.append("[ -f ").append(MARK_1).append(" ] && H1=1\n");
            script.append("[ -f ").append(MARK_2).append(" ] && H2=1\n");
            script.append("echo \"STATE:$H1$H2\"\n");
            if (init) {
                script.append("if [ \"$H1$H2\" = \"00\" ]; then touch ").append(MARK_1)
                        .append("; chmod 666 ").append(MARK_1).append("; fi\n");
                script.append("mkdir -p /cache\n");
                script.append("touch ").append(LOG_1).append(' ').append(LOG_2).append('\n');
                script.append("chmod 666 ").append(LOG_1).append(' ').append(LOG_2).append('\n');
                script.append(": > ").append(LOG_1).append('\n');
                script.append(": > ").append(LOG_2).append('\n');
            }
            Process p = new ProcessBuilder("su", "-c", script.toString())
                    .redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String state = null;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("STATE:")) {
                    state = line.substring(6);
                }
            }
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return state;
        } catch (Throwable t) {
            return null;
        }
    }
}
