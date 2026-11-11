package com.myclick.bridge

/** Hook 时机 */
enum class Phase { BEFORE, AFTER }

/**
 * 与具体 Hook 框架解耦的桥接接口。
 * hook 层只依赖这个接口，方便替换成 LSPosed / 其它实现或写单元测试桩。
 */
interface HookBridge {
    fun findClass(name: String, loader: ClassLoader?): Class<*>?
    fun hook(
        clazz: Class<*>,
        methodName: String,
        phase: Phase,
        parameterTypes: Array<out Class<*>>,
        block: (MethodContext) -> Unit
    )

    /** 不关心签名（跨 Android 版本参数可能变化）时按名字全量 Hook */
    fun hookAll(clazz: Class<*>, methodName: String, phase: Phase, block: (MethodContext) -> Unit)
    fun log(message: String)
    fun log(throwable: Throwable)
}

/** 对 MethodHookParam 的轻量包装，屏蔽 Xposed 类型 */
class MethodContext(private val raw: Any) {
    private val p: de.robv.android.xposed.XC_MethodHook.MethodHookParam
        get() = raw as de.robv.android.xposed.XC_MethodHook.MethodHookParam

    val self: Any? get() = p.thisObject

    @Suppress("UNCHECKED_CAST")
    val args: Array<Any?> get() = p.args as Array<Any?>

    var result: Any?
        get() = p.result
        set(value) {
            p.result = value
        }

    val throwable: Throwable? get() = p.throwable

    fun setArg(index: Int, value: Any?) {
        p.args[index] = value
    }

    fun argOrNull(index: Int): Any? = p.args.getOrNull(index)
}
