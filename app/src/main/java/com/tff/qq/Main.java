package com.tff.qq;

import androidx.annotation.NonNull;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class Main extends XposedModule {

    private static final String QQ_PACKAGE = "com.tencent.mobileqq";

    private static final String CONTACT_CLASS = "com.tencent.qqnt.kernelpublic.nativeinterface.Contact";
    private static final String CALLBACK_CLASS = "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";
    private static final String KERNEL_SERVICE_IMPL = "com.tencent.qqnt.kernel.api.impl.KernelServiceImpl";

    private static final String ID_LOAD_CLASS = "loadClass";
    private static final String ID_GET_MSG_SERVICE = "getMsgService";
    private static final String ID_SEND_MSG = "sendMsg";
    private static final String ID_ADD_MSG_LISTENER = "addMsgListener";
    private static final String ID_ON_RECV_MSG = "onRecvMsg";

    private static final String ID_PROBE_RESUME = "probe.onResume";
    private static final String ID_PROBE_PAUSE = "probe.onPause";
    private static final String ID_PROBE_START = "probe.startActivity";
    private static final String ID_PROBE_START_FOR_RESULT = "probe.startActivityForResult";
    private static final String ID_PROBE_CTX_START = "probe.ctx.startActivity";

    private TffLogger logger;
    private volatile int mode = TffLogger.MODE_NONE;

    private volatile ClassLoader hostCl;

    private final Set<String> installed = new HashSet<>();
    private Map<Executable, XposedInterface.HookHandle> oldHandles;

    private Object msgService;
    private Method sendMsgMethod;
    private final Set<String> recentReplies = new HashSet<>();

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        mode = TffLogger.readMode();
        logger = new TffLogger(this, mode);
        dbg("module loaded in " + param.getProcessName() + " mode=" + mode);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!QQ_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (mode < TffLogger.MODE_1) {
            dbg("module disabled: mode=" + mode);
            return;
        }
        if (mode == TffLogger.MODE_2) {
            dbg("probe mode active, installing observation hooks");
            installProbeHooks();
            return;
        }
        dbg("package ready, installing kernel hooks");
        hostCl = param.getClassLoader();
        hookClassLoaderLoad();
        installGetMsgServiceHook();
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        if (mode != TffLogger.MODE_1 && mode != TffLogger.MODE_2) {
            dbg("hot reload rejected: mode=" + mode);
            return false;
        }
        try {
            Object state = mode == TffLogger.MODE_1
                    ? (msgService != null ? msgService : hostCl) : null;
            param.setSavedInstanceState(state);
        } catch (Throwable th) {
            dbg("hot reload rejected: save state failed " + th);
            return false;
        }
        dbg("hot reload allowed");
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        mode = TffLogger.readMode();
        logger = new TffLogger(this, mode);
        if (mode != TffLogger.MODE_1 && mode != TffLogger.MODE_2) {
            dbg("hot reloaded but mode invalid: " + mode + ", hooks not reinstalled");
            return;
        }
        dbg("hot reloaded, reinstalling hooks");
        oldHandles = new HashMap<>();
        for (XposedInterface.HookHandle h : param.getOldHookHandles()) {
            try {
                oldHandles.put(h.getExecutable(), h);
            } catch (Throwable ignored) {
            }
        }
        if (mode == TffLogger.MODE_2) {
            installProbeHooks();
            return;
        }
        Object state = param.getSavedInstanceState();
        if (state instanceof ClassLoader) {
            hostCl = (ClassLoader) state;
        } else if (state != null) {
            msgService = state;
            try {
                hostCl = msgService.getClass().getClassLoader();
            } catch (Throwable ignored) {
            }
        } else {
            dbg("hot reloaded but state lost, hooks not reinstalled");
            return;
        }
        hookClassLoaderLoad();
        installGetMsgServiceHook();
        if (msgService != null) {
            cacheSendMsgMethod();
            installSendMsgHook();
            installListenerAnchorHook();
        }
        restoreListenerHookFromOldHandles();
    }

    // ------------------------------------------------------------------
    // anchor-based hook chain (no dex parsing, no feature search)
    //
    // ClassLoader.loadClass -> KernelServiceImpl.getMsgService
    //   -> captured msgService -> sendMsg (name + signature match)
    //   -> addMsgListener -> captured listener -> onRecvMsg (name + List param)
    // ------------------------------------------------------------------

    private void hookClassLoaderLoad() {
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            loadClass.setAccessible(true);
            installOrReplace(ID_LOAD_CLASS, loadClass, chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof String && KERNEL_SERVICE_IMPL.equals(arg)) {
                        installGetMsgServiceHook();
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            dbg("hooked ClassLoader.loadClass");
        } catch (Throwable t) {
            dbg("hook loadClass failed: " + t);
        }
    }

    private synchronized void installGetMsgServiceHook() {
        if (installed.contains(ID_GET_MSG_SERVICE) || hostCl == null) {
            return;
        }
        try {
            Class<?> kernelService = Class.forName(KERNEL_SERVICE_IMPL, false, hostCl);
            Method m = kernelService.getDeclaredMethod("getMsgService");
            m.setAccessible(true);
            installOrReplace(ID_GET_MSG_SERVICE, m, this::hookGetMsgService);
            installed.add(ID_GET_MSG_SERVICE);
            dbg("hooked KernelServiceImpl.getMsgService");
        } catch (Throwable t) {
            dbg("getMsgService hook pending: " + t);
        }
    }

    private void installSendMsgHook() {
        if (installed.contains(ID_SEND_MSG) || msgService == null) {
            return;
        }
        if (sendMsgMethod == null) {
            cacheSendMsgMethod();
        }
        if (sendMsgMethod == null) {
            return;
        }
        try {
            installOrReplace(ID_SEND_MSG, sendMsgMethod, this::hookSendMsg);
            installed.add(ID_SEND_MSG);
            dbg("hooked sendMsg");
        } catch (Throwable t) {
            dbg("sendMsg hook pending: " + t);
        }
    }

    private void installListenerAnchorHook() {
        if (installed.contains(ID_ADD_MSG_LISTENER) || msgService == null) {
            return;
        }
        try {
            Class<?> cl = msgService.getClass();
            for (Method m : cl.getDeclaredMethods()) {
                if ("addMsgListener".equals(m.getName()) && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    installOrReplace(ID_ADD_MSG_LISTENER, m, this::hookAddMsgListener);
                    installed.add(ID_ADD_MSG_LISTENER);
                    dbg("hooked addMsgListener");
                    return;
                }
            }
            dbg("addMsgListener not found on " + cl.getName());
        } catch (Throwable t) {
            dbg("addMsgListener hook pending: " + t);
        }
    }

    private void installListenerHook(Object listener) {
        if (installed.contains(ID_ON_RECV_MSG) || listener == null) {
            return;
        }
        try {
            Class<?> cl = listener.getClass();
            for (Method m : cl.getDeclaredMethods()) {
                if ("onRecvMsg".equals(m.getName()) && m.getParameterCount() == 1) {
                    Class<?> pt = m.getParameterTypes()[0];
                    if (List.class.isAssignableFrom(pt)) {
                        m.setAccessible(true);
                        installOrReplace(ID_ON_RECV_MSG, m, this::hookOnRecvMsg);
                        installed.add(ID_ON_RECV_MSG);
                        dbg("hooked listener.onRecvMsg");
                        return;
                    }
                }
            }
            dbg("listener onRecvMsg not found on " + cl.getName());
        } catch (Throwable t) {
            dbg("listener hook pending: " + t);
        }
    }

    private void restoreListenerHookFromOldHandles() {
        if (installed.contains(ID_ON_RECV_MSG) || oldHandles == null) {
            return;
        }
        for (Executable e : oldHandles.keySet()) {
            if (e instanceof Method && "onRecvMsg".equals(((Method) e).getName())) {
                Method m = (Method) e;
                m.setAccessible(true);
                installOrReplace(ID_ON_RECV_MSG, m, this::hookOnRecvMsg);
                installed.add(ID_ON_RECV_MSG);
                dbg("restored onRecvMsg from old handle");
                return;
            }
        }
    }

    private void cacheSendMsgMethod() {
        if (sendMsgMethod != null || msgService == null) {
            return;
        }
        try {
            Class<?> contact = Class.forName(CONTACT_CLASS, false, hostCl);
            Class<?> callback = Class.forName(CALLBACK_CLASS, false, hostCl);
            Class<?> cl = msgService.getClass();
            for (Method m : cl.getDeclaredMethods()) {
                if (isSendMsgSignature(m, contact, callback)) {
                    m.setAccessible(true);
                    sendMsgMethod = m;
                    return;
                }
            }
        } catch (Throwable t) {
            dbg("cache sendMsg failed: " + t);
        }
    }

    private boolean isSendMsgSignature(Method m, Class<?> contact, Class<?> callback) {
        Class<?>[] pts = m.getParameterTypes();
        return pts.length == 5 && pts[0] == long.class
                && pts[1] == contact
                && pts[2] == ArrayList.class
                && pts[3] == HashMap.class
                && pts[4] == callback;
    }

    private XposedInterface.HookHandle installOrReplace(String id, Method method,
                                                        XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle old = oldHandles == null ? null : oldHandles.remove(method);
        if (old != null) {
            try {
                return old.replaceHook(hooker);
            } catch (Throwable t) {
                dbg("replaceHook failed for " + id + ": " + t);
            }
        }
        return hook(method)
                .setId(id)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    // ------------------------------------------------------------------
    // hookers
    // ------------------------------------------------------------------

    private Object hookGetMsgService(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (result != null) {
            msgService = result;
            dbg("captured msgService " + result.getClass().getName());
            cacheSendMsgMethod();
            installSendMsgHook();
            installListenerAnchorHook();
        }
        return result;
    }

    private Object hookSendMsg(XposedInterface.Chain chain) throws Throwable {
        try {
            if (msgService == null) {
                msgService = chain.getThisObject();
                dbg("captured msgService via sendMsg " + msgService.getClass().getName());
            }
            List<Object> args = chain.getArgs();
            if (args.size() >= 2 && args.get(1) != null) {
                dbg("sendMsg peer=" + args.get(1)
                        + " elements=" + (args.size() >= 3 ? String.valueOf(args.get(2)) : "?"));
            }
        } catch (Throwable ignored) {
        }
        return chain.proceed();
    }

    private Object hookAddMsgListener(XposedInterface.Chain chain) throws Throwable {
        try {
            List<Object> args = chain.getArgs();
            if (!args.isEmpty() && args.get(0) != null) {
                installListenerHook(args.get(0));
            }
        } catch (Throwable t) {
            dbg("addMsgListener intercept error: " + t);
        }
        return chain.proceed();
    }

    private Object hookOnRecvMsg(XposedInterface.Chain chain) throws Throwable {
        try {
            List<Object> args = chain.getArgs();
            if (!args.isEmpty() && args.get(0) != null) {
                handleRecvMsgs(args.get(0));
            }
        } catch (Throwable t) {
            dbg("onRecvMsg intercept error: " + t);
        }
        return chain.proceed();
    }

    // ------------------------------------------------------------------
    // probe mode: observation hooks, record only, never modify
    // ------------------------------------------------------------------

    private void installProbeHooks() {
        try {
            Class<?> activity = Class.forName("android.app.Activity");
            Class<?> ctxWrapper = Class.forName("android.content.ContextWrapper");
            installProbeHook(ID_PROBE_RESUME, activity, "onResume",
                    findDeclaredMethod(activity, "onResume"), this::hookProbeResume);
            installProbeHook(ID_PROBE_PAUSE, activity, "onPause",
                    findDeclaredMethod(activity, "onPause"), this::hookProbePause);
            installProbeHook(ID_PROBE_START, activity, "startActivity",
                    findDeclaredMethod(activity, "startActivity", android.content.Intent.class),
                    this::hookProbeStartActivity);
            installProbeHook(ID_PROBE_START_FOR_RESULT, activity, "startActivityForResult",
                    findDeclaredMethod(activity, "startActivityForResult",
                            android.content.Intent.class, int.class),
                    this::hookProbeStartActivity);
            installProbeHook(ID_PROBE_CTX_START, ctxWrapper, "startActivity",
                    findDeclaredMethod(ctxWrapper, "startActivity", android.content.Intent.class),
                    this::hookProbeStartActivity);
        } catch (Throwable t) {
            dbg("probe hooks failed: " + t);
        }
    }

    private void installProbeHook(String id, Class<?> cls, String name, Method method,
                                  XposedInterface.Hooker hooker) {
        if (installed.contains(id) || method == null) {
            return;
        }
        try {
            method.setAccessible(true);
            installOrReplace(id, method, hooker);
            installed.add(id);
            dbg("probe hooked " + cls.getName() + "." + name);
        } catch (Throwable t) {
            dbg("probe hook pending " + id + ": " + t);
        }
    }

    private Object hookProbeResume(XposedInterface.Chain chain) throws Throwable {
        try {
            Object th = chain.getThisObject();
            dbg("页面: " + (th == null ? "?" : th.getClass().getName()) + stackTop());
        } catch (Throwable t) {
            dbg("probe onResume error: " + t);
        }
        return chain.proceed();
    }

    private Object hookProbePause(XposedInterface.Chain chain) throws Throwable {
        try {
            Object th = chain.getThisObject();
            dbg("离开: " + (th == null ? "?" : th.getClass().getName()) + stackTop());
        } catch (Throwable t) {
            dbg("probe onPause error: " + t);
        }
        return chain.proceed();
    }

    private Object hookProbeStartActivity(XposedInterface.Chain chain) throws Throwable {
        try {
            List<Object> args = chain.getArgs();
            Object intent = args.isEmpty() ? null : args.get(0);
            Object th = chain.getThisObject();
            dbg("跳转: " + (th == null ? "?" : th.getClass().getName())
                    + " | " + intentSummary(intent) + stackTop());
        } catch (Throwable t) {
            dbg("probe startActivity error: " + t);
        }
        return chain.proceed();
    }

    private String intentSummary(Object intent) {
        try {
            if (!(intent instanceof android.content.Intent)) {
                return String.valueOf(intent);
            }
            android.content.Intent i = (android.content.Intent) intent;
            StringBuilder sb = new StringBuilder("Intent{");
            sb.append("action=").append(i.getAction());
            sb.append(", data=").append(i.getData());
            sb.append(", type=").append(i.getType());
            sb.append(", comp=").append(i.getComponent());
            sb.append(", flags=0x").append(Integer.toHexString(i.getFlags()));
            android.os.Bundle ex = i.getExtras();
            if (ex != null && !ex.isEmpty()) {
                sb.append(", extras{");
                int shown = 0;
                for (String k : ex.keySet()) {
                    if (shown++ >= 5) {
                        sb.append("...");
                        break;
                    }
                    Object v = ex.get(k);
                    sb.append(k).append('=').append(String.valueOf(v)).append(';');
                }
                sb.append('}');
            }
            sb.append('}');
            String s = sb.toString();
            return s.length() > 600 ? s.substring(0, 600) : s;
        } catch (Throwable t) {
            return String.valueOf(intent);
        }
    }

    private String stackTop() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder(" 栈:");
            int shown = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.startsWith("com.tff.qq") || cn.startsWith("io.github.libxposed")
                        || cn.startsWith("java.lang.Thread") || cn.startsWith("android.os.Looper")) {
                    continue;
                }
                sb.append('\n').append("    at ").append(cn).append('.').append(e.getMethodName())
                        .append('(').append(e.getFileName()).append(':').append(e.getLineNumber()).append(')');
                if (++shown >= 8) {
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private Method findDeclaredMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // message handling
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void handleRecvMsgs(Object msgListObj) {
        try {
            if (!(msgListObj instanceof List)) {
                dbg("recv msgs not a list: " + msgListObj.getClass().getName());
                return;
            }
            List<Object> msgList = (List<Object>) msgListObj;
            for (Object msg : msgList) {
                handleMsg(msg);
            }
        } catch (Throwable t) {
            dbg("handleRecvMsgs error: " + t);
        }
    }

    private void handleMsg(Object msgRecord) {
        try {
            Object chatType = getField(msgRecord, "chatType");
            if (!(chatType instanceof Integer) || (Integer) chatType != 1) {
                return;
            }

            Object senderUid = getField(msgRecord, "senderUid");
            if (senderUid == null) {
                return;
            }

            Object msgId = getField(msgRecord, "msgId");
            String key = String.valueOf(msgId);
            if (recentReplies.contains(key)) {
                return;
            }

            String content = extractTextContent(msgRecord);
            if (content == null || content.trim().equals(".")) {
                return;
            }

            recentReplies.add(key);
            if (recentReplies.size() > 200) {
                recentReplies.clear();
            }

            String peerUid = String.valueOf(getField(msgRecord, "peerUid"));
            dbg("reply to " + peerUid + ": " + content);
            reply(peerUid, ".");
        } catch (Throwable t) {
            dbg("handleMsg error: " + t);
        }
    }

    private String extractTextContent(Object msgRecord) {
        try {
            Object elements = getField(msgRecord, "elements");
            if (!(elements instanceof List)) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Object element : (List<Object>) elements) {
                Object textElement = getField(element, "textElement");
                if (textElement != null) {
                    Object content = getField(textElement, "content");
                    if (content != null) {
                        sb.append(content);
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getField(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private void reply(String peerUid, String text) {
        try {
            if (msgService == null || sendMsgMethod == null) {
                dbg("reply skipped, msgService=" + (msgService != null)
                        + " sendMsgMethod=" + (sendMsgMethod != null));
                return;
            }
            ClassLoader cl = hostCl != null ? hostCl : msgService.getClass().getClassLoader();

            Class<?> textElementClass = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.TextElement", false, cl);
            Object textElement = textElementClass.getDeclaredConstructor().newInstance();
            Method setContent = textElementClass.getDeclaredMethod("setContent", String.class);
            setContent.setAccessible(true);
            setContent.invoke(textElement, text);

            Class<?> msgElementClass = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.MsgElement", false, cl);
            Object msgElement = msgElementClass.getDeclaredConstructor().newInstance();
            Method setElementType = msgElementClass.getDeclaredMethod("setElementType", int.class);
            setElementType.setAccessible(true);
            setElementType.invoke(msgElement, 1);
            Method setTextElement = msgElementClass.getDeclaredMethod("setTextElement", textElementClass);
            setTextElement.setAccessible(true);
            setTextElement.invoke(msgElement, textElement);

            ArrayList<Object> elements = new ArrayList<>();
            elements.add(msgElement);

            Class<?> contactClass = Class.forName(CONTACT_CLASS, false, cl);
            Object contact = contactClass.getDeclaredConstructor(int.class, String.class, String.class)
                    .newInstance(1, peerUid, "");

            Class<?> callbackClass = Class.forName(CALLBACK_CLASS, false, cl);
            Object callback = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{callbackClass},
                    (proxy, method, args) -> {
                        if ("onResult".equals(method.getName())) {
                            dbg("reply onResult code=" + (args != null && args.length > 0 ? args[0] : "?")
                                    + " msg=" + (args != null && args.length > 1 ? args[1] : "?"));
                        }
                        return null;
                    });

            getInvoker(sendMsgMethod).invoke(msgService, 0L, contact, elements, new HashMap<>(), callback);
            dbg("reply sent to " + peerUid);
        } catch (Throwable t) {
            dbg("reply error: " + t);
        }
    }

    private void dbg(String msg) {
        try {
            if (logger != null) {
                logger.log(msg);
            }
        } catch (Throwable ignored) {
        }
    }
}
