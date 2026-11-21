# OPPO 相机解锁（CamUnlock）

**OPPO X9（PLJ110）的相机增强模块。** 把 Reno 17 Pro 的相机色彩资产补到本机：调色盘、胶片滤镜、
灵感构图（AI 构图），并给后续适配别的机型留了一层可读的适配日志。

CamUnlock 由 **KernelSU / Magisk 模块**和 **LSPosed 设置应用**组成：模块通过 bind 挂载补 `/odm`
下的相机资源，钩子在相机进程内改配置返回值、滤镜门控和取帧链路。停用模块即可完整还原。

- 仓库：<https://github.com/LucasBlackLu/OPPOCameraUnlock>
- LSPosed 模块包名：`com.oppocamera.unlock`
- 当前只在一台机器上实测：**OPPO X9（`PLJ110` / ColorOS 17 / Android 17 / 相机 7.016.29）**

---

## 功能

### 调色盘

Reno 17 的调色盘是「一整套 LUT + 一条三维查表流水线」，不是单个 UI 控件。本机原厂
`/odm/etc/camera/basictone` 里**根本没有 `palette/` 目录**，强开 feature 会在
`libCombineLut` 里 `std::bad_alloc`。

- **做法**：LSPosed 模块把 `assets/basictone`（`palette/HDR|SDR/SC16Lut0..8` + `setting/*.ini`）
  部署到相机私有目录 `/data/user/0/com.oplus.camera/files/campalette/basictone`，
  再把 `CombineLutHelper.init(6 参)` 的 `arg[2]=setting`、`arg[5]=palette` 换过去。
- **部署内容 = 设备原厂整份 basictone + 我们的 palette**：`lmt`（色彩矩阵）、`odt`、`setting_dolby`、
  `vig`、`setting/Ditherout|FromXml|TriggerBIN` 全部从 `/odm` 递归拷过来，只加 palette
  和那几个原厂没有的 `SimTool_Palette_*.ini`。少 `lmt` 会让大师模式预览发灰。
- **模式白名单**：`CommonUtil.isSupportPalette(mode)` 原厂只放行 `common / portrait / night`，
  这里 hook 成恒真（`persist.camunlock.palette.allmode`），照片 / 人像 / 夜景都能调出调色盘。
  大师/专业模式例外：那里**不注入、也不放行入口**（见下）。
- **大师模式不注入**：实测注入 basictone 会把大师的 tone 打平（同画面 A/B：注入 0.026/87，
  不注入 0.054/148，亮度与饱和度都掉一半）。所以按 `isSupportPalette(mode)` 记下的当前模式，
  大师/专业直接走原厂路径、入口也回落到原厂白名单。要旧行为（大师里也有调色盘）：
  `persist.camunlock.palette.master 1`（换 7.016.29 相机时必须开，否则 native 崩）。
- **格子颜色**：换过 Reno 的 unit.sdk 后 `ipusdk.PaletteSeekbarColorTable` 存在，用真实现算色；
  不存在时才退回 stub（否则格子会被 `Recipe.empty()` 画成全黑）。

### 胶片滤镜

| 层 | 做了什么 |
|---|---|
| 滤镜清单 | `com.oplus.camera.unit.sdk.jar` 的 `classes.dex` 里是一张「滤镜 id → `R.string.camera_filter_*`」表。本机 91 处、无 `hu_po`；Reno 版 93 处、含 `hu_po`(琥珀)/`qing_tou`(清透) → 整包换上（含 `oat/arm64/*.odex`、`*.vdex`） |
| 胶片 LUT | `/odm/etc/camera/meishe_lut`，路径**硬编码在 native** `libAlgoInterface.so`，Java 改不了 → 只能整目录 bind（135 个文件） |
| Meishe 引擎 | 新 unit.sdk 会给引擎设 `Custom LUT File Path` / `Custom LUT Factor`，原厂 `libmsnativefilter.so` 没有这两个 paramId（日志刷 `Invalid paramId`、滤镜静默失效）→ 换 Reno 版（11.27 MB），报错归零 |
| 滤镜组门控 | `FilterGroupManager` 的 32 个 `init*FilterGroup` 三层门控：静态字段 `sbXxxSupport` → 反射置 true；`isXxx()` → hook 成 true；`getConfigBooleanValue(key)` → 白名单强制 true |
| 同名去重 | 原厂表里同一组会**重复加同一条滤镜**（视频/人像/照片组都把「和光」加了两次，dexdump 里同一组复用 v1/v2），界面上就是两个「和光」。这里按显示名在组内只留第一条，日志写明跳过了哪条（`persist.camunlock.filter.dedup`，默认开） |

