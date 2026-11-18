package com.myclick.shell;

import java.lang.reflect.Method;

/**
 * 解除 hidden API 限制。
 *
 * SurfaceControl.Transaction 的 setLayerStack / setMatrix 都是 @hide，不解除限制时
 * 反射拿不到。手法是社区通用的「两次反射拿 VMRuntime，再 setHiddenApiExemptions("L")」。
 *
 * 用 Java 写而不是 Kotlin：这些调用全是 `Class.getDeclaredMethod(String, Class<?>...)`
 * 的二次反射，Kotlin 的 vararg / spread 语义在这里反复把 Class[] 当成单个 Class 传进去，
 * 直接写 Java 更不容易出错。
 */
public final class HiddenApi {

    private HiddenApi() {
    }

    public static void unlock() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class
                    .getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");

            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});

            Object instance = getRuntime.invoke(null);
            // "L" 表示放行所有隐藏 API
            setExemptions.invoke(instance, new Object[]{new String[]{"L"}});
            System.out.println("FX hidden-api 已解除限制");
        } catch (Throwable t) {
            System.out.println("FX hidden-api 解除失败: " + t);
        }
    }
}
