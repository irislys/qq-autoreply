package com.tff.qq;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.PathClassLoader;

/**
 * Loads the DexKit classes through a PathClassLoader pointing at the module
 * APK, so the native libdexkit.so is mmap'd straight out of the APK
 * (stored/uncompressed) instead of being extracted to disk. All DexKit
 * interactions are reflection based to avoid duplicate-class issues.
 */
public class DexResolver implements AutoCloseable {

    public String kernelServiceName;
    public String msgServiceImplName;
    public String sendMsgName;
    public String[] sendMsgParamTypes;
    public String listenerWrapperName;
    public String listenerParamType;

    private final ClassLoader loader;
    private final Object bridge;
    private final Class<?> clsFindClass;
    private final Class<?> clsFindMethod;
    private final Class<?> clsClassMatcher;
    private final Class<?> clsMethodMatcher;
    private final Method mFindClass;
    private final Method mFindMethod;
    private final Method mGetDeclaredClassName;
    private final Method mGetReturnTypeName;
    private final Method mGetMethodName;
    private final Method mGetParamTypeNames;
    private final Method mFirstOrNull;

    private DexResolver(ClassLoader loader, Object bridge,
                        Class<?> clsFindClass, Class<?> clsFindMethod,
                        Class<?> clsClassMatcher, Class<?> clsMethodMatcher,
                        Method mFindClass, Method mFindMethod,
                        Method mGetDeclaredClassName, Method mGetReturnTypeName,
                        Method mGetMethodName, Method mGetParamTypeNames,
                        Method mFirstOrNull) {
        this.loader = loader;
        this.bridge = bridge;
        this.clsFindClass = clsFindClass;
        this.clsFindMethod = clsFindMethod;
        this.clsClassMatcher = clsClassMatcher;
        this.clsMethodMatcher = clsMethodMatcher;
        this.mFindClass = mFindClass;
        this.mFindMethod = mFindMethod;
        this.mGetDeclaredClassName = mGetDeclaredClassName;
        this.mGetReturnTypeName = mGetReturnTypeName;
        this.mGetMethodName = mGetMethodName;
        this.mGetParamTypeNames = mGetParamTypeNames;
        this.mFirstOrNull = mFirstOrNull;
    }

    /**
     * Creates the resolver. The module APK is used to load the DexKit Java
     * classes (initialize=true triggers System.loadLibrary("dexkit"), which
     * resolves through this PathClassLoader directly against the APK - no file
     * extraction). The host APK is the actual dex parsing target.
     */
    public static DexResolver create(String moduleApkPath, String hostApkPath) throws Exception {
        ClassLoader loader = new PathClassLoader(moduleApkPath,
                DexResolver.class.getClassLoader().getParent());
        Class<?> bridgeClass = Class.forName("org.luckypray.dexkit.DexKitBridge", true, loader);
        Object bridge = bridgeClass.getMethod("create", String.class).invoke(null, hostApkPath);

        Class<?> clsFindClass = Class.forName("org.luckypray.dexkit.query.FindClass", true, loader);
        Class<?> clsFindMethod = Class.forName("org.luckypray.dexkit.query.FindMethod", true, loader);
        Class<?> clsClassMatcher = Class.forName("org.luckypray.dexkit.query.matchers.ClassMatcher", true, loader);
        Class<?> clsMethodMatcher = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher", true, loader);

        Method mFindClass = bridgeClass.getMethod("findClass", clsFindClass);
        Method mFindMethod = Class.forName("org.luckypray.dexkit.result.ClassDataList", true, loader)
                .getMethod("findMethod", clsFindMethod);
        Method mGetDeclaredClassName = Class.forName("org.luckypray.dexkit.result.MethodData", true, loader)
                .getMethod("getDeclaredClassName");
        Method mGetReturnTypeName = Class.forName("org.luckypray.dexkit.result.MethodData", true, loader)
                .getMethod("getReturnTypeName");
        Method mGetMethodName = Class.forName("org.luckypray.dexkit.result.MethodData", true, loader)
                .getMethod("getMethodName");
        Method mGetParamTypeNames = Class.forName("org.luckypray.dexkit.result.MethodData", true, loader)
                .getMethod("getParamTypeNames");
        Method mFirstOrNull = Class.forName("org.luckypray.dexkit.result.MethodDataList", true, loader)
                .getMethod("firstOrNull");

        return new DexResolver(loader, bridge, clsFindClass, clsFindMethod,
                clsClassMatcher, clsMethodMatcher, mFindClass, mFindMethod,
                mGetDeclaredClassName, mGetReturnTypeName, mGetMethodName,
                mGetParamTypeNames, mFirstOrNull);
    }

