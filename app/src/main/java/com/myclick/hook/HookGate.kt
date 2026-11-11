package com.myclick.hook

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程级一次性门闩。
 *
 * 真机（LSPosed 2.2.1 / Android 17）实测：system_server 里 LSPosed 会为它加载的
 * 每一个 package context 都回调一次 handleLoadPackage，日志里能看到
 * com.android.providers.settings / com.android.server.telecom / com.oplus.athena /
 * com.android.location.fused 等回调全部发生在 (system) 进程。
 * 如果每次回调都装一遍 InputEventReceiver Hook，一次点击会被同一进程里的
 * 多个 Hook 实例重复广播（实测一次 ACTION_DOWN 出现 8 条日志）。
 *
 * 进程名不用 lpparam.processName：该字段在不同 LSPosed 版本 / 不同回调里
 * 可能是包名而不是进程名（真机上就出现过），改读 /proc/self/cmdline 最可靠，
 * 且不依赖任何 hidden API。
 */
object HookGate {

    private val flags = AtomicInteger(0)

    const val COMMON = 1
    const val FRAMEWORK = 1 shl 1
    const val APP_INPUT = 1 shl 2
    const val RECORDER = 1 shl 3

    /** 原子占位：同一 bit 在一个进程内只会有一个调用者拿到 true。 */
    fun claim(flag: Int): Boolean = (flags.getAndUpdate { it or flag } and flag) == 0

    val processName: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            File("/proc/self/cmdline").readBytes()
                .toString(Charsets.ISO_8859_1)
                .substringBefore('\u0000')
                .trim()
        }.getOrDefault("")
    }

    val isSystemServer: Boolean
        get() {
            val p = processName
            return p == "system_server" || p == "system" ||
                p.startsWith("system_server:") || p.startsWith("system:")
        }
}
