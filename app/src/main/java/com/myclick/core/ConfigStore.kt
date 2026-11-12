package com.myclick.core

import android.content.Context
import android.os.Bundle
import kotlin.math.abs

/**
 * 被 Hook 进程里的配置快照。三级回退，保证 system_server 也能拿到配置：
 *
 *  1) ConfigProvider.call()   —— 主路径，任意持有 Context 的进程可用
 *  2) CONFIG_PUSH 广播        —— 设置页 / 开机接收器主动推给 system_server
 *  3) XSharedPreferences      —— 老式通道，LSPosed 兼容实现
 *  4) 内置默认值              —— 最坏情况下仍能按默认包名工作
 *
 * 全部结果缓存在内存；渲染热路径只读快照，不做 IPC。
 */
object ConfigStore {

    @Volatile
    var value: Config = Config.default()
        private set

    @Volatile
    private var lastRefreshMs: Long = 0L

    /** 配置刷新完成后的回调（由被 Hook 进程注册，用来更新覆盖层） */
    @Volatile
    var onUpdated: ((Config) -> Unit)? = null

    private const val REFRESH_INTERVAL_MS = 5_000L

    @Volatile
    private var logCount = 0

    /**
     * 限流诊断日志。优先写 XposedBridge.log（LSPosed 模块日志一定能看到）；
     * 部分 ColorOS 会过滤普通 App 进程的 I 级 logcat，故 Log.i 只作兜底。
     */
    private fun debugLog(msg: String) {
        if (logCount++ > 8) return
        val line = "MYclick [config] $msg"
        runCatching {
            val xb = Class.forName("de.robv.android.xposed.XposedBridge")
            xb.getMethod("log", String::class.java).invoke(null, line)
        }.onFailure { android.util.Log.i("MYclick", line) }
    }

    /** 异步拉取，避免 system_server / App 启动主线程被 Binder 调用拖住 */
    fun refreshAsync(context: Context?) {
        Thread({
            runCatching { refresh(context) }
            runCatching { onUpdated?.invoke(value) }
        }, "MYclick-ConfigRefresh").apply {
            isDaemon = true
            start()
        }
    }

    fun applyPush(config: Config) {
        value = config
        lastRefreshMs = System.currentTimeMillis()
        debugLog("push scale=${config.scale} gate=${config.gateMode} enabled=${config.enabled}")
        runCatching { onUpdated?.invoke(config) }
    }

    fun maybeRefresh(context: Context?) = synchronized(this) {
        val now = System.currentTimeMillis()
        if (abs(now - lastRefreshMs) < REFRESH_INTERVAL_MS) return
        lastRefreshMs = now
        refresh(context)
    }

    fun refresh(context: Context?) {
        val ctx = context ?: Contexts.any()
        val fromProvider = readFromProvider(ctx)
        val fromXsp = if (fromProvider == null) readFromXSharedPreferences() else null
        when {
            fromProvider != null -> {
                value = fromProvider
                debugLog("provider scale=${value.scale} gate=${value.gateMode}")
            }
            fromXsp != null -> {
                value = fromXsp
                debugLog("xsp scale=${value.scale} gate=${value.gateMode}")
            }
            else -> debugLog("fallback scale=${value.scale} gate=${value.gateMode}")
        }
        // 无论上面是否成功，都向模块 App 索要一次最新配置（显式广播，绕过包可见性）
        if (ctx != null) TouchRelay.requestConfig(ctx)
        lastRefreshMs = System.currentTimeMillis()
        runCatching { onUpdated?.invoke(value) }
    }

    private fun readFromProvider(context: Context?): Config? {
        if (context == null) return null
        return try {
            val b: Bundle? = context.contentResolver.call(
                ConfigProvider.URI,
                ConfigProvider.METHOD_CONFIG,
                null,
                null
            )
            if (b == null || !b.containsKey("enabled")) null else Config.fromBundle(b)
        } catch (t: Throwable) {
            null
        }
    }

    /** 反射调用 XSharedPreferences，避免普通进程里因缺类导致 verify 失败。 */
    private fun readFromXSharedPreferences(): Config? {
        return try {
            val clazz = Class.forName("de.robv.android.xposed.XSharedPreferences")
            @Suppress("UNCHECKED_CAST")
            val ctor = clazz.getConstructor(String::class.java, String::class.java)
            val xsp = ctor.newInstance(Config.MODULE_PACKAGE, Config.PREFS_FILE)
            runCatching { clazz.getMethod("makeWorldReadable").invoke(xsp) }
            runCatching { clazz.getMethod("reload").invoke(xsp) }
            // prefs 文件尚未生成时，getXxx 会全部返回默认值。若此时返回非 null，
            // 会把已经通过 CONFIG_PUSH 送达的自定义配置覆盖掉 —— 必须判空回退。
            val hasData = runCatching {
                clazz.getMethod("contains", String::class.java).invoke(xsp, "nonce") as Boolean
            }.getOrDefault(false)
            if (!hasData) return null
            val d = Config.default()
            fun bool(k: String, def: Boolean) =
                clazz.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
                    .invoke(xsp, k, def) as Boolean
            fun flt(k: String, def: Float) =
                clazz.getMethod("getFloat", String::class.java, java.lang.Float.TYPE)
                    .invoke(xsp, k, def) as Float
            fun int(k: String, def: Int) =
                clazz.getMethod("getInt", String::class.java, Integer.TYPE)
                    .invoke(xsp, k, def) as Int
            fun str(k: String, def: String) =
                (clazz.getMethod("getString", String::class.java, String::class.java)
                    .invoke(xsp, k, def) as? String) ?: def
            Config(
                enabled = bool("enabled", d.enabled),
                scale = flt("scale", d.scale),
                speed = flt("speed", d.speed),
                themeMode = int("themeMode", d.themeMode),
                touchSource = int("touchSource", d.touchSource),
                gateMode = int("gateMode", d.gateMode),
                globalEffect = bool("globalEffect", d.globalEffect),
                hideNativeDot = bool("hideNativeDot", d.hideNativeDot),
                dprCapEnabled = bool("dprCapEnabled", d.dprCapEnabled),
                targetPackage = str("targetPackage", d.targetPackage),
                extraTargets = str("extraTargets", d.extraTargets),
                darkFill = int("darkFill", d.darkFill),
                darkArcStart = int("darkArcStart", d.darkArcStart),
                darkArcEnd = int("darkArcEnd", d.darkArcEnd),
                darkSpark = int("darkSpark", d.darkSpark),
                lightFill = int("lightFill", d.lightFill),
                lightArcStart = int("lightArcStart", d.lightArcStart),
                lightArcEnd = int("lightArcEnd", d.lightArcEnd),
                lightSpark = int("lightSpark", d.lightSpark),
                nonce = str("nonce", d.nonce)
            )
        } catch (t: Throwable) {
            null
        }
    }
}
