package com.myclick.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 跨进程事件总线。
 *
 * system_server 无法直接往录屏进程里调 Java 对象，所以用「只投递给动态注册接收者」
 * 的轻量广播把 ACTION_DOWN 坐标送过去；接收者只在录屏进程里注册。
 * FLAG_RECEIVER_REGISTERED_ONLY 保证不会唤醒任何静态接收者 / 拉起进程。
 */
object TouchRelay {
    const val ACTION_TOUCH = "com.myclick.action.TOUCH"
    const val ACTION_CONFIG_PUSH = "com.myclick.action.CONFIG_PUSH"
    const val ACTION_CONFIG_REQUEST = "com.myclick.action.CONFIG_REQUEST"
    const val ACTION_TEST_TOUCH = "com.myclick.action.TEST_TOUCH"

    const val EX_X = "x"
    const val EX_Y = "y"
    const val EX_DISPLAY_ID = "displayId"
    const val EX_EVENT_TIME = "eventTime"
    const val EX_NONCE = "nonce"
    const val EX_CONFIG = "config"

    private fun base(action: String): Intent = Intent(action).apply {
        addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    }

    fun sendTouch(
        context: Context,
        xPx: Float,
        yPx: Float,
        displayId: Int,
        eventTime: Long,
        nonce: String,
        targetPackage: String = ""
    ) {
        try {
            val i = base(ACTION_TOUCH).apply {
                putExtra(EX_X, xPx)
                putExtra(EX_Y, yPx)
                putExtra(EX_DISPLAY_ID, displayId)
                putExtra(EX_EVENT_TIME, eventTime)
                putExtra(EX_NONCE, nonce)
                // 显式包名：真机 dumpsys 里隐式广播会被放进延迟队列（+500ms），
                // 指定包名后走前台队列直达动态接收器，点击与特效几乎同步。
                if (targetPackage.isNotEmpty()) setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            context.sendBroadcast(i)
        } catch (t: Throwable) {
            // 广播失败不能影响系统输入分发
        }
    }

    fun sendConfig(context: Context, config: Config) {
        try {
            val i = base(ACTION_CONFIG_PUSH).apply {
                putExtra(EX_CONFIG, config.toBundle())
                putExtra(EX_NONCE, config.nonce)
            }
            context.sendBroadcast(i)
        } catch (t: Throwable) {
            // ignore
        }
    }

    /**
     * 被 Hook 进程启动时向模块 App 索要一份最新配置。
     *
     * 真机上 Android 11+ 的包可见性会让录屏 / system_server 解析不到
     * com.myclick.config Provider，因此改用显式广播（setPackage）触发模块 App 的
     * 静态接收器，再由它回推 CONFIG_PUSH。显式 Intent 可以拉起未运行的模块进程。
     */
    fun requestConfig(context: Context) {
        try {
            // 必须是 ComponentName 级显式 Intent：只 setPackage 在 Android 11+ 的
            // 包可见性过滤下，普通 App 进程（录屏）依然会解析不到模块包，广播被静默丢弃。
            val i = Intent(ACTION_CONFIG_REQUEST).apply {
                component = ComponentName(
                    Config.MODULE_PACKAGE,
                    "com.myclick.core.ConfigRequestReceiver"
                )
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            context.sendBroadcast(i)
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun parseTouch(intent: Intent?, expectedNonce: String): TouchEvent? {
        intent ?: return null
        if (intent.getStringExtra(EX_NONCE) != expectedNonce) return null
        return TouchEvent(
            xPx = intent.getFloatExtra(EX_X, Float.NaN),
            yPx = intent.getFloatExtra(EX_Y, Float.NaN),
            displayId = intent.getIntExtra(EX_DISPLAY_ID, 0),
            eventTime = intent.getLongExtra(EX_EVENT_TIME, 0L)
        ).takeIf { it.xPx.isFinite() && it.yPx.isFinite() }
    }

    fun parseConfig(intent: Intent?): Config? {
        intent ?: return null
        val b: Bundle = intent.getBundleExtra(EX_CONFIG) ?: return null
        return Config.fromBundle(b)
    }
}

data class TouchEvent(
    val xPx: Float,
    val yPx: Float,
    val displayId: Int,
    val eventTime: Long
)

/** 录屏进程内部把触摸事件交给覆盖层；其它进程置空即可。 */
object TouchBus {
    @Volatile
    var sink: ((TouchEvent) -> Unit)? = null

    fun dispatch(event: TouchEvent) {
        try {
            sink?.invoke(event)
        } catch (t: Throwable) {
            // 渲染层异常绝不能冒泡回输入分发
        }
    }
}
