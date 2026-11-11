// 顶层构建脚本：版本集中声明，子模块只 apply 不写版本。
// AGP 9.x 起 Kotlin 支持已内置（built-in Kotlin），无需再 apply
// org.jetbrains.kotlin.android，否则会直接报致命诊断错误。
plugins {
    id("com.android.application") version "9.4.1" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
