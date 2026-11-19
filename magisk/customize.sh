#!/system/bin/sh
# Magisk / KernelSU 安装期脚本：设置权限、按需标 SELinux
MODDIR=${0%/*}
[ -f "$MODDIR/module.prop" ] || MODDIR=$(dirname "$0")

# 脚本可执行
set_perm_recursive() {
  find "$1" -type d -exec chmod 755 {} \; 2>/dev/null
  find "$1" -type f -exec chmod 644 {} \; 2>/dev/null
}

chmod 755 "$MODDIR"/*.sh 2>/dev/null
if [ -d "$MODDIR/payload" ]; then
  set_perm_recursive "$MODDIR/payload"
fi

# 相机要读的标签（root 才有权限改；没有也不影响安装）
if [ "$(id -u)" = "0" ] && [ -d "$MODDIR/payload/meishe_lut" ]; then
  chcon -R u:object_r:vendor_configs_file:s0 "$MODDIR/payload" 2>/dev/null
fi

echo "oppo_camera_port: installed"
