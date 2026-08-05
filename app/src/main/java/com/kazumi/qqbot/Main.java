package com.kazumi.qqbot;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

public class Main extends XposedModule {

    private static final String QQ_PACKAGE = "com.tencent.mobileqq";

    private static final String KERNEL_SERVICE = "com.tencent.qqnt.kernel.api.impl.KernelServiceImpl";
    private static final String MSG_SERVICE = "com.tencent.qqnt.kernel.api.impl.MsgService";
    private static final String MSG_LISTENER_WRAPPER = "com.tencent.qqnt.kernel.api.impl.mk";

    private static final String TAG = "KazumiQQBot";

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
        dbg("package ready, installing hooks");
        ClassLoader cl = param.getClassLoader();
        try {
            hookMsgService(cl);
            dbg("msgService hooks done");
        } catch (Throwable t) {
            dbg("msgService hooks failed: " + t);
        }
        try {
            hookListenerWrapper(cl);
            dbg("listener wrapper hooks done");
        } catch (Throwable t) {
            dbg("listener wrapper hooks failed: " + t);
        }
    }

    private void hookMsgService(@NonNull ClassLoader cl) throws Throwable {
        Class<?> kernelService = Class.forName(KERNEL_SERVICE, false, cl);
        Class<?> msgServiceClass = Class.forName(MSG_SERVICE, false, cl);

        Method getMsgService = findMethod(kernelService, "getMsgService");
        if (getMsgService != null) {
            getMsgService.setAccessible(true);
            hook(getMsgService).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result;
                try {
                    result = chain.proceed();
                } catch (Throwable ignored) {
                    result = null;
                }
                if (result != null) {
                    msgService = result;
                    cacheSendMsgMethod();
                    dbg("captured msgService " + result.getClass().getName());
                }
                return result;
            });
            dbg("hooked KernelServiceImpl.getMsgService");
        } else {
            dbg("getMsgService method not found");
        }

        Method sendMsg = findMethod(msgServiceClass, "sendMsg");
        if (sendMsg != null) {
            sendMsg.setAccessible(true);
            hook(sendMsg).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (msgService == null) {
                    msgService = chain.getThisObject();
                    cacheSendMsgMethod();
                    dbg("captured msgService via sendMsg " + msgService.getClass().getName());
                }
                try {
                    List<Object> args = chain.getArgs();
                    if (args.size() >= 2 && args.get(1) != null) {
                        Object peer = args.get(1);
                        dbg("sendMsg peer=" + peer);
                        dbg("sendMsg elements=" + (args.size() >= 3 ? String.valueOf(args.get(2)) : "?"));
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            dbg("hooked MsgService.sendMsg");
        } else {
            dbg("sendMsg method not found");
        }
    }

    private void hookListenerWrapper(@NonNull ClassLoader cl) throws Throwable {
        Class<?> wrapper;
        try {
            wrapper = Class.forName(MSG_LISTENER_WRAPPER, false, cl);
        } catch (Throwable t) {
            dbg("wrapper class not found: " + t);
            return;
        }
        dbg("wrapper class loaded: " + wrapper.getName());
        Method onRecvMsg = findSingleArgMethod(wrapper, "onRecvMsg");
        if (onRecvMsg == null) {
            dbg("wrapper onRecvMsg not found, methods: " + java.util.Arrays.toString(wrapper.getDeclaredMethods()));
            return;
        }
        dbg("wrapper onRecvMsg found: " + onRecvMsg.toGenericString());
        onRecvMsg.setAccessible(true);
        hook(onRecvMsg).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            dbg("onRecvMsg intercepted");
            List<Object> args = chain.getArgs();
            if (!args.isEmpty() && args.get(0) != null) {
                dbg("onRecvMsg args[0]=" + args.get(0).getClass().getName() + " size=" + (args.get(0) instanceof List ? ((List<?>) args.get(0)).size() : "?"));
                handleRecvMsgs(args.get(0));
            } else {
                dbg("onRecvMsg empty args");
            }
            return chain.proceed();
        });
        dbg("hooked wrapper onRecvMsg");
    }

    private void cacheSendMsgMethod() {
        try {
            if (sendMsgMethod == null && msgService != null) {
                ClassLoader cl = msgService.getClass().getClassLoader();
                Class<?> contactClass = Class.forName("com.tencent.qqnt.kernelpublic.nativeinterface.Contact", false, cl);
                Class<?> callbackClass = Class.forName("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback", false, cl);
                sendMsgMethod = msgService.getClass().getDeclaredMethod("sendMsg",
                        long.class, contactClass, ArrayList.class, HashMap.class, callbackClass);
                sendMsgMethod.setAccessible(true);
                dbg("cached sendMsg method");
            }
        } catch (Throwable t) {
            dbg("cache sendMsg failed: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRecvMsgs(Object msgListObj) {
        try {
            if (!(msgListObj instanceof List)) {
                dbg("recv msgs not a list: " + msgListObj.getClass().getName());
                return;
            }
            List<Object> msgList = (List<Object>) msgListObj;
            dbg("recv msgs size=" + msgList.size());
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
                dbg("skip msg, chatType=" + chatType);
                return;
            }

            Object senderUid = getField(msgRecord, "senderUid");
            if (senderUid == null) {
                dbg("skip msg, no senderUid");
                return;
            }

            Object msgId = getField(msgRecord, "msgId");
            String key = String.valueOf(msgId);
            if (recentReplies.contains(key)) {
                dbg("skip duplicate msg " + key);
                return;
            }

            String content = extractTextContent(msgRecord);
            dbg("private msg from " + senderUid + ": " + content);
            if (content == null || content.trim().equals(".")) {
                dbg("skip reply content");
                return;
            }

            recentReplies.add(key);
            if (recentReplies.size() > 200) {
                recentReplies.clear();
            }

            String peerUid = String.valueOf(getField(msgRecord, "peerUid"));
            dbg("replying to " + peerUid + ": " + content);
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
                java.lang.reflect.Field f = c.getDeclaredField(name);
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
                dbg("reply skipped, msgService=" + (msgService != null) + " sendMsgMethod=" + (sendMsgMethod != null));
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
                    .newInstance(1, peerUid, "");
            dbg("reply contact=" + contact);

            Class<?> callbackClass = Class.forName("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback", false, cl);
            Object callback = java.lang.reflect.Proxy.newProxyInstance(cl,
                    new Class[]{callbackClass},
                    (proxy, method, args) -> {
                        if ("onResult".equals(method.getName())) {
                            dbg("reply onResult code=" + (args != null && args.length > 0 ? args[0] : "?")
                                    + " msg=" + (args != null && args.length > 1 ? args[1] : "?"));
                        }
                        return null;
                    });

            sendMsgMethod.invoke(msgService, 0L, contact, elements, new HashMap<>(), callback);
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

    private static Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    private static Method findSingleArgMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 1) {
                return m;
            }
        }
        return null;
    }
}
