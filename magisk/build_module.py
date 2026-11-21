# -*- coding: utf-8 -*-
"""build_module.py — 打包 Magisk/KernelSU 模块 zip

模块 zip 结构（文件必须在 zip 根，不能套一层目录）：
    module.prop
    post-fs-data.sh / service.sh / uninstall.sh / customize.sh
    payload/...

payload/apk/ 默认**不打进 zip**：那是整包相机 APK，放进去就等于替换本机相机。
要那套「相机整包」的行为，自己把 OplusCamera.apk + version.txt 放进模块目录再用
--with-apk 打包。
"""
import os
import sys
import zipfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # CameraUnlock/repo
# 模块构建目录（payload 所在）默认在仓库隔壁：CameraUnlock/magisk_module
MOD = os.environ.get('CAMUNLOCK_MODULE') or os.path.join(BASE, 'magisk_module')
if not os.path.isdir(MOD):
    alt = os.path.join(os.path.dirname(BASE), 'magisk_module')
    if os.path.isdir(alt):
        MOD = alt
OUT = os.environ.get('CAMUNLOCK_OUT') or os.path.join(
    os.path.dirname(BASE), 'out', 'oppo_camera_port_reno17p.zip')
WITH_APK = '--with-apk' in sys.argv
if not os.path.isdir(MOD):
    sys.exit('找不到模块目录：%s（可用环境变量 CAMUNLOCK_MODULE 指定）' % MOD)

FILES = [
    'module.prop',
    'post-fs-data.sh',
    'service.sh',
    'uninstall.sh',
    'customize.sh',
    'README.md',
]

if os.path.exists(OUT):
    os.remove(OUT)
os.makedirs(os.path.dirname(OUT), exist_ok=True)

n = 0
total = 0
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for f in FILES:
        p = os.path.join(MOD, f)
        if not os.path.exists(p):
            print('  ! 缺 %s' % f)
            continue
        z.write(p, f)
        n += 1
    for dp, dn, fn in os.walk(os.path.join(MOD, 'payload')):
        for f in sorted(fn):
            full = os.path.join(dp, f)
            rel = os.path.relpath(full, MOD).replace('\\', '/')
            if not WITH_APK and rel.startswith('payload/apk/'):
                continue
            z.write(full, rel)
            n += 1
            total += os.path.getsize(full)

if not WITH_APK:
    print('提示：payload/apk/ 未打进 zip（想替换相机整包就用 --with-apk）')

print('打包 %d 个条目 -> %s (%.1f MB)' % (n, OUT, os.path.getsize(OUT) / 1e6))
print('payload 原始 %.1f MB' % (total / 1e6))
