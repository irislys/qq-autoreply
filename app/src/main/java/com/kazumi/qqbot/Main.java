package com.kazumi.qqbot;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

public class Main extends XposedModule {

    public static final String TAG = "KazumiQQBot";
    public static final String QQ_PACKAGE = "com.tencent.mobileqq";

    private Object msgService;
    private final Set<String> recentReplies = new HashSet<>();
    private final Set<String> hookedListenerClasses = new HashSet<>();

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        Log.i(TAG, "KazumiQQBot module loaded in process: " + param.getProcessName());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!QQ_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        Log.i(TAG, "QQ package ready, installing hooks...");
        ClassLoader cl = param.getClassLoader();
        try {
            installServiceHooks(cl);
            installListenerHooks(cl);
            Log.i(TAG, "All hooks installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install hooks", t);
        }
    }

    private void installServiceHooks(@NonNull ClassLoader cl) throws Throwable {
        Class<?> kernelService = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl", false, cl);
        Class<?> msgServiceClass = Class.forName("com.tencent.qqnt.kernel.api.impl.MsgService", false, cl);

        for (Method m : kernelService.getDeclaredMethods()) {
            if (m.getName().equals("getMsgService") && m.getParameterTypes().length == 0) {
                m.setAccessible(true);
                hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = null;
                    try {
                        result = chain.proceed();
                    } catch (Throwable t) {
                        Log.e(TAG, "getMsgService proceed failed", t);
                    }
                    if (result != null) {
                        msgService = result;
                        Log.i(TAG, "Captured MsgService instance: " + msgService.getClass().getName());
                    }
                    return result;
                });
                Log.i(TAG, "Hooked KernelServiceImpl.getMsgService");
                break;
            }
        }

        for (Method m : msgServiceClass.getDeclaredMethods()) {
            if (m.getName().equals("sendMsg")) {
                m.setAccessible(true);
                hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (msgService == null) {
                        msgService = chain.getThisObject();
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked MsgService.sendMsg: " + m.toGenericString());
                break;
            }
        }

        for (Method m : msgServiceClass.getDeclaredMethods()) {
            if (m.getName().equals("addMsgListener")) {
                m.setAccessible(true);
                hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result;
                    try {
                        result = chain.proceed();
                    } catch (Throwable t) {
                        Log.e(TAG, "addMsgListener proceed failed", t);
                        result = null;
                    }
                    if (msgService == null) {
                        msgService = chain.getThisObject();
                    }
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty() && args.get(0) != null) {
                        Object listener = args.get(0);
                        hookListenerClass(listener.getClass(), cl);
                    }
                    return result;
                });
                Log.i(TAG, "Hooked MsgService.addMsgListener");
                break;
            }
        }
    }

    private void installListenerHooks(@NonNull ClassLoader cl) throws Throwable {
        Class<?> listenerInterface = Class.forName("com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener", false, cl);
        for (Method m : listenerInterface.getDeclaredMethods()) {
            if (m.getName().equals("onRecvMsg")) {
                m.setAccessible(true);
                hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty() && args.get(0) != null) {
                        handleRecvMsgs(args.get(0));
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked IKernelMsgListener.onRecvMsg interface");
                break;
            }
        }
    }

    private void hookListenerClass(Class<?> clazz, ClassLoader cl) {
        try {
            String key = clazz.getName();
            if (!hookedListenerClasses.add(key)) {
                return;
            }
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                for (Method m : current.getDeclaredMethods()) {
                    if (m.getName().equals("onRecvMsg") && m.getParameterTypes().length == 1) {
                        m.setAccessible(true);
                        hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (!args.isEmpty() && args.get(0) != null) {
                                handleRecvMsgs(args.get(0));
                            }
                            return chain.proceed();
                        });
                        Log.i(TAG, "Hooked onRecvMsg on " + clazz.getName());
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook listener class " + clazz.getName(), t);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRecvMsgs(Object msgListObj) {
        try {
            if (!(msgListObj instanceof List)) {
                return;
            }
            List<Object> msgList = (List<Object>) msgListObj;
            for (Object msg : msgList) {
                handleMsg(msg);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error handling received messages", t);
        }
    }

    private void handleMsg(Object msgRecord) {
        try {
            Object chatType = getField(msgRecord, "chatType");
            int chatTypeInt = chatType instanceof Integer ? (Integer) chatType : -1;
            if (chatTypeInt != 1) {
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
            Log.i(TAG, "Private message from " + senderUid + ": " + content);

            if (content != null && content.trim().equals(".")) {
                Log.d(TAG, "Ignore message already our reply");
                return;
            }

            recentReplies.add(key);
            if (recentReplies.size() > 200) {
                recentReplies.clear();
            }

            String peerUid = String.valueOf(getField(msgRecord, "peerUid"));
            reply(peerUid, ".");
        } catch (Throwable t) {
            Log.e(TAG, "Error handling message", t);
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
        } catch (Throwable t) {
            Log.e(TAG, "Error extracting text content", t);
            return null;
        }
    }

    private Object getField(Object obj, String name) throws NoSuchFieldException, IllegalAccessException {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " in " + obj.getClass().getName());
    }

    private void reply(String peerUid, String text) {
        try {
            if (msgService == null) {
                Log.w(TAG, "msgService not available yet, cannot reply");
                return;
            }
            ClassLoader cl = msgService.getClass().getClassLoader();

            Class<?> textElementClass = Class.forName("com.tencent.qqnt.kernel.nativeinterface.TextElement", false, cl);
            Object textElement = textElementClass.getDeclaredConstructor().newInstance();
            Method setContent = textElementClass.getDeclaredMethod("setContent", String.class);
            setContent.setAccessible(true);
            setContent.invoke(textElement, text);

            Class<?> msgElementClass = Class.forName("com.tencent.qqnt.kernel.nativeinterface.MsgElement", false, cl);
            Object msgElement = msgElementClass.getDeclaredConstructor().newInstance();
            Method setElementType = msgElementClass.getDeclaredMethod("setElementType", int.class);
            setElementType.setAccessible(true);
            setElementType.invoke(msgElement, 1);
            Method setTextElement = msgElementClass.getDeclaredMethod("setTextElement", textElementClass);
            setTextElement.setAccessible(true);
            setTextElement.invoke(msgElement, textElement);

            ArrayList<Object> elements = new ArrayList<>();
            elements.add(msgElement);

            Class<?> contactClass = Class.forName("com.tencent.qqnt.kernelpublic.nativeinterface.Contact", false, cl);
            Object contact = contactClass.getDeclaredConstructor(int.class, String.class, String.class)
                    .newInstance(1, "", peerUid);

            Class<?> callbackClass = Class.forName("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback", false, cl);
            Object callback = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{callbackClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("onResult")) {
                            Log.i(TAG, "sendMsg onResult: code=" + (args != null && args.length > 0 ? args[0] : "?")
                                    + " msg=" + (args != null && args.length > 1 ? args[1] : "?"));
                        }
                        return null;
                    });

            Method sendMsg = msgService.getClass().getDeclaredMethod("sendMsg",
                    long.class, contactClass, ArrayList.class, HashMap.class, callbackClass);
            sendMsg.setAccessible(true);
            sendMsg.invoke(msgService, 0L, contact, elements, new HashMap<>(), callback);
            Log.i(TAG, "Auto-replied to " + peerUid + ": " + text);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to send reply", t);
        }
    }
}
