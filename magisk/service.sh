#!/system/bin/sh
# 兜底：post-fs-data 没挂上（某些情况下 /odm 还没就绪）就在这里补一次，幂等。
MODDIR=${0%/*}
PAYLOAD="$MODDIR/payload"
CAM=/odm/etc/camera

for name in meishe_lut; do
  src="$PAYLOAD/$name"
  dst="$CAM/$name"
  [ -d "$src" ] && [ -d "$dst" ] || continue
  mount | grep -q " $dst " && continue
  chcon -R u:object_r:vendor_configs_file:s0 "$src" 2>/dev/null
  mount --bind "$src" "$dst" && echo "palette_port(service): bind $name ok"
done

if [ -f "$MODDIR/enable_retro" ] && [ -f "$PAYLOAD/config/camera_unit_config" ]; then
  dst="$CAM/config/camera_unit_config"
  if ! mount | grep -q " $dst "; then
    chcon u:object_r:vendor_configs_file:s0 "$PAYLOAD/config/camera_unit_config" 2>/dev/null
    mount --bind "$PAYLOAD/config/camera_unit_config" "$dst" && echo "palette_port(service): retro config armed"
  fi
fi

# 相机 APK：POP / 鱼眼 / 实况调色盘的 UI 代码只在 7.016.29 这一版里有。
# 只要模块里放了 payload/apk/OplusCamera.apk，且机上版本不是这个号，就开机补装一次。
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
