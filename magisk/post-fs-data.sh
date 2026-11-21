#!/system/bin/sh
# OPPO 相机资源移植 —— post-fs-data
#
# 目标分区的实情（这决定了只能 bind，不能用 Magisk 的 system/ 覆盖）：
#   /odm 是 erofs 只读整块挂载，相机资源在 /odm/etc/camera/
#   KernelSU 只对 /system 做 overlay，/odm 不会自动叠
# 所以 payload 里是「合并后的完整目录」，这里整目录 bind 上去。
#
# 相机进程域 u:r:opluscamera_app:s0，读 u:object_r:vendor_configs_file:s0，
# 所以 payload 里的文件必须改成这个标签，否则静默读不到（不报错，只是不生效）。

MODDIR=${0%/*}
PAYLOAD="$MODDIR/payload"
CAM=/odm/etc/camera
LOG="palette_port"

log() { echo "$LOG: $*"; }

chcon_tree() {
  chcon -R u:object_r:vendor_configs_file:s0 "$1" 2>/dev/null
}

# 1) 胶片 / 实况调色盘：meishe_lut
#    相机读这条路径的代码在 native（/odm/lib64/libAlgoInterface.so 里写死
#    /odm/etc/camera/meishe_lut/），Java 侧改不了，只能整目录 bind。
bind_dir() {
  name="$1"
  src="$PAYLOAD/$name"
  dst="$CAM/$name"
  [ -d "$src" ] || return 0
  [ -d "$dst" ] || { log "skip $name (target missing)"; return 0; }
  # 注意：不能只判「已挂载就跳过」。模块重装会删掉旧 payload 目录再重建，
  # 旧的 bind 会指向已删除的 inode（挂载点在但里面是空的），必须强制重挂。
  umount "$dst" 2>/dev/null
  chcon_tree "$src"
  if mount --bind "$src" "$dst"; then
    log "bind $name ok ($(ls "$dst" | wc -l) files)"
  else
    log "bind $name FAILED"
  fi
}

bind_dir meishe_lut

# 1.2) apk_resources：retro（复古/实况调色盘）的 UI 图 + Lottie 胶囊动画 + 引导视频
#      纯增量，设备原来只有 mp4/，这里补 img/ 和 json/
bind_dir apk_resources

# 1.3) 美摄滤镜引擎 libmsnativefilter.so
#      换过 Reno17p 的 unit.sdk（Java）之后，它会给 Meishe 设 "Custom LUT File Path"/"Factor"
#      这两个参数；本机原厂 .so 没有这些 paramId，日志会刷
#      "meicam: Invalid paramId: Custom LUT ..."，琥珀/清透就不会真正生效。
#      换成 Reno17p 的 .so（11.27MB，带这两个参数）后报错归零、相机不崩。
L64=/odm/lib64/libmsnativefilter.so
if [ -f "$PAYLOAD/lib64/libmsnativefilter.so" ] && [ -f "$L64" ]; then
  umount "$L64" 2>/dev/null
  chcon u:object_r:same_process_hal_file:s0 "$PAYLOAD/lib64/libmsnativefilter.so" 2>/dev/null
  mount --bind "$PAYLOAD/lib64/libmsnativefilter.so" "$L64" \
    && log "bind libmsnativefilter.so ok ($(stat -c %s $L64) B)" \
    || log "bind libmsnativefilter.so FAILED"
fi

# 1.5) 滤镜清单：com.oplus.camera.unit.sdk.jar
#      滤镜 id → 显示名的表就在这个 jar 的 classes.dex 里（R.string.camera_filter_*），
#      本机版本缺 hu_po(琥珀)/qing_tou(清透)/gt_* 等。
#
#      绑定点必须是 **/product/framework**（app 真正 open 的路径），不能绑
#      /my_product/product_overlay/framework —— 后者只是 overlay 的 lowerdir 之一，
#      开机时合并视图已经定型，绑在层上不会反映到 /product（实测踩过）。
#      ART 的 odex/vdex 要跟着换，否则校验和不匹配。
OVL=/product/framework
if [ -d "$PAYLOAD/framework" ]; then
  for f in com.oplus.camera.unit.sdk.jar \
           oat/arm64/com.oplus.camera.unit.sdk.odex \
           oat/arm64/com.oplus.camera.unit.sdk.vdex; do
    src="$PAYLOAD/framework/$f"
    dst="$OVL/$f"
    [ -f "$src" ] && [ -f "$dst" ] || continue
    if mount | grep -q " $dst "; then
      log "$(basename $f) already mounted"
      continue
    fi
    chcon u:object_r:system_file:s0 "$src" 2>/dev/null
    mount --bind "$src" "$dst" && log "bind $(basename $f) ok" || log "bind $f FAILED"
  done
fi

# 2) 配置层：本分支不再带 retro/POP 的配置补丁（camera_unit_config / protobuf），
#    保持设备原厂配置不动 —— 整份换 Reno 的会让人像模式卡死。

log "done"
