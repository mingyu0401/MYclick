package com.myclick.shell

import android.os.Process
import java.io.File

/**
 * 绘制进程的 pid 文件（`/data/local/tmp/myclick-fx.pid`）。
 *
 * 用途只有一个：App 被系统杀掉、绘制进程还在独立跑的情况下，下一次打开 App 时
 * 要先把这个残留实例杀掉再起新的，否则会同时存在两个绘制进程、特效叠成两份。
 *
 * 之所以放 `/data/local/tmp`：App 进程读不到自己的私有目录之外，而这里 shell 身份
 * 可读可写，App 侧通过 Shizuku 执行 `cat` / `kill` 也能操作。
 */
object ShellPidFile {

    const val PATH = "/data/local/tmp/myclick-fx.pid"

    fun write() {
        runCatching { File(PATH).writeText(Process.myPid().toString()) }
    }

    fun clear() {
        runCatching { File(PATH).delete() }
    }
}
