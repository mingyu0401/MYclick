<div align="center">

# ✦ MYclick

**把 ColorOS / 一加「屏幕录制」里那个朴素的白色圆点，<br/>换成会扩散、会旋转、会迸出火花的点击特效**

[![Release](https://img.shields.io/badge/Release-v1.0.40-F9A86C?style=flat-square)](https://github.com/mingyu0401/MYclick/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-3DDC84?style=flat-square)](#-兼容性)
[![Framework](https://img.shields.io/badge/Framework-LSPosed-647589?style=flat-square)](#-快速开始)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF?style=flat-square)](#-从源码构建)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue?style=flat-square)](LICENSE)

[📦 下载 Release APK](https://github.com/mingyu0401/MYclick/releases/latest) ·
[🚀 快速开始](#-快速开始) ·
[🐛 提交 Issue](https://github.com/mingyu0401/MYclick/issues)

<br/>

<img src="docs/images/effect-demo.png" width="360" alt="点击特效效果示意图"/>

*效果示意 · 橙色扩散填充圆 + 双旋转光弧 + 三角火花，以实际点击点为圆心*

</div>

---

## ✨ 它做了什么

原版 ColorOS 屏录的「记录屏幕点击」，本质是打开 AOSP 的 `show_touches`，
由 `system_server` 在合成层画一个白点。MYclick 把这个白点关掉，
用一层**透明、不可触摸、能被 MediaProjection 捕获**的覆盖层重画一遍：

| | |
| :--- | :--- |
| 🎯 **全屏真实坐标** | 从系统框架的 `InputEventReceiver#dispatchInputEvent` 取全局 `ACTION_DOWN`，点任何 App 都有特效，不消费事件 |
| 🎬 **真的录得进去** | 框架模式用 `TYPE_APPLICATION_OVERLAY` + 透明 `SurfaceView`；Shizuku 模式用 shell 身份的 `SurfaceControl` 图层。两者都走 SurfaceFlinger 合成链且**不设 `FLAG_SECURE`**，也**绝不往 AUTO_MIRROR 虚拟屏加窗口** |
| ⚡ **60fps 零分配** | `Choreographer` 驱动、粒子对象池 + swap-remove、`Paint`/`Path` 复用、`dt` 钳 50ms，无粒子立即停帧清屏 |
| 🎨 **完全可配** | SCALE、动画速度、深浅主题、8 项调色板、目标包名、触摸来源、全局生效开关，改完即时广播生效 |
| 📱 **自动识别录屏 App** | 打开设置页即探测本机真实安装的屏幕录制包名并自动填入目标包名，换机 / 换 ROM 无需手填 |

---

## 📸 效果

<div align="center">

| 效果示意 | 真机录屏抽帧（非合成图） |
| :---: | :---: |
| <img src="docs/images/effect-demo.png" width="260" alt="效果示意"/> | <img src="docs/images/effect-recorded.png" width="260" alt="真机录屏抽帧"/> |
| 规格参考 | 橙色扩散圆 + 双段白→淡蓝光弧 + 三角火花 |

</div>

动画规格 1:1 还原：16 帧 ease-out-cubic 扩散圆（`26 × SCALE`）、
23 帧双光弧（各 `1.1π`、10 段变线宽、白 → 淡蓝/粉）、4 个三角火花（摩擦 0.9、每帧 -0.032 alpha），
逻辑坐标用 dp，DPR 上限 2x。设置页里的「实时预览」跑的就是同一套 `EffectEngine`。

<details>
<summary><b>点开看完整规格对照表</b></summary>

| 规格 | 实现 |
| --- | --- |
| 扩散圆 0 → `26 × SCALE`（默认 1.0），266ms / 16 帧 | `EffectEngine.BASE_RADIUS` / `CIRCLE_FRAMES`，`ease = 1-(1-p)³` |
| 填充 alpha 0.4 → 0 | `fillAlpha = 0.4*(1-cp)*255` |
| 双光弧各 `1.1π`，随机起始偏移 / 角速度 `[0, 0.08, 0.1]` | `ARC_SPAN` / `ROT_SPEEDS` |
| 弧半径按 `[0,1,1.5,2] × SCALE` 向外扩张，383ms / 23 帧 | `SPREAD_RATES` / `ARC_FRAMES` |
| 线宽 `weight(t)=min(2-|4(t-0.5)|,1)`，0.4 → 3.3，分 10 段 | `MIN_WIDTH/MAX_WIDTH` / `ARC_SEGMENTS` |
| 白 `(250,252,252)` → 淡蓝 `(150,210,255)` / 粉 `(224,79,124)` | `Palette.arcStart/arcEnd` 线性插值 |
| 4 个三角火花，初速 `4.8~6.8 × SCALE`，摩擦 ×0.9 | `SPARKS_PER_TAP` / `FRICTION` |
| 深色圆 `rgba(249,168,108)`、浅色圆 `rgba(224,122,63)` | `Config.dark*` / `Config.light*`，设置页可改 |

</details>

---

## 📦 下载

| 渠道 | 文件 |
| --- | --- |
| **GitHub Releases**（推荐） | [MYclick-1.0.40-release.apk](https://github.com/mingyu0401/MYclick/releases/latest) |
| 仓库内直链 | [download/MYclick-1.0.40-release.apk](https://github.com/mingyu0401/MYclick/raw/main/download/MYclick-1.0.40-release.apk) |

> **测试与日常使用统一使用 release 包**（复用 debug 签名，可直接覆盖安装）。
> 当前版本 **v1.0.40**；v1.0.0 / v1.0.1 / v1.0.2 保留为历史 Release。
> SHA-256：`afdf71795244a7d22b01e083771a8eae57dbe46de7ac238d6b53c469eb7293ba`

---

## 🚀 快速开始

<details open>
<summary><b>① 安装</b></summary>

```bash
adb install -r download/MYclick-1.0.40-release.apk
adb shell pm list packages -s | grep -i screenrecord   # 确认录屏包名
```

</details>

<details open>
<summary><b>② 在 LSPosed 里启用并勾选作用域</b>（最关键的一步）</summary>

打开 **LSPosed 管理器 → 模块 → MYclick**，启用后勾选：

- ✅ `com.oplus.screenrecorder`（屏幕录制；**目标包名会自动识别为本机真实安装的录屏 App**，其它分支可在「关于」页重新探测）
- ✅ **系统框架 / System Framework (`android`)** ← 全屏点击坐标全靠它
- ✅ 部分版本还会单列 `system`，一并勾上

> 只勾选录屏 App 是**不会有全屏特效**的——录屏进程本身收不到别的窗口的触摸事件。

已 root 且装了 `lspctl`，也可以命令行完成（真机验证走的就是这条路径）：

```bash
adb shell su -c 'lspctl module enable --user=0 com.myclick'
adb shell su -c 'lspctl scope set --user=0 com.myclick android system com.oplus.screenrecorder'
```

</details>

<details open>
<summary><b>③ 重启 → 打开设置页 → 开始录屏</b></summary>

1. **重启手机**（Hook 系统框架必须重启；软重启 `kill system_server` 也可以，无线调试会短暂断开）。
   只更新覆盖层代码时不必重启整机，`adb shell am force-stop com.oplus.screenrecorder` 即可重新加载。
   **「全局生效」的宿主在 system_server 里，模块 APK 升级后必须重启系统框架才会加载新代码。**
2. 打开 MYclick 设置页，在「特效」页点一下预览区确认动画，按需要调 SCALE / 调色板。
3. 用系统快捷磁贴开始录屏，随便点几下，停止后回看视频。建议同时在录屏设置里打开「记录屏幕点击」——
   它写 `show_touches=1` 是最可靠的「录制开始」信号，本模块会把它钳回 0 以隐藏原生白点。

</details>

排障日志：

```bash
adb logcat -s LSPosed:V MYclick:V Xposed:V
adb shell settings get system show_touches     # 录屏中应为 0
```

ColorOS 会过滤普通 App 的 `android.util.Log`，**优先看 LSPosed 管理器 → 日志 → 模块日志**。

---

## 🧭 设置页

三页底部胶囊导航，深色卡片式排布，避免一长条列表：

| 页 | 内容 |
| --- | --- |
| **首页** | 版本主卡、Root / LSPosed 状态双卡、设备信息、常用开关（含「全局生效」）、一键推送配置 |
| **特效** | 实时预览（点一下就出特效）、SCALE / 速度 / 触摸来源 / 全局生效、深浅两套调色板 |
| **关于** | 效果示意图、目标包名（自动识别）与重新探测、工具与仓库链接、排障速查 |

<div align="center">

| 首页 | 特效 | 关于 |
| :---: | :---: | :---: |
| <img src="docs/images/ui-home.jpg" width="210" alt="设置页首页"/> | <img src="docs/images/ui-fx.jpg" width="210" alt="设置页特效"/> | <img src="docs/images/ui-about.jpg" width="210" alt="设置页关于"/> |

*真机截图 · 一加 PLK110 / Android 17 / ColorOS 17 · v1.0.12 release 包*

底部导航带滑动高亮胶囊：切页时旧页淡出横移、新页淡入，图标弹性缩放、文字颜色渐变；
主卡右侧图标与 App 启动图标共用同一个矢量资源，圆形裁切后自然融入卡片。

</div>

「特效」页的预览区与录屏覆盖层共用同一套 `EffectEngine`，点一下就能看到动画（下图为录屏抽帧）：

<div align="center">
<img src="docs/images/ui-fx-preview.jpg" width="420" alt="设置页实时预览动画抽帧"/>
</div>

<details>
<summary>设计参考</summary>

设置页的视觉语言（纯黑底、石板蓝主卡、`#1A1A1A` 分组卡、大标题、底部胶囊导航）参考了
ONextBox 的深色卡片式布局，仅作样式参考：

<img src="docs/images/ui-reference.jpg" width="240" alt="UI 设计参考"/>

</details>

---

## 🛠 从源码构建

```bash
# JDK 17+ / Android SDK platform android-36 / build-tools 36+
export ANDROID_HOME=/path/to/Android/Sdk     # Windows 可写 local.properties
./gradlew :app:assembleRelease --offline
# 产物：app/build/outputs/apk/release/app-release.apk
```

- AGP 9.4.1 **内置 Kotlin 2.2.0**，不要再 apply `org.jetbrains.kotlin.android`；
  `kotlin-stdlib` 版本必须与内置编译器一致。
- `app/libs/xposed-api-82.jar` 是 **compileOnly** 桩，运行时由 LSPosed 提供，
  已验证 APK 内不含 `de/robv/android/xposed/**` 类。
- 混淆默认关闭（`gradle.properties` 的 `myclick.enableMinify=false`），
  置 `true` 即启用 `proguard-rules.pro`（Hook 入口、反射目标、配置字段均已 keep）。
- release 复用 debug 签名，**可直接安装**，不用自己配 keystore。
- 无 AndroidX / 无第三方运行时依赖，整包约 2.3 MB。

---

## ⚙️ 配置项

| 项 | 默认 | 说明 |
| --- | --- | --- |
| 启用特效 | 开 | 热路径实时读取，改后不必重启 |
| SCALE / 动画速度 | 1.0 / 1.0 | 范围 0.5~3.0 / 0.3~3.0 |
| 主题 | 跟随系统 | 三个页面右上角一个图标按钮，点一次循环「跟随系统 → 强制深色 → 强制浅色」，图标同步变化；判定以系统下发的 `Configuration.uiMode` 为准（ColorOS 的 `mNightModeLocked` 会让 `UiModeManager.getNightMode()` 失真），shell 绘制进程向 AMS 取全局配置，同样实时生效；切换时重建前后用旧界面快照做交叉淡化，整屏颜色平滑过渡 |
| 触摸事件来源 | 自动 | 自动 / 仅系统框架 / 仅应用作用域 / 仅录屏进程本地 |
| **全局生效** | **关** | 默认只在录屏时显示；打开后**不录屏也出特效**，由 system_server 里的覆盖层直接绘制，不依赖录屏 App 进程是否在运行。⚠️ 全局显示可能会厌烦，慎重打开（开启前二次确认）。旧版「显示时机 → 始终显示」自动迁移到此开关 |
| 隐藏原生圆点 / DPR 上限 2x | 开 / 开 | 钳 `show_touches=0`；关闭 DPR 上限按真实 density 渲染 |
| 目标 / 附加包名 | 自动识别本机录屏包 | 打开设置页即探测并自动填入；可手动覆盖 / 重新探测 |
| 调色板 8 项 | 见规格表 | 深色 / 浅色各 4 个 `#RRGGBB` |
| **Shizuku 模式** | **关** | 给没有 root / LSPosed 的普通用户，**推荐**。用 Shizuku 以 `shell` 身份读触摸屏设备节点，拿到的是**真实触摸坐标**：点空白处、游戏、视频全屏控件都有特效，位置不偏移，也不会弹「正在读取屏幕内容」。绘制走 shell 身份子进程的 `SurfaceControl` 图层，**没有窗口，也就没有「正在当前页面悬浮显示」提示**，且每次点击只开一块点击点周围的小图层（合成面积约为全屏的 1/9）。需要 Shizuku 或 root。开启后「全局生效」和录屏内特效自动让位 |

### Shizuku 模式（免 root，推荐）

不需要 root、不需要 LSPosed，拿到的是真实触摸坐标：

1. 安装 [Shizuku](https://shizuku.rikka.app/)，用「无线调试」或「root」启动它的服务
2. 设置页首页 →「免 Xposed 模式」→ 打开「Shizuku 模式」，按提示授权
3. 允许「显示在其他应用上层」（旧版本残留的权限，新版绘制已不再需要窗口）

原理：`shell` 用户属于 `input` 组，可以读 `/dev/input/event*`。模块通过 Shizuku 启动
`getevent -lt <触摸屏>`，解析 `ABS_MT_POSITION_X/Y` 与 `BTN_TOUCH`，按面板量程线性映射到
屏幕像素，并按当前屏幕旋转做一次坐标变换。因此它和框架模式一样是**手指真实位置**，
而不是控件中心。

绘制端是一个以 `shell` 身份运行的 `app_process` 子进程：里面用 `SurfaceControl` 直接向
SurfaceFlinger 要 root 图层来画，**绕开窗口系统** —— 所以 ColorOS 不会再弹
「正在当前页面悬浮显示」。触摸事件、录制状态判定、绘制都在这个子进程里，App 只负责
启动它并下发配置。

**每次点击只开一块小图层**：一次特效的可见范围只有点击点周围约 90dp，但整屏
buffer 在 1272×2772 上每帧要清 13MB、上传 13MB，SurfaceFlinger 还要整块合成一遍；
录屏时那块虚拟屏再合一次，点完那 0.4 秒里系统带宽会被吃掉一大块，表现就是**点完马上
滑动发涩**。现在按点击点定位一块 630×630（scale=1 时）的正方形图层，用 `setPosition`
精确摆到点击处，合成面积降到原先的 1/9；连点各自独立成层（最多 5 块，仍比一块全屏轻）。
**不能用「缩小 buffer + `setScale` 放大」** —— `setScale` 的缩放锚点不是左上角，实测
特效会整体偏到别处。

**退出设置页后特效继续工作**：模块会起一个前台服务（低优先级常驻通知）保活 ——
ColorOS 对普通后台进程几秒就回收，没有前台服务的话「开好特效 → 退出 App → 去录屏」
会直接失效。在系统设置里「强行停止」应用会彻底结束它，下次打开 App 自动恢复。

> 注意：设备重启后 Shizuku 服务需要重新激活（这是 Shizuku 自身机制）；若设备已 root，
> 模块会直接走 `su`，不需要 Shizuku。

---

## ✅ 真机验证

设备：**一加 PLK110 / Android 17 / ColorOS 17 / KernelSU / LSPosed 2.2.1**，
录屏 App `17.5.4`（targetSdk 37）。用 `adb shell screenrecord` 与 ColorOS 原生录屏两条通道分别录制，
`ffprobe` 看帧数、抽帧统计亮度与点击点附近的时域帧差，再放大逐帧核对：

| 项目 | 结果 |
| --- | --- |
| 特效入镜 | 橙色扩散圆、双段白→淡蓝光弧、4 个三角火花全部录进视频，且以实际点击点为圆心 |
| 全黑问题 | 修复前 154s 录制只有 **7 帧视频**（音频 7216 帧）；修复后 29.1s/**973 帧**、6.6s/**497 帧**、4.6s/**370 帧**，全程非黑 |
| 窗口归属 | 录屏期间 `dumpsys window` 只有 `mDisplayId=0` 的一个 `MYclick-FX` 窗口 |
| 时序 | `ACTION_DOWN → relay（约 2ms）→ overlay`，单次点击只出一次特效，无重影 |
| 对齐 | 红色十字标记比对，特效中心与点击点重合，约 154px 的 Y 偏移已消除 |
| 设置页 UI 回归 | 2026-11-12 装上 `download/` 里的 release 包逐页截图：三页布局正常、示意图完整显示；修掉 Android 15+ 强制 edge-to-edge 导致的状态栏文字重叠、底部内容从导航下透出；实时预览抽帧可见扩散圆、双段光弧、4 个三角火花 |
| release 包录屏回归 | 同日 ColorOS 原生录屏 **105s / 1317 帧**（HEVC 1080×2354），全程非黑（桌面段 122/156/157、深色页 40/35/30）；4 次点击全部中继成功（`sink=true`、`active=true`），抽帧可见扩散圆、双段淡蓝光弧、4 个三角火花；`dumpsys window` 仍只有 `displayId=0` 一个 `MYclick-FX` |
| v1.0.39 Shizuku 模式特效入镜 | 用户反馈 Shizuku 模式录屏里没有特效、Root 模式有。`dumpsys SurfaceFlinger` 显示录屏 / 投屏虚拟屏只镜像主屏 `DisplayContent` 那棵树（`Display 10000 → Display 0(MirrorRoot) → (Mirroring) Display 0`）；Root 模式是 WMS 窗口，本来就在树里，而 Shizuku 模式的特效图层直接挂在 SurfaceFlinger 的 `ROOT` 下，和 `Display 0` 平级，不在镜像范围。改为：全部槽位挂到同一个父图层 `MYclick-ShellFx`，识别到录屏虚拟屏后用 `SurfaceControl.mirrorSurface` 镜像这个父图层，再挂到建在录屏屏幕图层栈上的容器 `MYclick-ShellFx-capture-<stack>` 下（镜像图层默认离屏，不挂父图层不会合成 —— 1.0.38 正是漏了这步）。以 `shell` 身份（uid 2000）强制镜像到投屏虚拟屏实测：镜像出现在图层树的根上且可达。Root 模式代码未改动 |
| v1.0.40 录屏特效对齐 | 1.0.39 录屏里有了特效，但位置整体偏向右下：录屏画面里系统触摸点在 (540,1410)、对应特效在 (640,1650)，比例约 1.18 = 1272/1080。ColorOS 录屏虚拟屏是 1080×2354，系统镜像主屏时给 MirrorRoot 加了「等比缩放 + 居中」，特效镜像没有。改为对特效镜像套用同一变换：`scale = min(录屏宽/主屏宽, 录屏高/主屏高)`，按缩放后尺寸居中；录屏尺寸与主屏当前方向尺寸一起轮询，旋转后自动重算。用户实测：录屏里的特效与屏幕上的点击位置一致 |
| v1.0.1 UI 回归 | 装上 `MYclick-1.0.1-release.apk`（versionCode 2）逐页截图：版本号 1.0.1、主卡图标与启动图标一致、底栏文字垂直居中；录屏抽帧确认高亮胶囊质心 `48 → 105 → 162` **连续过渡**（非瞬移），页面淡入淡出生效；目标包名自动识别为本机真实安装的 `com.oplus.screenrecorder` |
| 全局生效默认值 | 覆盖安装后冷启动设置页，root 读取模块 SP 确认 `globalEffect=false`（默认关闭）、`gateMode=0` |
| 全局生效修复回归 | 2026-11-12：ColorOS 会拦截 system_server 发往普通 App 静态接收器的广播（框架侧 `global sent` 有、App 侧 `recv` 为 0），改为**在 system_server 同进程挂载覆盖层**、ACTION_DOWN 经 TouchBus 直连。软重启后日志 `[android] 全局生效宿主已挂载`，三次点击均有 `global direct` + `overlay onTouch globalHost=true attached=true`；窗口由 `uid=1000 / package=android` 持有；**非录屏状态**短录屏抽帧可见白 → 淡蓝双段光弧 |
| v1.0.2 叠画修复 | 打开「全局生效」后再开录屏，用户真机确认每次点击只出一份特效。根因是录屏 App 常驻后台时错过了配置推送，快照里 `globalEffect` 仍为 `false`，一开录两套窗口就各画一份；现由 system_server 随每次 `ACTION_DOWN` 下发 `globalOwned` 归属标记，录屏进程收到即让位，归属信息跟着事件走、不再依赖配置同步 |
| v1.0.3 长截图卡顿修复 | 根因是 `HookGate.isSystemServer` 用 `startsWith("system:")` 判断，把 ColorOS 的 `system:ui` 也当成 system_server，于是在一个 SystemUI 进程里又挂了一套全屏覆盖层 + 输入 Hook（`dumpsys window` 里能看到两条 `MYclick-FX`，session 分别属于 system_server 和 `system:ui`）。改为精确匹配 `system_server` / `system`，并让所有 `system:*` 子进程直接跳过 Hook；同时把输入热路径上的 MOVE 过滤提前、配置刷新改异步、日志改惰性求值，`onDisplayChanged` 只在屏幕密度变化时才重建窗口。修复后 `dumpsys window` 里 `MYclick-FX` 只剩 1 条，session 归属 system_server，点击日志仍为 `global direct` |
| v1.0.7 移除无障碍模式 | 无障碍方案有三个改不掉的缺陷：ColorOS 持续提示「正在读取屏幕内容」、特效只能画在控件中心导致位置偏移、大量界面（Compose / Flutter / WebView / 游戏）不上报 `TYPE_VIEW_CLICKED` 而完全没有特效。上一版加回 Shizuku 模式后，无障碍模式已删干净：服务类、`accessibility_service_config.xml`、manifest 声明、`Config.overlayOnly` 全链路与设置页入口一并移除。同时 SCALE 默认值从 1.4 调为 1.0 |
| v1.0.8 冷启动恢复窗口 | 冷启动后 `LocalFxHost.sync` 从未被调用（只有改配置时才走 `save()`），所以重开 App 会看到窗口不在、事件源空转。改为 `onCreate` 里按配置同步一次，实测冷启动后 `MYclick-FX` 立即存在，`tap raw=(5000,10000) → (312.5,625.0)` 与理论值一致。同时验证了 ColorOS「正在当前页面悬浮显示」提示：改用 `TYPE_TOAST` 挂全屏窗口会被系统拒绝（dumpsys 里不出现窗口），只能继续用 `TYPE_APPLICATION_OVERLAY`；该提示是一次性安全提醒，点过「取消」后反复切换应用均未复现 |
| v1.0.6 Shizuku 模式 | 新增以 shell 身份读触摸屏的通道。真机实测：`shell` 属 `input` 组，`getevent` 可读 `/dev/input/event7`（`touchpanel`，量程 20351×44351，正好是屏幕 1272×2772 的 16 倍）。`sendevent` 模拟真实触摸验证映射：竖屏 `raw(6000,12000) → (375,750)`、横屏 `raw(6000,12000) → (750,897)`，均与理论值一致；录屏抽帧确认特效出现在手指位置。过程中修掉三个坑：`Shizuku.newProcess()` 在 13.x 已私有（改用 `IShizukuService.newProcess`）、`ShizukuProvider` 必须由 App 自己声明（否则 `pingBinder()` 恒 false）、提权探测必须离开主线程（未授权的 `su` 会阻塞到 ANR）。关闭无障碍后该模式独立工作 |
| v1.0.12 动画帧率 | 原先用 `Handler.postDelayed(16ms)` 排帧 —— 是「画完再等 16ms」，每帧的绘制与调度耗时都叠进间隔，跑起来只有 45~58fps 且越到后段越抖；更坑的是**继承了面板刷新率**（PLK110 报 120Hz）按 8.33ms 排帧，而 shell 身份的 root 图层拿不到 120fps（`setFrameRate(..., CHANGE_FRAME_RATE_ALWAYS)` 调用成功但 SurfaceFlinger 的 renderRate 仍停在 55~60），结果每帧 `lockCanvas` 都要白等两个 vsync，实测 **9.9ms/帧**。改为 `postAtTime` 固定节拍、直接从 60fps 起步（`framePeriodMs = max(面板周期, 16.67ms)`），并对实测帧率做单向校准（跑不动就往下调）。效果：**61fps、单帧 1.2~1.4ms**，稳定不抖 |
| v1.0.11 点击后卡顿 | 用户对照确认：关掉「启用特效」后滑动就不卡，问题在绘制。原先整屏 `SurfaceControl` 图层每帧 CLEAR + 上传 13MB（1272×2772×4B）并整块合成，录屏虚拟屏再合一次。改为按点击点开局部小图层：630×630（90dp 半径 × density 3.5），`setPosition` 精确落位，单帧绘制 2.4ms → 1.3~1.5ms，合成面积 3.53M → 0.40M 像素（降到 11%）。真机抽帧核实特效中心 (617,1646) 与点击点 (631,1643) 一致、弧线未被 buffer 边界裁切；连点 5 块槽位轮转，超过则复用最老的槽。踩坑：`setScale` 缩放锚点不是左上角会整体偏移（已弃用）；`sendevent` 注入缺 `ABS_MT_PRESSURE` 时驱动不上报坐标，会让「点击没反应」的假象出现 |
| v1.0.10 图层只挂主屏 | 之前 `setLayerStack` 是 @hide，反射被 hidden API 拦掉、失败也没声张，结果图层挂在**所有 display** 上 —— 录屏时那块虚拟屏也要额外合成一次，是「点完之后滑动发涩」的可疑来源之一。现在用 `VMRuntime.setHiddenApiExemptions` 解除限制，`setLayerStack(DEFAULT_DISPLAY)` 真正生效。另外把单帧绘制耗时打进日志（真机 1272×2772 全屏 buffer ≈ 2.4ms/帧，29 帧合计约 70ms，不是卡顿主因）；试过用 `setScale` 把 buffer 缩到 1584×727（1.25ms/帧），但它的缩放锚点不是左上角、特效整体偏移，已回退 |
| v1.0.10 退出 App 后仍工作 | 绘制进程独立读触摸屏（`getevent`）、自己判定录制、自己画，App 只负责下发配置；再配一个前台服务保活 App 进程（Shizuku 的 `newProcess` 子进程会随调用方死亡被回收，实测 App 被杀 4 秒后绘制进程一起消失）。真机验证：按 Home 退出界面 30 秒后 App 进程、绘制进程、`getevent`、前台服务全部存活；此时开 ColorOS 录屏 → 日志 `FX rec on` → 模拟点击 → `FX tap ... active=true` → `FX idle after 29 frames`（App 全程在后台）。重开 App 时按 `/data/local/tmp/myclick-fx.pid` 清掉残留实例，不会出现两份特效 |
| v1.0.10 彻底去掉悬浮窗提示 | ColorOS 对 `TYPE_APPLICATION_OVERLAY` 每次显示都会弹「正在当前页面悬浮显示」，`TYPE_TOAST` 又被系统拒绝，改用 `app_process` 挂窗口则被 WMS 以 `Unknown pid / uid` 挡住（进程没在 AMS 注册，`openSession()` 直接失败）。最终方案：Shizuku 起一个 `shell` 身份的 `app_process` 子进程，进程内用 `SurfaceControl` 建 root 图层直接画 —— 没有窗口、没有 AppOps 操作，真机实测点击特效正常显示、能录进录屏、屏幕上再无任何提示条。踩到的坑：`Choreographer` 在该进程里永远不回调（改 Handler 固定节拍）、ColorOS 的 `ApplicationSharedMemory` 未初始化、`cmd display get-displays` 在受限 SELinux 域下拿不到输出（改用 `DisplayManager` 枚举私有虚屏，仍按 `RecordingScreen` 名字排除 `com.oplus.cast`） |
| v1.0.9 关闭全局后不再全程出特效 | Shizuku 模式的宿主跑在模块进程里，拿不到录屏进程那套 `RecordingState`，于是之前只按总开关放行 —— 关掉「全局生效」仍然满屏出特效。新增 `RecorderWatcher`：用一个常驻 shell 进程在设备侧轮询 `cmd display get-displays`，按输出里的 `owner <录屏包名>` 判断是否在录屏（每次约 40ms，只在状态翻转时回一行）。**不能按 `dumpsys media_projection` 判断** —— ColorOS 录屏根本不登记那一路会话，而 `com.oplus.cast` 的投屏会长期占着一路，按它判断会一直当作「在录」。实测：待机 `MYclick-FX` 窗口数为 0、点击 `attached=false` 不出粒子；开录 1 秒内翻成 `录制开始，特效放行`、窗口挂上、抽帧可见特效；停录立刻 `录制结束`、窗口归零；「全局生效」打开时不依赖录制状态，待机也常驻绘制 |

<details>
<summary><b>排障速查表</b></summary>

| 现象 | 原因 / 处理 |
| --- | --- |
| 完全没有特效 | 多半只勾了录屏 App、没勾「系统框架」，或没重启。看模块日志里 `[android] 系统框架触摸源已安装` |
| 日志里什么都没有 | ColorOS 过滤普通 App 的 `android.util.Log`；改看 LSPosed 模块日志。灭屏 / Dozing 时本来就没有输入事件 |
| 只有点悬浮球才有特效 | 系统框架 Hook 未生效 |
| 特效比点击慢半拍 | 隐式广播被放进延迟队列（实测约 500ms）；确认 `setPackage` + `FLAG_RECEIVER_FOREGROUND` |
| 一次点击画两遍 | `HookGate` 进程级一次性安装 + 40ms/16px 去重；检查是否把额外 App 勾进作用域 |
| 录屏全黑 / 只有几帧（音频正常） | 覆盖层误挂到 AUTO_MIRROR 虚拟屏。`dumpsys window windows \| grep MYclick-FX` 应只有 `mDisplayId=0` 一个；升级后 `am force-stop com.oplus.screenrecorder` |
| 特效出现但录屏里没有 | 检查是否被加 `FLAG_SECURE`（本模块不加）、是否用了「仅录制单个 App」模式 |
| 特效位置偏移 | 窗口 inset；当前用 `setFitInsetsTypes(0)` + `getLocationOnScreen()` 补偿 |
| 原生白点还在 | `hideNativeDot` 未生效；`adb shell settings put system show_touches 0` 临时验证 |

</details>

---

## 📁 目录结构

```
MYclick/
├── download/                       # 可直接安装的 release APK（测试统一用这个）
├── docs/images/                    # 效果示意图、设置页三页真机截图、预览抽帧与录屏验证帧
├── app/
│   ├── build.gradle.kts            # compileOnly Xposed API，不打包进 APK
│   ├── libs/xposed-api-82.jar      # 随工程交付，离线可构建
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml     # xposedmodule / xposedminversion=93 / xposedscope
│       ├── assets/xposed_init      # com.myclick.HookEntry
│       ├── res/                    # 深浅双主题（values / values-night）、导航图标、效果示意图
│       └── java/com/myclick/
│           ├── HookEntry.kt                 # LSPosed 入口，运行时动态判定作用域
│           ├── bridge/                     # HookBridge / XposedBridgeImpl
│           ├── core/                       # Config / Prefs / Provider / TouchRelay / 状态与诊断
│           ├── fx/                         # EffectEngine / EffectSurfaceView
│           ├── hook/                       # 框架 Hook / 录屏运行时 / 中继 / HookGate
│           ├── overlay/                    # 悬浮窗 + 虚拟屏过滤 + 多显示器
│           ├── shell/                      # app_process 绘制进程：SurfaceControl 图层 / 协议 / 录制判定
│           ├── touch/                      # Shizuku 通道 / 触摸屏探测 / evdev 事件源
│           └── ui/                         # SettingsActivity / UiKit / 颜色选择 / 底栏切换动画
├── LICENSE
└── README.md
```

---

## 📄 许可与说明

[Apache-2.0](LICENSE)。本项目是个人设备增强用途的 LSPosed 模块，
**不含原厂 APK 的任何代码或资源**；逆向结论仅用于定位 Hook 点。

Hook 系统框架只在 `before` 阶段读事件、不改参数，出错全部吞掉；
仍建议先备份数据、熟悉自己设备的救砖流程再折腾。
