package com.myclick.bridge

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** 标准 Xposed API 82 实现，LSPosed 在运行时提供这些类。 */
class XposedBridgeImpl : HookBridge {

    override fun findClass(name: String, loader: ClassLoader?): Class<*>? {
        // system_server 的 com.android.server.* 位于 SYSTEMSERVERCLASSPATH，
        // 用 boot classloader 找不到，必须优先用 LSPosed 传进来的 lpparam.classLoader。
        val candidates = listOfNotNull(
            loader,
            Thread.currentThread().contextClassLoader,
            javaClass.classLoader
        )
        for (l in candidates) {
            try {
                return Class.forName(name, false, l)
            } catch (t: Throwable) {
                // 换下一个
            }
        }
        return runCatching { XposedHelpers.findClass(name, loader) }.getOrNull()
    }

    override fun hook(
        clazz: Class<*>,
        methodName: String,
        phase: Phase,
        parameterTypes: Array<out Class<*>>,
        block: (MethodContext) -> Unit
    ) {
        val callback = callback(phase, block)
        val params = arrayOfNulls<Any>(parameterTypes.size + 1)
        for (i in parameterTypes.indices) params[i] = parameterTypes[i]
        params[parameterTypes.size] = callback
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, *params)
        } catch (t: Throwable) {
            // 签名不匹配时退化为按名字全量 Hook
            runCatching { XposedBridge.hookAllMethods(clazz, methodName, callback) }
            log("findAndHookMethod $clazz#$methodName 失败，已降级 hookAll: ${t.message}")
        }
    }

    override fun hookAll(
        clazz: Class<*>,
        methodName: String,
        phase: Phase,
        block: (MethodContext) -> Unit
    ) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, callback(phase, block))
        } catch (t: Throwable) {
            log(t)
        }
    }

    private fun callback(phase: Phase, block: (MethodContext) -> Unit): XC_MethodHook =
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (phase != Phase.BEFORE) return
                safeInvoke(param, block)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (phase != Phase.AFTER) return
                safeInvoke(param, block)
            }

            private fun safeInvoke(param: MethodHookParam, fn: (MethodContext) -> Unit) {
                try {
                    fn(MethodContext(param))
                } catch (t: Throwable) {
                    log(t)
                }
            }
        }

    override fun log(message: String) {
        try {
            XposedBridge.log("$TAG $message")
        } catch (t: Throwable) {
            android.util.Log.i(TAG, message)
        }
    }

    override fun log(throwable: Throwable) {
        try {
            XposedBridge.log(throwable)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, throwable)
        }
    }

    private companion object {
        const val TAG = "MYclick"
    }
}
