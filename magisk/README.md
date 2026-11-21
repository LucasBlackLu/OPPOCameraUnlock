# OPPO 相机 调色盘 / 胶片 移植模块

> ⚠️ **仅在 OPPO X9（`PLJ110`，ColorOS 17 / Android 17）上测试可用。**
> 其他机型没有验证过，挂载路径、SELinux 标签、相机版本都可能不同，**刷之前先备份，出问题直接删模块重启即可回滚**。

配套的 LSPosed 模块（hook 层）在仓库根目录：`com.oppocamera.unlock`。

## 最小替换模式（推荐先这样试）

只要模块目录里有 `minimal` 这个空文件，本模块**一个 /odm、/product 文件都不换**：

```sh
su -c "touch /data/adb/modules/oppo_camera_port/minimal"
su -c "stop; start"
```

这时只有 LSPosed 模块生效：调色盘靠它把 palette/setting 部署到相机私有目录 + 改
`CombineLutHelper.init` 的路径参数，**系统分区一个字节都不动**（`/odm` 上原有的 lmt/odt 照读）。
代价是少了「胶片 135 支 LUT」「琥珀/清透滤镜名」「Meishe 引擎」这三项 —— 它们必须换 `/odm` 文件。

`payload/apk/` 里的相机整包**默认不打进 zip**（`build_module.py` 会跳过），
所以刷模块不会替换你本机的相机；确实需要整包时用 `python magisk/build_module.py --with-apk`。

## 这模块干什么

把 Reno 17 Pro 固件里的相机资源补到本机。**只做文件替换**（hook 和相机重启由 LSPosed 模块负责）。

| payload | 目标 | 说明 |
|---|---|---|
| `payload/apk/OplusCamera.apk` | 补装成 data app | 相机 7.016.29（`version.txt` 里写目标版本）。开机等 `sys.boot_completed` 后比对版本，不一致就 `pm install -r -d -g` |
| `payload/meishe_lut/`（135 个） | `/odm/etc/camera/meishe_lut` | 胶片 LUT（`.MS-LUT` + RGBA 图层）。含琥珀 `hu_po.bin`、清透 `qing_tou.bin`、portra400 / positive / ccd / polaroid / gt-* |
| `payload/apk_resources/` | `/odm/etc/camera/apk_resources` | 滤镜资源（原厂 95 → 135 个 LUT，纯增量） |
| `payload/framework/` | `/product/framework` | `com.oplus.camera.unit.sdk.jar`＋oat（滤镜清单：琥珀/清透） |
| `payload/lib64/libmsnativefilter.so` | `/odm/lib64/` | 带 `Custom LUT` 参数的 Meishe 引擎（原厂的没有，会刷 `Invalid paramId`） |
| `payload/lib64/libBasicTonePhoto.so` | `/odm/lib64/` | Reno 版带 6 个 palette 相关符号（原厂 0 个），调色盘走 BasicTone 时用 |

> 配置层（`/odm/etc/camera/config/camera_unit_config`、`camera_unit_feature_config.protobuf`）
> **这一版不再替换**，保持设备原厂文件。retro/POP 的配置补丁属于试验分支，不在这条发布线里。

## 为什么是 bind 挂载，而不是 Magisk 的 system/ 覆盖

- `/odm` 是独立的 erofs 只读整块挂载，KernelSU 的 overlay 只作用于 `/system`，`payload` 不能靠 `system/` 自动叠。
- `/product/framework` 是 **overlay**（`lowerdir=/my_product/product_overlay/framework:/product/framework`），
  必须绑**合并后的路径** `/product/framework/...`，绑 lowerdir 不生效。
- payload 放的是合并后的完整目录/文件，`post-fs-data.sh` 里逐个 `mount --bind`，卸载即还原，不写任何只读分区。
- SELinux 标签必须对，否则**静默失效**（不报错）：
  `/odm` 文件 `u:object_r:vendor_configs_file:s0`、`/product` 文件 `u:object_r:system_file:s0`、
  `/odm/lib64/*.so` `u:object_r:same_process_hal_file:s0`。

## 安装 / 卸载

```sh
# KernelSU / Magisk
su -c "ksud module install /data/local/tmp/oppo_camera_port_reno17p.zip"

# 只软重启框架，别整机重启（这台整机会掉 root）
su -c "stop; start"

# 不想重启也行：手动重挂 + 重启相机
su -c "sh /data/local/tmp/apply_all.sh"

su -c "ksud module uninstall oppo_camera_port"
```

## 开关

| 开关 | 作用 |
|---|---|
| `touch /data/adb/modules/oppo_camera_port/enable_retro` | 挂 retro 配置（实况调色盘 / POP 的配置层） |
| `setprop persist.camunlock.pop 1` | hook 层：把 `retro_camera_mode` 注入模式列表 |
| `setprop persist.camunlock.palette.allmode 1` | hook 层：放开调色盘的「仅 common/portrait/night」白名单 |
| `setprop persist.camunlock.filter.director 1` | hook 层：放行导演联名滤镜（姜文 / 贾樟柯） |
| `setprop persist.camunlock.filter.festival 1` 等 | hook 层：鲜活 / 节日 / 庆典 |

## 验证

```sh
su -c "mount | grep -E 'camera|unit.sdk'"
su -c "ls /odm/etc/camera/meishe_lut | wc -l"                                  # 135
su -c "md5sum /odm/etc/camera/meishe_lut/hu_po.bin"                            # ffc1f32c4c01f1011fc02fc32e87156d
su -c "stat -c %s /product/framework/com.oplus.camera.unit.sdk.jar"            # 5363803
su -c "grep -c retro /odm/etc/camera/config/camera_unit_config"                # 16
su -c "dumpsys package com.oplus.camera | grep versionName"                    # 7.013.60
```

## 已知问题

- **人像模式卡死**：如果把 `camera_unit_config` 整份换成 Reno17p 的（27,365 B），进人像会冻结
  （预览不刷新、无 ANR）。必须用「设备原厂 + retro 补丁」那份（39,633 B）。
- **大师模式没有调色盘入口**：`CommonUtil.isSupportPalette(mode)` 的白名单写死为
  `common / portrait / night`，大师模式不在里面，且该模式不构造调色盘按钮，hook 那句判断也不够。
- **POP 模式**：`retro_camera_mode` 已进 `mode_type_list`（hook 日志可证），但模式条 5 个格子由
  APK 另一张表挑选，暂未出现。
