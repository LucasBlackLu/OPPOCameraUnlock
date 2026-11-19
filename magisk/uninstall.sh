#!/system/bin/sh
# 卸载时把挂载撤掉（模块目录会被删，挂载不会自己消失）
CAM=/odm/etc/camera
OVL=/product/framework
umount "$CAM/meishe_lut" 2>/dev/null
umount "$CAM/config/camera_unit_config" 2>/dev/null
umount "$OVL/com.oplus.camera.unit.sdk.jar" 2>/dev/null
umount "$OVL/oat/arm64/com.oplus.camera.unit.sdk.odex" 2>/dev/null
umount "$OVL/oat/arm64/com.oplus.camera.unit.sdk.vdex" 2>/dev/null
# 旧版本绑在 overlay 层上的残留
umount "/my_product/product_overlay/framework/com.oplus.camera.unit.sdk.jar" 2>/dev/null
echo "palette_port: unmounted"
