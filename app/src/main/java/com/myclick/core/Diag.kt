package com.myclick.core

/**
 * 限流诊断输出。ColorOS 会过滤普通 App 进程的 I 级 logcat，
 * 所以优先走 XposedBridge.log（LSPosed 模块日志一定能看到）。
 */
object Diag {
    @Volatile
    private var count = 0

    fun log(tag: String, msg: String) {
        if (count++ > 80) return
        val line = "MYclick [$tag] $msg"
        runCatching {
            val xb = Class.forName("de.robv.android.xposed.XposedBridge")
            xb.getMethod("log", String::class.java).invoke(null, line)
        }.onFailure { android.util.Log.i("MYclick", "[$tag] $msg") }
    }
}
