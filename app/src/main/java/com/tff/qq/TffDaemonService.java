package com.tff.qq;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

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

public class TffDaemonService extends Service {

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(2048);
    private Thread writerThread;
    private volatile boolean running;
    private volatile int currentMode = TffConstants.MODE_NONE;

    private final ITffDaemon.Stub binder = new ITffDaemon.Stub() {

        @Override
        public int init() throws RemoteException {
            if (!isCallerAllowed()) {
                return TffConstants.MODE_INIT_FAIL;
            }
            synchronized (TffDaemonService.this) {
                if (!hasRoot()) {
                    return TffConstants.MODE_NO_ROOT;
                }
                int mode = runInitScript();
                if (mode >= TffConstants.MODE_1) {
                    currentMode = mode;
                    startWriter();
                }
                return mode;
            }
        }

        @Override
        public int getMode() throws RemoteException {
            if (!isCallerAllowed()) {
                return TffConstants.MODE_NONE;
            }
            synchronized (TffDaemonService.this) {
                if (!hasRoot()) {
                    return TffConstants.MODE_NO_ROOT;
                }
                return runStateScript();
            }
        }

        @Override
        public void log(int mode, String line) throws RemoteException {
            if (!isCallerAllowed()) {
                return;
            }
            enqueue(mode, line);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopWriter();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // security
    // ------------------------------------------------------------------

    private boolean isCallerAllowed() {
        try {
            int uid = Binder.getCallingUid();
            if (uid == android.os.Process.myUid()) {
                return true;
            }
            int qqUid = getPackageManager().getPackageUid("com.tencent.mobileqq", 0);
            return uid == qqUid;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // root detection / init scripts
    // ------------------------------------------------------------------

    private boolean hasRoot() {
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

    private int runInitScript() {
        String state = runSu(true);
        if (state == null) {
            return TffConstants.MODE_INIT_FAIL;
        }
        return stateToMode(state);
    }

    private int runStateScript() {
        String state = runSu(false);
        if (state == null) {
            return TffConstants.MODE_INIT_FAIL;
        }
        return stateToMode(state);
    }

    private int stateToMode(String state) {
        if ("11".equals(state)) {
            return TffConstants.MODE_CONFLICT;
        }
        return "01".equals(state) ? TffConstants.MODE_2 : TffConstants.MODE_1;
    }

    /**
     * Runs one su script. With init enabled: creates the config dir, creates the
     * default mode-1 marker when none exists, creates/clears both log files and
     * relaxes permissions so the daemon can write them. Always prints
     * STATE:h1h2 to stdout.
     */
    private String runSu(boolean init) {
        try {
            StringBuilder script = new StringBuilder();
            script.append("mkdir -p ").append(TffConstants.CFG_DIR).append('\n');
            script.append("chmod 777 ").append(TffConstants.CFG_DIR).append('\n');
            script.append("H1=0; H2=0\n");
            script.append("[ -f ").append(TffConstants.MARK_1).append(" ] && H1=1\n");
            script.append("[ -f ").append(TffConstants.MARK_2).append(" ] && H2=1\n");
            script.append("echo \"STATE:$H1$H2\"\n");
            if (init) {
                script.append("if [ \"$H1$H2\" = \"00\" ]; then touch ").append(TffConstants.MARK_1)
                        .append("; chmod 666 ").append(TffConstants.MARK_1).append("; fi\n");
                script.append("mkdir -p /cache\n");
                script.append("touch ").append(TffConstants.LOG_1).append(' ').append(TffConstants.LOG_2).append('\n');
                script.append("chmod 666 ").append(TffConstants.LOG_1).append(' ').append(TffConstants.LOG_2).append('\n');
                script.append(": > ").append(TffConstants.LOG_1).append('\n');
                script.append(": > ").append(TffConstants.LOG_2).append('\n');
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

    // ------------------------------------------------------------------
    // log writer (owned by the daemon process)
    // ------------------------------------------------------------------

    private void enqueue(int mode, String line) {
        if (!running) {
            return;
        }
        String item = mode + "|" + line;
        if (!queue.offer(item)) {
            queue.poll();
            queue.offer(item);
        }
    }

    private void startWriter() {
        if (writerThread != null && writerThread.isAlive()) {
            return;
        }
        running = true;
        writerThread = new Thread(this::writerLoop, "TFFWriter");
        writerThread.setDaemon(true);
        writerThread.start();
        log(TffConstants.MODE_1, "========== [TFFQQ] daemon 启动 ==========");
    }

    private void stopWriter() {
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

    private void log(int mode, String line) {
        enqueue(mode, line);
    }

    private void writerLoop() {
        while (running || !queue.isEmpty()) {
            String item;
            try {
                item = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (item == null) {
                continue;
            }
            int sep = item.indexOf('|');
            int mode = sep > 0 ? parseMode(item.substring(0, sep)) : TffConstants.MODE_1;
            String line = sep > 0 ? item.substring(sep + 1) : item;
            appendLine(mode, timestamp() + " | " + line);
        }
    }

    private int parseMode(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return TffConstants.MODE_1;
        }
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private void appendLine(int mode, String line) {
        String path = mode == TffConstants.MODE_2 ? TffConstants.LOG_2 : TffConstants.LOG_1;
        try {
            File f = new File(path);
            if (f.length() > TffConstants.MAX_LOG_SIZE) {
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
            Log.e(TffConstants.TAG, "日志写入失败: " + path + " (" + t + ")");
        }
    }
}
