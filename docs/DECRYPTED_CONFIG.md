# `/odm/etc/camera/config/oplus_camera_config` 解密与注入

## 它是什么

`/odm/etc/camera/config/oplus_camera_config`（本机 75,944 B）是**加密的二进制**（文件头 `0x01 0x01`）。
解开后是一个 JSON 数组，633 条：

```json
[
  { "VendorTag": "com.oplus.feature.video.dv.60fps.support", "Type": "Byte", "Count": "1", "Value": "1" },
  { "VendorTag": "com.oplus.camera.capture.hdr.cap.mode.value", "Type": "String", "Count": "1",
    "Value": "common,portrait,professional,night,highPixel,xpan,underWater" },
  ...
]
```

即：**全机的特性开关表**（VendorTag + 类型 + 值），`oplus_camera_config` 和 `camera_unit_config` 走同一条读取链。

## 解密链在哪

unit.sdk（`com.oplus.camera.unit.sdk.jar`）：

```
ApsUtils.getConfigData(Context)          → CONFIG_JSON_PATH + "oplus_camera_config"
UpdateHelper.getValidConfigData          → 读文件字节（还有 RUS 更新路径要合并）
UpdateHelper.getConfigString(byte[], OplusStringJNI)   ← 解密在这一步（JNI，native）
```

`getConfigString` 第二个参数是 `com.oplus.ocs.camera.consumer.apsAdapter.config.OplusStringJNI`。
解密性能在 native，但**不需要啃 .so**：在相机进程里 hook 这一句就能拿到明文。

## 模块里的实现

`CameraPaletteModule.hookConfigDecrypt`：

```kotlin
UpdateHelper.getConfigString(byte[], OplusStringJNI) → 原样返回，同时把结果写到
/data/user/0/com.oplus.camera/files/decoded/（root 可读）
```

实测：相机启动后就能拿到明文（75,934 B，633 条 VendorTag）。

## 能做什么

1. **读**：知道这台机器每一项特性开关的真实值（比猜配置靠谱）。
2. **改**：hook 里把 JSON 解析出来改字段，再返回改过的字符串 —— **不需要重新加密、不需要写 /odm**。
   例：把 `retroCamera` 加进下面两条，POP 成片就会走 Ultra HDR：

   ```
   com.oplus.camera.capture.hdr.cap.mode.value = common,portrait,professional,night,highPixel,xpan,underWater
   com.oplus.camera.preview.hdr.cap.mode.value  = common,professional,night,highPixel,xpan,underWater
   ```

3. **验证**：`X9 / PLJ110` 这台机器上 `retro` 相关 tag **一个都没有**（retro=0），
   说明 POP 不是靠 vendor tag 开的，而是靠 APK 的模式表 —— 这也解释了为什么改配置看不到 POP 入口。
