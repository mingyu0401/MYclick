<div align="center">

# ✦ MYclick

**把录屏里的白色触摸圆点，换成橙色扩散、旋转光弧与火花的点击特效**

[📦 下载 Release](https://github.com/mingyu0401/MYclick/releases/latest) ·
[🐛 提交 Issue](https://github.com/mingyu0401/MYclick/issues)

<img src="docs/images/effect-demo.png" width="360" alt="点击特效效果示意图"/>

</div>

---

## ✨ 功能

- 点击任意位置都会出现特效，并且能被录屏录下
- 三种运行方式：LSPosed、Shizuku（免 root，推荐）、Root
- 可调整特效大小、动画速度、深浅色主题和配色
- 自动识别本机的录屏 App
- 可选「全局生效」：不录屏也显示特效（默认关闭）

## 📋 要求

- Android 12 及以上
- 以下三者之一：LSPosed、Shizuku、root

## 🚀 使用

### LSPosed 模式

1. 安装 APK，在 LSPosed 中启用 MYclick。
2. 作用域同时勾选「系统框架 (android)」和录屏 App（如 `com.oplus.screenrecorder`）。只勾录屏 App 不会有全屏特效。
3. 重启手机，然后开始录屏。

### Shizuku 模式（推荐）

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/)。
2. 在 MYclick 首页选择「Shizuku 模式」，按提示授权。

设备重启后需要重新启动 Shizuku。

### Root 模式

在 MYclick 首页选择「Root 模式」，并在 Magisk / KernelSU / APatch 中授权。

## ⚙️ 设置

| 选项 | 说明 |
| --- | --- |
| SCALE | 特效大小，0.5–3.0，默认 1.0 |
| 动画速度 | 0.3–3.0，默认 1.0 |
| 主题 | 跟随系统 / 强制深色 / 强制浅色 |
| 全局生效 | 开启后不录屏也显示特效，可能比较打扰，默认关闭 |
| 调色板 | 深色、浅色各 4 种颜色，可自定义 |

## 🛠 从源码构建

需要 JDK 17+ 和 Android SDK（platform 36）。

```bash
./gradlew :app:assembleRelease --offline
```

产物位于 `app/build/outputs/apk/release/app-release.apk`，使用 debug 签名，可直接安装。

## 📄 许可

[Apache-2.0](LICENSE)

Hook 系统框架有一定风险，使用前请先备份数据。
