import hashlib
import pathlib
import subprocess
import sys
import zipfile

ZIP = r"C:\Users\black\.dsh\attachments\v1\files\59\597b250e9e23e57a5c039c370492fe5882144b8c8b7066d9417b3bab1bef983d\basictone.zip"
ODM = "/odm/etc/camera/basictone"

z = zipfile.ZipFile(ZIP)
zipmd5 = {}
for n in z.namelist():
    if n.endswith("/"):
        continue
    zipmd5[n[len("basictone/"):]] = hashlib.md5(z.read(n)).hexdigest()

out = subprocess.run(
    ["adb", "shell", "md5sum " + ODM + "/lmt/* " + ODM + "/odt/* " + ODM + "/setting/* " + ODM + "/setting_dolby/* " + ODM + "/vig/*"],
    capture_output=True, text=True,
).stdout
devmd5 = {}
for line in out.splitlines():
    parts = line.split()
    if len(parts) == 2:
        devmd5[parts[1][len(ODM) + 1:]] = parts[0]

same, diff, only_zip, only_dev = [], [], [], []
for k in sorted(set(zipmd5) | set(devmd5)):
    a, b = zipmd5.get(k), devmd5.get(k)
    if a and b:
        (same if a == b else diff).append(k)
    elif a:
        only_zip.append(k)
    else:
        only_dev.append(k)

print("SAME (%d):" % len(same))
for k in same:
    print("  ", k)
print("DIFF (%d):" % len(diff))
for k in diff:
    print("  ", k, zipmd5[k], devmd5[k])
print("ONLY IN ZIP (%d):" % len(only_zip))
for k in only_zip:
    print("  ", k)
print("ONLY ON DEVICE (%d):" % len(only_dev))
for k in only_dev:
    print("  ", k)
