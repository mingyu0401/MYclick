package com.myclick.core

import android.content.Context

/**
 * 在任意被 Hook 进程里拿到一个可用 Context。
 * system_server 没有 Application，因此按优先级做多重反射回退。
 */
object Contexts {

    @Volatile
    private var captured: Context? = null

    fun offer(context: Context) {
        captured = try {
            context.applicationContext ?: context
        } catch (t: Throwable) {
            context
        }
    }

    fun any(): Context? = captured ?: resolveSystemContext()

    private fun resolveSystemContext(): Context? {
        val attempts = listOf(
            { reflectStatic("android.app.AppGlobals", "getInitialApplication") },
            { reflectStatic("android.app.ActivityThread", "currentApplication") },
            {
                val at = Class.forName("android.app.ActivityThread")
                val thread = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
                if (thread == null) null else {
                    at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread)
                }
            }
        )
        for (attempt in attempts) {
            try {
                val c = attempt() as? Context
                if (c != null) return c
            } catch (t: Throwable) {
                // 继续下一个
            }
        }
        return null
    }

    private fun reflectStatic(clazzName: String, method: String): Any? {
        val clazz = Class.forName(clazzName)
        return clazz.getDeclaredMethod(method).apply { isAccessible = true }.invoke(null)
    }
}