### 灵感构图（AI 构图）

相机本机带这套能力，feature 默认关着；模块把「入口 → 出图 → 引导」三段接上：

| 阶段 | 做法 |
|---|---|
| 入口 | `CameraConfig` 里含 `aicomposition` / `ai_composition` 的 key 一律放行；`FeatureConfigureAdapter` 返回的 String 集合里补 `com.oplus.camera.feature.ai_composition`（只动 Collection，不碰 `getConflictMap` 的 map，否则 `FeatureConflictDecision.f()` 里 `ClassCastException` 闪退） |
| 取帧 | 挂预览帧类的帧方法（候选 `tb.u / tb.q / tb.d / wb.r / rb.t`，按「11 参 + ≥2 个 `HardwareBuffer`」匹配，不认方法名），挑带 GPU 用法位、面积最大的那张存 ThreadLocal |
| 出图 | 帧 → Bitmap → 喂相机 AIUnit 云单元 `cloud_ai_shot_suggestions`，拿 `{"data":{"zoom":…,"center":[…]}}` |
| 翻译 | `zoom` / `center` 反算裁剪框，拼成相机推荐列表认的 `{"outputNum":1,"crops":[…]}` |
| 投递 | `runCompositionStreaming(HardwareBuffer, Callback)` 返回后调回调 `onEvent(1/2, json)` |
| 引导 | 相机自带 native 跟踪器本机一直判「画面变化较大」，改用陀螺仪角速度积分回传 `{cx, cy, 0, 3}` |

AIUnit 接入靠 1.5 KB 的桥接 dex（`assets/aiunit/feiyu_aiunit_bridge.dex`），在相机进程里用相机的
ClassLoader 当 parent 动态加载。

### UI 换图（不替换相机 APK）

灵感构图的图标、入口引导图、调色盘按钮这些**图片**可以只换图不换 APK：
把 7.016.29 里改动过的图放进 `app/src/main/assets/ui/`（配 `overrides.json`），
运行时按**资源名**替换（`ImageView.setImageResource` / `View.setBackgroundResource` 两个入口）。

- 换了几张、有没有就绪，`adapt.log` 里直接看：
  `HIT ui.skin → hooked=2，映射 5 项 …`、`UI 资源就绪：filter_palette ← filter_palette.png（256x256）`、
  进面板时的 `UI 替换：…`。
- 生成/更新：`python _tools/make_ui_assets.py`（数据来自 `_tools/extract_ui.py` 的两版对比）。
- 开关 `persist.camunlock.ui`（默认开）；`persist.camunlock.ui.trace 1` 会把相机请求的资源名记下来，
  换机型时用来确认名字是不是同一套。
- 说明：`layout/`、`shape`、`gradient` 这类资源不是图片，换不了也不该换；
  相机若用 canvas 自绘（比如调色盘入口本身），不会走上面两个入口。

### 日志（诊断与导出）

- 文件：`/data/user/0/com.oplus.camera/files/camunlock/adapt.log`（logcat 里 tag `CamUnlock`）。
- 设置页「日志」区现在可以：开关写日志、开关细节 dump（滤镜组条目 / UI 替换明细）、
  **一键导出到 `/sdcard/Download/camunlock-adapt-*.log`**、清空日志（导出后直接分享给作者就行）。

### 适配与日志（给换机型用的）

hook 目标全部登记在 `Adapt.kt`：**id + 一句话说明 + 候选类名/方法指纹**。启动结束打一张自检表：

```
设备（最终）：model=PLJ110 market=OPPO Find X9 · ColorOS=PLJ110_17.0.0.101(CN01) (API 37) · 相机=7.006.121
模块（最终）：com.oppocamera.unlock 2.0.0
文件 胶片 LUT：琥珀：/odm/etc/camera/meishe_lut/hu_po.bin → 824183 B
文件 unit.sdk（滤镜清单）：/product/framework/com.oplus.camera.unit.sdk.jar → 5363803 B
适配自检：HIT 19 / MISS 1，耗时 88 ms
  HIT  camera.config      → d/getConfigBooleanValue hooked=2
  HIT  combine.init       → hooked=1（arg[2]=setting / arg[5]=palette）
  HIT  filter.groups.init → hooked=32
  HIT  frame.source       → wb.r#f（11 参 / 3 个 HardwareBuffer）
  HIT  ui.skin            → hooked=2，映射 5 项：…
  MISS inspiration.trace  → 候选都不存在：pb.b$a
```

