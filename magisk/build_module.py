# -*- coding: utf-8 -*-
"""build_module.py — 打包 Magisk/KernelSU 模块 zip

模块 zip 结构（文件必须在 zip 根，不能套一层目录）：
    module.prop
    post-fs-data.sh / service.sh / uninstall.sh / customize.sh
    payload/...
"""
import os
import sys
import zipfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.path.join(BASE, 'magisk_module')
OUT = os.path.join(BASE, 'out', 'oppo_camera_port_reno17p.zip')

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
            z.write(full, rel)
            n += 1
            total += os.path.getsize(full)

print('打包 %d 个条目 -> %s (%.1f MB)' % (n, OUT, os.path.getsize(OUT) / 1e6))
print('payload 原始 %.1f MB' % (total / 1e6))
