#!/system/bin/sh
# 兜底：post-fs-data 没挂上（某些情况下 /odm 还没就绪）就在这里补一次，幂等。
MODDIR=${0%/*}
PAYLOAD="$MODDIR/payload"
CAM=/odm/etc/camera

# 与 post-fs-data 一致：minimal 模式下不替换任何系统文件，也不碰相机 APK。
if [ -f "$MODDIR/minimal" ]; then
  echo "palette_port(service): minimal，不动 /odm，也不装相机 APK"
  exit 0
fi

for name in meishe_lut; do
  src="$PAYLOAD/$name"
  dst="$CAM/$name"
  [ -d "$src" ] && [ -d "$dst" ] || continue
  mount | grep -q " $dst " && continue
  chcon -R u:object_r:vendor_configs_file:s0 "$src" 2>/dev/null
  mount --bind "$src" "$dst" && echo "palette_port(service): bind $name ok"
done

# 相机 APK：**完全可选**。默认的模块 zip 里不放这个 APK（避免替换本机相机），
# 只有你自己把 payload/apk/OplusCamera.apk + version.txt 放进模块目录时才会补装。
# 装成 data app 更新，/product/app 里的原厂包不动，随时 pm uninstall 回滚。
APK="$PAYLOAD/apk/OplusCamera.apk"
WANT=$(cat "$PAYLOAD/apk/version.txt" 2>/dev/null | tr -d ' \r\n')
if [ -f "$APK" ] && [ -n "$WANT" ]; then
  (
    # 等系统起来再装，post-fs-data 阶段 package manager 还没就绪
    for i in $(seq 1 60); do
      [ "$(getprop sys.boot_completed)" = "1" ] && break
      sleep 3
    done
    sleep 8
    HAVE=$(dumpsys package com.oplus.camera 2>/dev/null | grep -m1 versionName | cut -d= -f2 | tr -d ' \r')
    if [ "$HAVE" != "$WANT" ]; then
      echo "palette_port(service): 相机 $HAVE -> $WANT"
      pm install -r -d -g "$APK" >>/data/local/tmp/cam_apk_install.log 2>&1 \
        && echo "palette_port(service): apk install ok" \
        || echo "palette_port(service): apk install FAILED (见 /data/local/tmp/cam_apk_install.log)"
    fi
  ) &
fi
