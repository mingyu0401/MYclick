package com.myclick.core

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * 可选 Shizuku 通道（无编译期依赖，全部反射 / ContentProvider 探测）。
 *
 * 说明：本工程不捆绑 shizuku-api AAR。若设备上装了 Shizuku 且授权给本模块，
 * 我们会先尝试通过 ShizukuProvider 拿 Binder 执行 appops；拿不到就自动回退到
 * RootHelper → 纯 Xposed。三条路径互相独立，任意一条可用即可。
 */
object ShizukuBridge {

    data class Status(val available: Boolean, val message: String)

    fun probe(context: Context): Status {
        // 1) 类路径探测（若使用方自行引入 shizuku-api，这条会命中）
        val byClass = runCatching {
            Class.forName("moe.shizuku.api.Shizuku")
            "shizuku-api 在类路径中"
        }.getOrNull()
        if (byClass != null) return Status(true, byClass)

        // 2) Provider 探测（Shizuku 通过 exported provider 派发 Binder）
        val byProvider = runCatching {
            val uri = Uri.parse("content://moe.shizuku.api.ShizukuProvider")
            val b: Bundle? = context.contentResolver.call(uri, "send_binder", null, Bundle())
            if (b != null) "ShizukuProvider 可用：${b.keySet().joinToString()}" else null
        }.getOrNull()
        if (byProvider != null) return Status(true, byProvider)

        return Status(false, "未检测到 Shizuku（已自动回退 root / 纯 Xposed）")
    }

    /**
     * 尝试用 Shizuku 执行 appops。需要 shizuku-api 在类路径里；
     * 缺失时返回 null，调用方继续走 root。
     */
    fun grantOverlayViaShizuku(packageName: String): String? {
        return try {
            val clazz = Class.forName("moe.shizuku.api.Shizuku")
            val m = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            val p = m.invoke(
                null,
                arrayOf("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"),
                null,
                null
            )
            if (p is Process) {
                val text = p.inputStream.bufferedReader().use { it.readText() }.trim()
                p.waitFor()
                text
            } else null
        } catch (t: Throwable) {
            null
        }
    }
}
