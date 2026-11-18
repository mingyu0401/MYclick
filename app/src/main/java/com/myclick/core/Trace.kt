package com.myclick.core

import android.content.Context
import java.io.File

/**
 * 轻量落盘诊断。
 *
 * ColorOS 会过滤普通 App 进程的 `android.util.Log`（I 级几乎全丢），
 * 无障碍模式又没有 system_server 里的 XposedBridge 日志通道可用，
 * 所以这里直接写 App 私有目录，排障时用 `run-as com.myclick cat files/fx.log` 读取。
 *
 * 只在「纯悬浮窗模式」这条链路上调用，写入量极小（每次点击 1~2 行），
 * 超过 [MAX_BYTES] 就清空重来，不会无限增长。
 */
object Trace {

    private const val FILE_NAME = "fx.log"
    private const val MAX_BYTES = 64 * 1024

    @Volatile
    private var enabled = true

    fun off() {
        enabled = false
    }

    fun log(context: Context?, tag: String, msg: String) {
        if (!enabled || context == null) return
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
            val line = "${System.currentTimeMillis()} [$tag] $msg\n"
            f.appendText(line)
        }
        // 同时尝试 logcat（某些 ROM 不过滤，能看到更实时）
        runCatching { android.util.Log.i("MYclick", "[$tag] $msg") }
    }
}
