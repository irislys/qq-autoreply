package com.tff.qq;

import androidx.annotation.NonNull;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

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

    static {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable ignored) {
        }
    }

    private static final String QQ_PACKAGE = "com.tencent.mobileqq";
    private static final String TAG = "TFFQQBot";

    private static final String CONTACT_CLASS = "com.tencent.qqnt.kernelpublic.nativeinterface.Contact";
    private static final String CALLBACK_CLASS = "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";

    private static final String ID_LOAD_CLASS = "loadClass";
    private static final String ID_GET_MSG_SERVICE = "getMsgService";
    private static final String ID_SEND_MSG = "sendMsg";
    private static final String ID_ON_RECV_MSG = "onRecvMsg";

    private volatile ClassLoader hostCl;
    private volatile String hostAppPath;
    private volatile Thread resolveThread;
    private volatile boolean resolvingDone;
    private volatile boolean installing;

    private volatile String kernelServiceName;
    private volatile String msgServiceImplName;
    private volatile String sendMsgName;
    private volatile String[] sendMsgParamTypes;
    private volatile String listenerWrapperName;
    private volatile String listenerParamType;

    private final Set<String> installed = new HashSet<>();
    private Map<Executable, XposedInterface.HookHandle> oldHandles;

    private Object msgService;
    private Method sendMsgMethod;
    private final Set<String> recentReplies = new HashSet<>();

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        dbg("module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!QQ_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        dbg("package ready, starting dynamic resolution");
        hostCl = param.getClassLoader();
        hostAppPath = param.getApplicationInfo().sourceDir;
        hookClassLoaderLoad();
        startResolution();
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        Thread t = resolveThread;
        if (t != null) {
            t.interrupt();
        }
        if (hostCl == null) {
            dbg("hot reload rejected: no classloader state");
            return false;
        }
        try {
            param.setSavedInstanceState(hostCl);
        } catch (Throwable th) {
            dbg("hot reload rejected: save state failed " + th);
            return false;
        }
        dbg("hot reload allowed");
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        Object state = param.getSavedInstanceState();
        if (!(state instanceof ClassLoader)) {
            dbg("hot reloaded but classloader state lost, hooks not reinstalled");
            return;
        }
        dbg("hot reloaded, reinstalling hooks");
        hostCl = (ClassLoader) state;
        hostAppPath = null;
        oldHandles = new HashMap<>();
        for (XposedInterface.HookHandle h : param.getOldHookHandles()) {
            try {
                oldHandles.put(h.getExecutable(), h);
            } catch (Throwable ignored) {
            }
        }
        hookClassLoaderLoad();
        startResolution();
    }

    // ------------------------------------------------------------------
    // dynamic resolution
    // ------------------------------------------------------------------

    private void startResolution() {
        if (resolveThread != null) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                resolveAll();
            } catch (Throwable th) {
                dbg("resolution error: " + th);
            } finally {
                resolvingDone = true;
                resolveThread = null;
                installTargets();
            }
        }, "TFFResolve");
        t.setDaemon(true);
        resolveThread = t;
        t.start();
    }

    private void resolveAll() throws Throwable {
        ClassLoader cl = hostCl;
        try (DexKitBridge bridge = hostAppPath != null
                ? DexKitBridge.create(hostAppPath)
                : DexKitBridge.create(cl, true)) {
            resolveKernel(bridge);
            resolveListener(bridge);
        }
        dbg("resolution done: kernel=" + kernelServiceName + " msgService=" + msgServiceImplName
                + " sendMsg=" + sendMsgName + " listener=" + listenerWrapperName);
    }

    private void resolveKernel(DexKitBridge bridge) {
        MethodDataList methods = bridge.findClass(FindClass.create()
                        .searchPackages("com.tencent.qqnt")
                        .matcher(ClassMatcher.create()))
                .findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().name("getMsgService").paramCount(0)));
        MethodData getMsgService = null;
        for (MethodData m : methods) {
            String ret = m.getReturnTypeName();
            if (ret != null && ret.contains("MsgService")) {
                getMsgService = m;
                break;
            }
        }
        if (getMsgService == null) {
            getMsgService = methods.firstOrNull();
        }
        if (getMsgService == null) {
            return;
        }
        kernelServiceName = getMsgService.getDeclaredClassName();
        msgServiceImplName = getMsgService.getReturnTypeName();
        if (msgServiceImplName == null) {
            return;
        }
        MethodDataList sendList = bridge.findClass(FindClass.create()
                        .searchPackages("com.tencent.qqnt")
                        .matcher(ClassMatcher.create()))
                .findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .paramCount(5)
                                .paramTypes("long",
                                        CONTACT_CLASS,
                                        "java.util.ArrayList",
                                        "java.util.HashMap",
                                        CALLBACK_CLASS)));
        MethodData send = null;
        for (MethodData m : sendList) {
            if (msgServiceImplName.equals(m.getDeclaredClassName())) {
                send = m;
                break;
            }
        }
        if (send == null) {
            for (MethodData m : sendList) {
                String dc = m.getDeclaredClassName();
                if (dc != null && dc.contains("MsgService")) {
                    send = m;
                    break;
                }
            }
        }
        if (send == null) {
            send = sendList.firstOrNull();
        }
        if (send != null) {
            sendMsgName = send.getMethodName();
            List<String> pts = send.getParamTypeNames();
            sendMsgParamTypes = pts == null ? null : pts.toArray(new String[0]);
        }
    }

    private void resolveListener(DexKitBridge bridge) {
        MethodDataList methods = bridge.findClass(FindClass.create()
                        .searchPackages("com.tencent.qqnt")
                        .matcher(ClassMatcher.create()))
                .findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().name("onRecvMsg").paramCount(1)));
        for (MethodData m : methods) {
            List<String> pts = m.getParamTypeNames();
            if (pts != null && !pts.isEmpty() && "java.util.List".equals(pts.get(0))) {
                listenerWrapperName = m.getDeclaredClassName();
                listenerParamType = pts.get(0);
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // lazy hook installation driven by ClassLoader.loadClass
    // ------------------------------------------------------------------

    private void hookClassLoaderLoad() {
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            loadClass.setAccessible(true);
            installOrReplace(ID_LOAD_CLASS, loadClass, chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof String && isTargetClass((String) arg)) {
                        installTargets();
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

    private boolean isTargetClass(String name) {
        return name.equals(kernelServiceName)
                || name.equals(msgServiceImplName)
                || name.equals(listenerWrapperName);
    }

    private synchronized void installTargets() {
        if (!resolvingDone || hostCl == null || installing) {
            return;
        }
        installing = true;
        try {
            installGetMsgServiceHook();
            installSendMsgHook();
            installListenerHook();
        } finally {
            installing = false;
        }
    }

    private void installGetMsgServiceHook() {
        if (installed.contains(ID_GET_MSG_SERVICE) || kernelServiceName == null) {
            return;
        }
        try {
            Class<?> kernelService = Class.forName(kernelServiceName, false, hostCl);
            Method m = kernelService.getDeclaredMethod("getMsgService");
            m.setAccessible(true);
            installOrReplace(ID_GET_MSG_SERVICE, m, this::hookGetMsgService);
            installed.add(ID_GET_MSG_SERVICE);
            dbg("hooked " + kernelServiceName + ".getMsgService");
        } catch (Throwable t) {
            dbg("getMsgService hook pending: " + t);
        }
    }

    private void installSendMsgHook() {
        if (installed.contains(ID_SEND_MSG) || msgServiceImplName == null || sendMsgName == null) {
            return;
        }
        try {
            Class<?> msgServiceClass = Class.forName(msgServiceImplName, false, hostCl);
            Class<?>[] paramTypes = resolveParamTypes(sendMsgParamTypes);
            Method m = msgServiceClass.getDeclaredMethod(sendMsgName, paramTypes);
            m.setAccessible(true);
            sendMsgMethod = m;
            installOrReplace(ID_SEND_MSG, m, this::hookSendMsg);
            installed.add(ID_SEND_MSG);
            dbg("hooked " + msgServiceImplName + "." + sendMsgName);
        } catch (Throwable t) {
            dbg("sendMsg hook pending: " + t);
        }
    }

    private void installListenerHook() {
        if (installed.contains(ID_ON_RECV_MSG) || listenerWrapperName == null) {
            return;
        }
        try {
            Class<?> wrapper = Class.forName(listenerWrapperName, false, hostCl);
            Class<?>[] paramTypes = resolveParamTypes(new String[]{listenerParamType});
            Method m = wrapper.getDeclaredMethod("onRecvMsg", paramTypes);
            m.setAccessible(true);
            installOrReplace(ID_ON_RECV_MSG, m, this::hookOnRecvMsg);
            installed.add(ID_ON_RECV_MSG);
            dbg("hooked " + listenerWrapperName + ".onRecvMsg");
        } catch (Throwable t) {
            dbg("listener hook pending: " + t);
        }
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

    private Class<?>[] resolveParamTypes(String[] typeNames) throws ClassNotFoundException {
        if (typeNames == null) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            types[i] = typeForName(typeNames[i]);
        }
        return types;
    }

    private Class<?> typeForName(String name) throws ClassNotFoundException {
        switch (name) {
            case "void":
                return void.class;
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            case "char":
                return char.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            default:
                return Class.forName(name, false, hostCl);
        }
    }

    // ------------------------------------------------------------------
    // hookers
    // ------------------------------------------------------------------

    private Object hookGetMsgService(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (result != null) {
            msgService = result;
            dbg("captured msgService " + result.getClass().getName());
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
            log(4, TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
