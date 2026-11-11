package com.myclick.hook

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.myclick.bridge.HookBridge
import com.myclick.bridge.Phase
import com.myclick.core.ConfigStore
import com.myclick.core.Contexts
import com.myclick.core.RecordingState

/**
 * 只在被判定为「屏幕录制 App」的进程里安装。
 *
 * 逆向依据（com.oplus.screenrecorder 17.5.4）：
 *  com.oplus.screenrecorder.common.b0 在开始录制时执行
 *      Settings.System.putInt(cr, "show_touches", 1)
 *  结束录制时写回 0 —— 系统级「显示点按反馈」的白色圆点就是这么来的。
 *  所以这里既用它做录屏状态信号，又把它钳成 0 来隐藏原生圆点。
 */
class RecorderHooks(private val bridge: HookBridge) {

    fun install() {
        installContextCapture()
        installNativeDotSuppressor()
        installRecordingStateSignals()
        installDecorFallback()
        bridge.log("[recorder] 录屏进程 Hook 已安装")
    }

    /** 尽早拿到 Context：Application.onCreate 与 ContextWrapper.attachBaseContext 双保险 */
    private fun installContextCapture() {
        val wrapper = runCatching { Class.forName("android.content.ContextWrapper") }.getOrNull()
        if (wrapper != null) {
            bridge.hookAll(wrapper, "attachBaseContext", Phase.AFTER) { ctx ->
                val c = ctx.self as? Context ?: return@hookAll
                Contexts.offer(c)
                runCatching { RecorderRuntime.start(c, bridge) }
            }
        }
        val app = runCatching { Class.forName("android.app.Application") }.getOrNull()
        if (app != null) {
            bridge.hookAll(app, "onCreate", Phase.AFTER) { ctx ->
                val c = ctx.self as? Context ?: return@hookAll
                Contexts.offer(c)
                runCatching { RecorderRuntime.start(c, bridge) }
            }
        }
    }

    /**
     * 隐藏系统原生触摸圆点。
     * 不删除调用、不抛异常，只把 value 改成 0 —— 录屏 App 的逻辑完全不受影响。
     */
    private fun installNativeDotSuppressor() {
        val settingsSystem = runCatching {
            Class.forName("android.provider.Settings\$System")
        }.getOrNull() ?: return
        bridge.hookAll(settingsSystem, "putInt", Phase.BEFORE) { ctx ->
            val args = ctx.args
            if (args.size < 3) return@hookAll
            if (args[1] as? String != "show_touches") return@hookAll
            val value = args[2] as? Int ?: return@hookAll
            RecordingState.setActive(value != 0)
            if (ConfigStore.value.hideNativeDot) {
                ctx.setArg(2, 0)
            }
        }
        // 有些分支走 putIntForUser，同样钳制
        bridge.hookAll(settingsSystem, "putIntForUser", Phase.BEFORE) { ctx ->
            val args = ctx.args
            if (args.size < 4) return@hookAll
            if (args[1] as? String != "show_touches") return@hookAll
            val value = args[2] as? Int ?: return@hookAll
            RecordingState.setActive(value != 0)
            if (ConfigStore.value.hideNativeDot) ctx.setArg(2, 0)
        }
    }

    /** MediaProjection / MediaRecorder 作为录屏状态的兜底信号 */
    private fun installRecordingStateSignals() {
        val mpm = bridge.findClass("android.media.projection.MediaProjectionManager", null)
        if (mpm != null) {
            bridge.hookAll(mpm, "getMediaProjection", Phase.AFTER) {
                RecordingState.setActive(true)
            }
        }
        val mp = bridge.findClass("android.media.projection.MediaProjection", null)
        if (mp != null) {
            bridge.hookAll(mp, "stop", Phase.BEFORE) {
                RecordingState.setActive(false)
            }
        }
        val recorder = bridge.findClass("android.media.MediaRecorder", null)
        if (recorder != null) {
            bridge.hookAll(recorder, "start", Phase.AFTER) { RecordingState.setActive(true) }
            bridge.hookAll(recorder, "stop", Phase.BEFORE) { RecordingState.setActive(false) }
        }
    }

    /**
     * 无 SYSTEM_ALERT_WINDOW 时的回退：找录屏 App 的全屏窗口 DecorView，
     * 往里 addView 同一个 EffectSurfaceView（不加 FLAG_SECURE）。
     */
    private fun installDecorFallback() {
        val activity = runCatching { Class.forName("android.app.Activity") }.getOrNull()
        if (activity != null) {
            bridge.hookAll(activity, "onResume", Phase.AFTER) { ctx ->
                val a = ctx.self as? Activity ?: return@hookAll
                Contexts.offer(a)
                runCatching { RecorderRuntime.start(a, bridge) }
                runCatching { RecorderRuntime.attachDecor(a.window.decorView as View) }
            }
        }
        val wmi = bridge.findClass("android.view.WindowManagerImpl", null)
        if (wmi != null) {
            bridge.hookAll(wmi, "addView", Phase.AFTER) { ctx ->
                val view = ctx.argOrNull(0) as? View ?: return@hookAll
                // 别把我们自己刚加的特效窗口又当成宿主 DecorView
                if (view.javaClass.name.contains("EffectSurfaceView")) return@hookAll
                val lp = ctx.argOrNull(1) as? WindowManager.LayoutParams ?: return@hookAll
                if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                    lp.height == ViewGroup.LayoutParams.MATCH_PARENT
                ) {
                    runCatching { RecorderRuntime.attachDecor(view) }
                }
            }
        }
    }
}
