package com.myclick

import com.myclick.bridge.XposedBridgeImpl
import com.myclick.core.Config
import com.myclick.core.ConfigStore
import com.myclick.core.Contexts
import com.myclick.core.PackageDetector
import com.myclick.hook.CommonHooks
import com.myclick.hook.FrameworkRuntime
import com.myclick.hook.HookGate
import com.myclick.hook.InputEventHook
import com.myclick.hook.RecorderHooks
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 入口（assets/xposed_init 指向这里）。
 *
 * 角色判定完全在运行时做，且用 HookGate 保证每类 Hook 在一个进程里只装一次：
 *   - system_server  -> 系统框架触摸源（全局 ACTION_DOWN）
 *   - 录屏包（动态探测）-> 覆盖层 + 原生圆点抑制 + 录屏状态
 *   - 其它被勾选的 App -> 可选的应用作用域触摸转发
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    private val bridge = XposedBridgeImpl()

    @Volatile
    private var detectedPackages: List<String>? = null

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        // zygote 阶段先尽力加载一次配置（通常走 XSharedPreferences）
        runCatching { ConfigStore.refresh(null) }
        bridge.log("initZygote, modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == Config.MODULE_PACKAGE) return

        val cfg = ConfigStore.value
        // 不在加载期因为「总开关关闭」就跳过安装：所有热路径都会实时读 enabled，
        // 这样设置页改开关后无需重启被 Hook 的进程即可生效。

        // LSPosed 在 system_server 里会为它加载的每一个 package context 回调一次，
        // 真机实测一次点击曾产生 8 份 ACTION_DOWN 广播。只认第一次回调。
        if (HookGate.isSystemServer) {
            if (HookGate.claim(HookGate.FRAMEWORK)) {
                bridge.log("[android] system_server 认领 (pkg=$pkg, ${HookGate.processName})")
                installCommon()
                if (cfg.touchSource == Config.SOURCE_AUTO || cfg.touchSource == Config.SOURCE_FRAMEWORK) {
                    runCatching { FrameworkRuntime.install(bridge, lpparam.classLoader) }
                        .onFailure { bridge.log(it) }
                } else {
                    bridge.log("[android] 框架触摸源按配置跳过 (source=${cfg.touchSource})")
                }
            }
            return
        }

        if (HookGate.claim(HookGate.COMMON)) installCommon()

        // 个别 LSPosed 分支在普通 App 进程也会回调 "android"（framework-res）。
        // 这时不能装 framework 运行时，改走应用作用域输入观察。
        if (pkg == "android" || pkg == "system") {
            if (HookGate.claim(HookGate.APP_INPUT) &&
                (cfg.touchSource == Config.SOURCE_AUTO || cfg.touchSource == Config.SOURCE_APP_SCOPED)
            ) {
                runCatching {
                    InputEventHook(bridge, "app:$pkg", true, lpparam.classLoader).install()
                }.onFailure { bridge.log(it) }
            }
            return
        }

        val isRecorder = try {
            isRecorderPackage(pkg)
        } catch (t: Throwable) {
            false
        }

        if (isRecorder) {
            if (HookGate.claim(HookGate.RECORDER)) {
                runCatching { RecorderHooks(bridge).install() }.onFailure { bridge.log(it) }
                if (cfg.touchSource == Config.SOURCE_AUTO || cfg.touchSource == Config.SOURCE_LOCAL) {
                    // 规格里指定的录屏进程内 InputEventReceiver Hook（覆盖自身悬浮 UI / 本地调试）
                    runCatching {
                        InputEventHook(bridge, "local:$pkg", false, lpparam.classLoader).install()
                    }.onFailure { bridge.log(it) }
                }
                bridge.log("[target] 命中录屏包: $pkg (process=${HookGate.processName})")
            }
        } else if (cfg.touchSource == Config.SOURCE_AUTO || cfg.touchSource == Config.SOURCE_APP_SCOPED) {
            // 降级路径：把该 App 窗口内的 ACTION_DOWN 转发给录屏进程
            if (HookGate.claim(HookGate.APP_INPUT)) {
                runCatching {
                    InputEventHook(bridge, "app:$pkg", true, lpparam.classLoader).install()
                }.onFailure { bridge.log(it) }
            }
        }
    }

    private fun installCommon() {
        runCatching { CommonHooks(bridge).install() }.onFailure { bridge.log(it) }
    }

    /** 配置里的目标 + pm 运行时探测（含 "screenrecorder" 的系统包），做降级兼容 */
    private fun isRecorderPackage(pkg: String): Boolean {
        if (ConfigStore.value.allTargets().any { it.equals(pkg, ignoreCase = true) }) return true
        val detected = detectedPackages ?: synchronized(this) {
            detectedPackages ?: PackageDetector.detect(Contexts.any()).also { detectedPackages = it }
        }
        return detected.any { it.equals(pkg, ignoreCase = true) }
    }
}
