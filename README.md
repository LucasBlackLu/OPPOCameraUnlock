# OPPO 相机解锁

[![build](https://github.com/LucasBlackLu/OPPOCameraUnlock/actions/workflows/build.yml/badge.svg)](https://github.com/LucasBlackLu/OPPOCameraUnlock/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/LucasBlackLu/OPPOCameraUnlock?include_prereleases&sort=semver)](https://github.com/LucasBlackLu/OPPOCameraUnlock/releases)

给 ColorOS 相机补上两样东西：**调色盘（LUMO Palette）** 和 **灵感构图（AI 构图）**。

LSPosed 模块，基于 **libxposed API 101**，界面复用 PUI 设计系统。

- 仓库：<https://github.com/LucasBlackLu/OPPOCameraUnlock>（仓库 OPPOCameraUnlock，包名 com.oppocamera.unlock）
- 包名：`com.oppocamera.unlock`
- 下载：<https://github.com/LucasBlackLu/OPPOCameraUnlock/releases/latest>

![图标](docs/icon-preview.png)

图标参考 GCam 画的：白机身 + 六片彩色虹膜 + 右上角「灵感构图」星光；
另带一份单色层（`ic_launcher_monochrome`），ColorOS 的莫奈取色 / Android 13+ 主题图标
会直接按壁纸调色板给它染色。

---

## 配置来源

`app/src/main/assets/basictone/` 是 **X10 Pro Max** 的 `/odm/etc/camera/basictone` 提取包（`basictone.zip`）里与调色盘相关的部分，一字未改搬进来：

```
assets/basictone/
├── setting/                     ← 整目录（11 个文件）
│   ├── SimTool.ini              ← 主色调映射表
│   ├── SimTool_Dolby.ini
│   ├── SimTool_Master.ini
│   ├── SimTool_Photo_Master.ini
│   ├── SimTool_Palette_Photo_Normal.ini
│   ├── SimTool_Palette_Photo_Night.ini
│   ├── SimTool_Palette_Photo_Portrait.ini
│   ├── SimTool_Preview_Palette_Normal.ini
│   ├── FromXml
│   ├── TriggerBIN
│   └── Ditherout
└── palette/
    ├── HDR/SC16Lut0..8          ← 16³×3×float32 = 49152 B/张
    └── SDR/SC16Lut0..8
```

重新生成（换了提取包时）：

```powershell
python tools\gen_assets.py
```

`tools\gen_assets.py` 只从 zip 里取 `setting/`、`palette/HDR/`、`palette/SDR/` 三个前缀，其余（`lmt/`、`odt/`、`vig/`、`setting_dolby/`，约 60 MB 二进制 LUT）不进 APK。

`tools\cmp_basictone.py` 用来核对提取包与机器 `/odm` 的差异（实测 71 个同名文件里 67 个 md5 一致，`palette/` 整目录缺失）。

## 部署

首次进相机时铺到相机自己的私有目录：

```
/data/user/0/com.oplus.camera/files/campalette/basictone/
├── .extract                     ← 版本戳，如 x10-1.1.0，一致就跳过重铺
├── setting/                     ← assets/basictone/setting 原样
├── palette/HDR/                 ← assets/basictone/palette/HDR 原样
├── palette/SDR/                 ← assets/basictone/palette/SDR 原样
└── odt/                         ← 从 /odm/etc/camera/basictone/odt 拷贝
```

`odt` 四张 ODT 共 12 MB，机器上已有同一份，不再塞进 APK；缺 `ODT_Pre` 时用 `ODT_Photo` 顶替。

铺完校验 `palette/HDR/SC16Lut8` 与 `palette/SDR/SC16Lut8` 都是 49152 字节，否则放弃重定向，避免半套 LUT 把相机搞崩。

## Hook

| 目标 | 动作 |
|---|---|
| `com.oplus.camera.configure.CameraConfig.d(String,ZZ)` | `com.oplus.feature.color.palette.support` 返回 true |
| `com.oplus.ocs.camera.CombineLutHelper#init`（6 参） | 第 6 参含 `palette` 时，把 setting / palette / odt 换成部署目录 |
| `...ipusdk.processunit.combinelut.CombineLutProcessUnitSDK#init` | 同上（本机无此类，自动跳过） |
| `PaletteColors#get(String)` / `PaletteSeekbarColors#get(String)` | 返回 stub Recipe；否则 Seekbar inflate 时因缺 `PaletteSeekbarColorTable` 抛 `NoClassDefFoundError` 闪退 |

日志标签 `CamPalette`（LSPosed 日志里看）。

## 使用

1. 安装 APK
2. LSPosed 启用模块，作用域勾 **相机**（`com.oplus.camera`，已静态声明）
3. 打开本应用，点「重启相机」
4. 相机 → 照片 → 左下角橙色花朵 → 调色盘

## 编译

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-21.0.11"
.\gradlew.bat :app:assembleDebug
```

Gradle 9.7.1 / AGP 9.4.1 / Kotlin 2.4.20 / compileSdk 37 / minSdk 33。

## CI

`.github/workflows/build.yml`：

| 触发 | 动作 |
|---|---|
| push 到 `main` / 提 PR / 手动 | JDK 21 + `platforms;android-37` + `assembleDebug`，APK 传成 artifact `com.oppocamera.unlock-debug` |
| 打 `v*` tag | 在上面基础上，把 APK 挂进 GitHub Release（`检查更新` 指的就是 releases/latest） |

发版：

```bash
git tag v2.0.0 && git push origin v2.0.0
```

> CI 里踩到的坑：`compileSdk 37` 对应的包名不是 `platforms;android-37`，而是
> `platforms;android-37.0`，而且只在 **canary 频道**里 —— 所以装 SDK 的命令是
> `sdkmanager --channel=3 "platforms;android-37.0" "build-tools;36.0.0"`。

## 实测

- `deployed (extracted=29) -> .../campalette/basictone files=34`，二次启动走 `deployed (cached)`
- `palette init code=0`（LUT 全部读成功）
- 调色盘面板正常打开：影调 / 饱和度 / 滤镜强度 / 柔光 + 胶片样式（和光 / Portra 胶片…），拖动实时生效，相机不闪退
- 部署目录 29 个配置文件 md5 与提取包逐一相同

## 致谢

- [libxposed API](https://github.com/libxposed/api) —— 模块框架（API 101）
- PUI 设计系统：来自 [PUIInstaller](https://github.com/LucasBlackLu/PUIInstaller)（作者 panl）—— 界面骨架
- `com.linggan.goutu`（AI 构图）—— 灵感构图的取帧 + 云端接力思路是从它身上逆出来的，
  本仓库按同样的路子重写成了自己的一套
- 作者酷安主页：[coolapk.com/u/26800889](https://www.coolapk.com/u/26800889)

---

## 灵感构图

相机本机带这套能力，只是 feature 默认关着。模块自己把「入口 → 出图 → 引导」三段都接上：

| 阶段 | 做法 |
|---|---|
| 入口 | `CameraConfig.d` 里名字含 `aicomposition` / `ai_composition` 的 key 一律放行；`FeatureConfigureAdapter` 返回的 String 集合里补 `com.oplus.camera.feature.ai_composition`（只补 Collection，**不碰** `getConflictMap` 的 map —— 它的 key 是 `FeatureKey`，塞 String 会在 `FeatureConflictDecision.f()` 里 `ClassCastException` 闪退） |
| 取帧 | 挂预览帧类 `wb.r` 的 `f(...)`（11 参），参数里挑带 GPU 用法位、面积最大的那张，存 ThreadLocal。构图调用自己带进来的 buffer 常是 `RGB_888`/私有格式，转 Bitmap 会全黑，所以优先用取帧类抓到的那张 `RGBA_8888` |
| 出图 | 帧 → Bitmap → 喂给相机的 AIUnit 云单元 `cloud_ai_shot_suggestions`，拿回 `{"data":{"zoom":1.8,"center":[384,563],"reason":"…"}}` |
| 翻译 | `zoom`/`center` 反算裁剪框，拼成相机推荐列表认的 `{"outputNum":1,"crops":[{…srcCrop, compositionDesc}]}` |
| 投递 | `runCompositionStreaming(HardwareBuffer, SdkCompositionStreamCallback)` 返回后，调回调的 `onEvent(1, json)` / `onEvent(2, json)`，并把原方法返回值改成 true |
| 引导 | 相机自带的 native 跟踪器在这台机器上一直判「画面变化较大，已退出本次构图」，所以 `initializeTracker` / `track` 的返回值整个换掉：用陀螺仪角速度积分算偏移，回传 `{cx, cy, 0, 3}` |

AIUnit 的接入靠一份 1.5 KB 的桥接 dex（`assets/aiunit/feiyu_aiunit_bridge.dex`）：

```
com.trae.feiyu.cloud.FeiyuFrameDetector  extends com.oplus.aiunit.core.base.FrameDetector
com.trae.feiyu.cloud.FeiyuConnector      extends com.oplus.aiunit.core.a
```

它只有声明，实现全在相机里（`com.oplus.aiunit.*`），所以必须在相机进程里用相机的
ClassLoader 当 parent 动态加载（`InMemoryDexClassLoader`）。

几个版本差异（踩过）：

- 本机 AIUnit 的「跑一次」是 `FrameDetector.f(j, k): ErrorCode`，不是老版本那个
  `process(input, output): void`；
- 输入/输出 slot 的请求/返回都在各自那个 String 字段里（`j.c` / `k.d`），
  `setFrame(Bitmap, Boolean)` 返回 0 才算这一帧被接受；
- 投递窗口 30 秒：相机每十几秒会再调一次 `runCompositionStreaming`，每次都重投的话
  它会按新裁剪反复变焦，观感上像「画面一直在跳」。

日志：LSPosed 模块日志里搜 `CamUnlock`（`已送入推荐 …` / `跟踪回传 …`），
桥接内部日志同时落在 `/sdcard/Android/data/com.oplus.camera/files/aicomp.log`。

实测（PLJ110 / ColorOS 17 / 相机 7.006.121）：只启用本模块（`com.linggan.goutu` 关闭），
相机里「灵感构图」入口出现，指向笔记本后云端回 `zoom=2.2 / center=[422,512]`，
相机自动变焦到 2x 并显示「笔记本电脑主体位于画面中心，精准聚焦核心设备」的构图说明。