    public void resolve() throws Exception {
        resolveKernel();
        resolveListener();
    }

    private void resolveKernel() throws Exception {
        List<Object> getList = findMethods("getMsgService", 0, null);
        Object getMsgService = null;
        for (Object m : getList) {
            String ret = (String) mGetReturnTypeName.invoke(m);
            if (ret != null && ret.contains("MsgService")) {
                getMsgService = m;
                break;
            }
        }
        if (getMsgService == null && !getList.isEmpty()) {
            getMsgService = getList.get(0);
        }
        if (getMsgService == null) {
            return;
        }
        kernelServiceName = (String) mGetDeclaredClassName.invoke(getMsgService);
        msgServiceImplName = (String) mGetReturnTypeName.invoke(getMsgService);
        if (msgServiceImplName == null) {
            return;
        }
        List<Object> sendList = findMethods(null, 5, new String[]{
                "long",
                "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
                "java.util.ArrayList",
                "java.util.HashMap",
                "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback"
        });
        Object send = null;
        for (Object m : sendList) {
            if (msgServiceImplName.equals(mGetDeclaredClassName.invoke(m))) {
                send = m;
                break;
            }
        }
        if (send == null) {
            for (Object m : sendList) {
                String dc = (String) mGetDeclaredClassName.invoke(m);
                if (dc != null && dc.contains("MsgService")) {
                    send = m;
                    break;
                }
            }
        }
        if (send == null && !sendList.isEmpty()) {
            send = sendList.get(0);
        }
        if (send != null) {
            sendMsgName = (String) mGetMethodName.invoke(send);
            Object pts = mGetParamTypeNames.invoke(send);
            if (pts instanceof List) {
                List<?> list = (List<?>) pts;
                sendMsgParamTypes = list.toArray(new String[0]);
            }
        }
    }

    private void resolveListener() throws Exception {
        List<Object> methods = findMethods("onRecvMsg", 1, null);
        for (Object m : methods) {
            Object pts = mGetParamTypeNames.invoke(m);
            if (pts instanceof List && !((List<?>) pts).isEmpty()
                    && "java.util.List".equals(((List<?>) pts).get(0))) {
                listenerWrapperName = (String) mGetDeclaredClassName.invoke(m);
                listenerParamType = "java.util.List";
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> findMethods(String name, int paramCount, String[] paramTypes) throws Exception {
        Object findClass = clsFindClass.getMethod("create").invoke(null);
        findClass = findClass.getClass().getMethod("searchPackages", String[].class)
                .invoke(findClass, (Object) new String[]{"com.tencent.qqnt"});
        Object classMatcher = clsClassMatcher.getMethod("create").invoke(null);
        findClass = findClass.getClass().getMethod("matcher", clsClassMatcher)
                .invoke(findClass, classMatcher);

        Object classDataList = mFindClass.invoke(bridge, findClass);

        Object findMethod = clsFindMethod.getMethod("create").invoke(null);
        Object methodMatcher = clsMethodMatcher.getMethod("create").invoke(null);
        if (name != null) {
            methodMatcher = clsMethodMatcher.getMethod("name", String.class).invoke(methodMatcher, name);
        }
        if (paramCount >= 0) {
            methodMatcher = clsMethodMatcher.getMethod("paramCount", int.class).invoke(methodMatcher, paramCount);
        }
        if (paramTypes != null) {
            methodMatcher = clsMethodMatcher.getMethod("paramTypes", String[].class)
                    .invoke(methodMatcher, (Object) paramTypes);
        }
        findMethod = findMethod.getClass().getMethod("matcher", clsMethodMatcher)
                .invoke(findMethod, methodMatcher);

        Object methodDataList = mFindMethod.invoke(classDataList, findMethod);
        return new ArrayList<>((List<Object>) methodDataList);
    }

    @Override
    public void close() {
        try {
            bridge.getClass().getMethod("close").invoke(bridge);
        } catch (Throwable t) {
            Log.e("TFFQQBot", "dex resolver close failed: " + t);
        }
    }
}
