package com.tff.qq;

public final class TffConstants {

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

    public static final String DAEMON_PACKAGE = "com.tff.qq";
    public static final String DAEMON_SERVICE = "com.tff.qq.TffDaemonService";

    public static final String TAG = "TFFQQBot";

    private TffConstants() {
    }
}
