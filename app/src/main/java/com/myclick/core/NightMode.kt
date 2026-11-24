package com.myclick.core

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock

/**
 * 系统深浅色的实时判定。
 *
 * 判定顺序参考 EMOO 的 `isSystemInDarkTheme()`：**以当前有效的 Configuration 为准**
 * （它反映系统此刻真正在用的明暗），而不是 `UiModeManager.getNightMode()`。
 *
 * 为什么不能反过来：
 *  - `getNightMode()` 返回的是「用户选的档位」，不是「现在是不是深色」。ColorOS 上
 *    `dumpsys uimode` 里能看到 `mNightModeLocked=true`，这个值会被厂商主题锁死，
 *    跟系统实际明暗脱钩 —— 表现就是自动检测失败、恒为浅色。
 *  - `Configuration.UI_MODE_NIGHT_YES/NO` 是系统算完之后下发的最终结果，跟随系统、
 *    定时切换、厂商主题都能正确反映。
 *
 * 为什么不能只读 `context.resources.configuration`：
 *  - Shizuku 模式的绘制端是 shell 身份起的 `app_process`，没注册到 AMS，收不到
 *    `handleConfigurationChanged`，Configuration 停在进程启动那一刻 —— 之后切换
 *    深色模式永远反映不过来。所以这里优先向 AMS 要**全局**配置（隐藏 API，
 *    shell / system_server 里可用），拿不到才退回本进程配置。
 */
object NightMode {

    /** 短缓存：连点时不必每次点击都走一次 binder；500ms 内切换也能迅速跟上 */
    private const val CACHE_MS = 500L

    @Volatile
    private var cachedDark = false

    @Volatile
    private var cachedAt = 0L

    fun isDark(context: Context?): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (cachedAt != 0L && now - cachedAt < CACHE_MS) return cachedDark
        val dark = resolve(context)
        cachedDark = dark
        cachedAt = now
        return dark
    }

    /** 配置下发 / 设置页切换后主动失效，下一次点击立刻用新值 */
    fun invalidate() {
        cachedAt = 0L
    }

    private fun resolve(context: Context?): Boolean {
        val ctx = context ?: Contexts.any()
        // 1. 全局 Configuration：系统算好的最终明暗，shell 进程也能拿到实时值
        val globalUiMode = globalUiMode()
        if (globalUiMode != null) return isNight(globalUiMode)

        // 2. 本进程 Configuration（Activity 进程里同样是实时的）
        if (ctx != null) {
            runCatching { ctx.resources.configuration.uiMode }.getOrNull()?.let {
                if ((it and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_UNDEFINED) {
                    return isNight(it)
                }
            }
        }

        // 3. 最后才看档位设置（仅当系统没给明暗信息时兜底）
        val mode = runCatching {
            ctx?.getSystemService(UiModeManager::class.java)?.nightMode
        }.getOrNull()
        when (mode) {
            UiModeManager.MODE_NIGHT_YES -> return true
            UiModeManager.MODE_NIGHT_NO -> return false
        }

        // 4. 系统资源
        return runCatching { isNight(Resources.getSystem().configuration.uiMode) }.getOrDefault(false)
    }

    private fun isNight(uiMode: Int): Boolean =
        (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** 向 AMS 要「此刻」的全局 Configuration；shell 进程解过 hidden API 限制也能调 */
    private fun globalUiMode(): Int? = runCatching {
        val am = Class.forName("android.app.ActivityManager")
            .getMethod("getService").invoke(null)
        val config = am?.javaClass?.getMethod("getConfiguration")?.invoke(am) as? Configuration
        val uiMode = config?.uiMode ?: return@runCatching null
        // 未定义时不能当作「浅色」，交给后面的兜底
        if ((uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_UNDEFINED) null else uiMode
    }.getOrNull()
}
