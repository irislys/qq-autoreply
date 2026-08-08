package com.tff.qq;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;

/**
 * IPC client living in the hooked app process (QQ). All root / file work is
 * delegated to TffDaemonService which runs in the module's own process (:tff).
 */
public class TffLogger implements ServiceConnection {

    private final XposedModule module;
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile ITffDaemon daemon;
    private volatile int mode = TffConstants.MODE_NONE;
    private Context appContext;

    public TffLogger(@NonNull XposedModule module) {
        this.module = module;
    }

    /**
     * Binds the daemon and resolves the mode.
     *
     * @param fresh true on first load (full init, clears logs), false on hot
     *              reload (mode only, keeps logs)
     * @return the resolved mode (MODE_1 / MODE_2 / MODE_NO_ROOT / MODE_CONFLICT /
     *         MODE_INIT_FAIL)
     */
    public int connect(boolean fresh) {
        try {
            appContext = getAppContext();
            if (appContext == null) {
                return TffConstants.MODE_INIT_FAIL;
            }
            Context moduleCtx = appContext.createPackageContext(
                    TffConstants.DAEMON_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            Intent intent = new Intent().setClassName(
                    TffConstants.DAEMON_PACKAGE, TffConstants.DAEMON_SERVICE);
            if (!moduleCtx.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                return TffConstants.MODE_INIT_FAIL;
            }
            if (!latch.await(15, TimeUnit.SECONDS)) {
                try {
                    moduleCtx.unbindService(this);
                } catch (Throwable ignored) {
                }
                return TffConstants.MODE_INIT_FAIL;
            }
            ITffDaemon d = daemon;
            if (d == null) {
                return TffConstants.MODE_INIT_FAIL;
            }
            mode = fresh ? d.init() : d.getMode();
            return mode;
        } catch (Throwable t) {
            return TffConstants.MODE_INIT_FAIL;
        }
    }

    public int getMode() {
        return mode;
    }

    public void log(@NonNull String msg) {
        ITffDaemon d = daemon;
        if (d == null || mode < TffConstants.MODE_1) {
            return;
        }
        try {
            d.log(mode, msg);
        } catch (Throwable ignored) {
        }
    }

    public void shutdown() {
        try {
            if (appContext != null) {
                appContext.unbindService(this);
            }
        } catch (Throwable ignored) {
        }
        daemon = null;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        daemon = ITffDaemon.Stub.asInterface(service);
        latch.countDown();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        daemon = null;
    }

    private Context getAppContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentActivityThread");
            Object thread = current.invoke(null);
            if (thread == null) {
                return null;
            }
            Method getSystemContext = at.getDeclaredMethod("getSystemContext");
            return (Context) getSystemContext.invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }
}