- 某个目标 MISS 只让**那一个功能**不生效，其它照旧；换机型时只需要改候选表，不用碰各个 hook。
- `persist.camunlock.log.dump 1` 会把每个滤镜组的条目（显示名 + LUT）也打出来，验证/适配都靠它。
- 完整的适配步骤、候选表对照、验证清单见 **[docs/适配机型.md](docs/适配机型.md)**。

---

## 安装

需要 root（KernelSU，exploit 注入的那种**重启会掉 root**）并装好 LSPosed。

1. 构建出 `com.oppocamera.unlock-*.apk` 和 `oppo_camera_port_reno17p.zip`（见「构建」）。
2. 装 LSPosed 模块：`adb install -r com.oppocamera.unlock-*.apk`，在 LSPosed 里启用并把作用域勾上「相机」。
3. 刷 Magisk / KernelSU 模块：

   ```sh
   adb push oppo_camera_port_reno17p.zip /data/local/tmp/
   adb shell su -c "ksud module install /data/local/tmp/oppo_camera_port_reno17p.zip"
   ```

4. 让文件层生效：`adb shell su -c "stop; start"`（只重启 framework，**别整机重启**）。
5. 打开本应用点「重启相机」。

**尽量不动系统文件**时，只装 LSPosed 模块、再 `touch /data/adb/modules/oppo_camera_port/minimal`
即可：调色盘 + 灵感构图照常，`/odm`、`/product` 一个字节都不换（代价是少胶片 135 支 LUT、琥珀/清透）。

回滚：KernelSU 管理器停用/删除模块，然后 `pm uninstall com.oplus.camera` 可把相机整包回退到 `/product/app` 原厂那份。

---

## 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

Gradle 9.7.1 / AGP 9.4.1 / Kotlin 2.4.20 / compileSdk 37 / minSdk 33。

Magisk 模块：

```sh
python magisk/build_module.py              # 不打相机整包（默认）
python magisk/build_module.py --with-apk   # 连 payload/apk/ 一起打（会替换本机相机）
```

CI（`.github/workflows/build.yml`）：push 到 `main` / PR / 手动 → JDK 21 +
`platforms;android-37.0` + `assembleDebug`；打 `v*` tag 时把 APK 挂进 Release。

---

## 适配其他机型

1. 先看 `adapt.log` 的自检表：哪些目标 MISS，`MISS` 行里写了「试过哪些候选」。
2. 只改 `Adapt.kt` 里候选表 / `CameraPaletteModule.kt` 顶部的候选列表（取帧类、灵感类、滤镜类…），
   不要散到各个 hook 里。
3. 文件层要对照目标机自己的 `/odm/etc/camera/basictone`：本机的 `lmt/odt/vig/setting_dolby`
   必须在部署目录里齐全，否则大师模式预览会发灰。
4. 相机版本不同，混淆名会变：本仓库按 7.006 / 7.013 / 7.016 三版对齐过
   （`wb.r / rb.t` → `tb.u / tb.d` → `tb.q`）。

---

