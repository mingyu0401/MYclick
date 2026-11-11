# ============================================================
#  MYclick / LSPosed module
#  默认 minify 关闭；开启前请确认以下 keep 规则完整。
# ============================================================

-dontwarn de.robv.android.xposed.**
-dontwarn android.content.res.XResources**
-dontwarn com.android.internal.**
-dontwarn moe.shizuku.**
-dontwarn kotlinx.**

# --- Xposed 入口：assets/xposed_init 里写的类名不能被改 ---
-keep class com.myclick.HookEntry { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.content.ContentProvider { *; }
-keep class * extends android.app.Activity { *; }

# Hook 层大量使用字符串反射定位类/方法，整包保留最稳妥
-keep class com.myclick.hook.** { *; }
-keep class com.myclick.bridge.** { *; }
-keep class com.myclick.core.** { *; }
-keep class com.myclick.overlay.** { *; }
-keep class com.myclick.fx.** { *; }
-keep class com.myclick.ui.** { *; }

# ContentProvider 通过反射/Call 暴露，保留方法名
-keepclassmembers class com.myclick.core.ConfigProvider { public *; }

# 配置字段名写进 Bundle/Cursor，不能混淆
-keepclassmembers class com.myclick.core.Config { *; }
-keepclassmembers class com.myclick.core.Config$Companion { *; }

# 反射调用的系统隐藏 API 名称以字符串形式存在，禁止优化掉
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# SurfaceView / Choreographer 回调由框架回调
-keepclassmembers class * extends android.view.SurfaceView {
    public <init>(...);
    public void surfaceCreated(...);
    public void surfaceChanged(...);
    public void surfaceDestroyed(...);
}