## 目录

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/oppocamera/unlock/hook/` | 钩子：`CameraPaletteModule.kt`（调色盘/滤镜/构图）、`Adapt.kt`（适配层与自检日志） |
| `app/src/main/java/com/oppocamera/unlock/` | 设置界面（Compose）、`Shell.kt`（重启相机） |
| `app/src/main/assets/basictone/` | 部署包：`palette/HDR|SDR/SC16Lut0..8` + `setting/*.ini` |
| `app/src/main/assets/ui/` | UI 换图：`overrides.json` + 图片（运行时按资源名替换相机 drawable） |
| `app/src/main/assets/aiunit/` | 灵感构图桥接 dex |
| `magisk/` | Magisk 模块脚本与打包（`payload/` 内容由固件提取，不入库） |
| `docs/` | `适配机型.md`（换机/换版本的完整步骤）、`实现说明.md`（各功能原理）、`DECRYPTED_CONFIG.md`（配置解密笔记） |

---

## 开关一览

| 开关 | 作用 |
|---|---|
| `persist.camunlock.palette.allmode` | 调色盘在照片/人像/夜景可见（大师始终不放行），默认开 |
| `persist.camunlock.palette.master` | 让大师模式也注入+放行调色盘（会发灰；7.016.29 相机必须开，否则崩），默认关 |
| `persist.camunlock.filter.dedup` | 滤镜同组同名去重（和光只留一条），默认开 |
| `persist.camunlock.filter.director` | 放行导演联名滤镜（姜文 / 贾樟柯），默认关 |
| `persist.camunlock.filter.{lively,festival,ceremony}` | 鲜活 / 节日 / 庆典，默认关（LUT 未随固件提供） |
| `persist.camunlock.log` | 写日志（logcat + `files/camunlock/adapt.log`），默认开 |
| `persist.camunlock.log.dump` | 额外打滤镜组内容等细节，默认关 |
| `persist.camunlock.ui` | UI 换图（用 `assets/ui` 的图替换相机资源），默认开 |
| `persist.camunlock.ui.trace` | 打印相机每次请求的 drawable 资源名（换机型核对用），默认关 |
| `persist.camunlock.palette.off` | 诊断：完全不改 LUT 路径（7.016.29 相机会 native 崩，仅供排查） |
| `persist.camunlock.palette.device_setting` | 诊断：setting 用原厂那份（原厂缺 `SimTool_Palette_*`，会崩） |
| `touch /data/adb/modules/oppo_camera_port/minimal` | 不替换任何系统文件，只保留 LSPosed 侧功能 |

---

## 已知问题 / 未验证

- **大师模式预览发灰（已修）**。原因不是相机版本：7.006.121 / 7.013.60 / 7.016.29 三个版本
  配本模块都灰，卸载回原厂才好 —— 是调色盘的 basictone 注入把大师的 tone 打平了。
  现在大师/专业模式不注入（见「功能 · 调色盘」），真机 A/B：大师 0.0455 / 114、照片 0.0461 / 114，
  修复前是 0.026 / 87。代价是大师模式里没有调色盘入口。
- **调色盘注入对非大师模式是必须的**：不注入 palette 目录时（`persist.camunlock.palette.off 1`）
  7.016.29 相机直接 `SIGSEGV`（`libCombineLutJni.processV3`、`addr=0x20`）—— 原厂 `/odm` 里没有 palette；
  原厂 7.006.121 不受影响（它本来就不带 palette 那套）。
- **相机整包不再随模块发布**：`payload/apk/` 默认不打进 zip，`service.sh` 只在你自己放了
  APK 时才补装；当前实装相机 = 设备原厂 7.006.121。
- **POP / 实况调色盘不在本分支**：`retro_camera_mode` 相关 hook、retro 配置补丁、`setting_Retro`、
  retro 胶囊 UI 全部在 `main` 与 `backup/pop-wip-20261121` 分支上；模式条与配置层保持设备原厂。
- **鲜活 / 节日 / 庆典**：LUT（`vibe/festival/ceremony.rgba.bin`）两台机器固件里都不存在，开了只多条目。
- **未上机验证**：`--with-apk` 打包路径、其他 ColorOS 版本、前置摄像头的调色盘、
  4K60 视频下的滤镜、相册重新编辑、横屏布局下的调色盘面板。
- **分析 APK 一定要用设备实装的那份**（`/data/app/…/base.apk`）；仓库 `payload` 里另一份同名构建
  dex 全不同，拿它逆会得出错误结论。

---

## 许可证与致谢

本仓库未声明开源许可证（默认保留所有权利），使用前请先确认机型与风险。

- [libxposed API](https://github.com/libxposed/api) —— 模块框架
- PUI 设计系统：作者 **天伞桜**（[GitHub](https://github.com/sakurakingfifth-debug/PUIInstaller) ·
  [酷安](https://www.coolapk.com/u/540690)）—— 界面骨架
- 灵感构图取帧桥：参考 **KaMuaMua2020**（酷安 `u/26800889`）
- 适配层写法参考 [OmniCam-Muse](https://github.com/benbaobaoshigemi/OmniCam-Muse)
  （symbols 指纹匹配 + OptionalHook + Diagnostics 自检的思路）
