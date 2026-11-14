"""从 basictone.zip（X10 Pro Max 提取）生成模块 assets。

产物：
  app/src/main/assets/basictone/setting/*          -> SimTool*.ini / FromXml / TriggerBIN / Ditherout
  app/src/main/assets/basictone/palette/HDR/*      -> SC16Lut0..8
  app/src/main/assets/basictone/palette/SDR/*      -> SC16Lut0..8
"""

import pathlib
import shutil
import zipfile

ZIP = pathlib.Path(__file__).resolve().parents[1] / "basictone.zip"
if not ZIP.is_file():
    ZIP = pathlib.Path(
        r"C:\Users\black\.dsh\attachments\v1\files\59"
        r"\597b250e9e23e57a5c039c370492fe5882144b8c8b7066d9417b3bab1bef983d\basictone.zip"
    )
ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"

KEEP_PREFIX = (
    "basictone/setting/",
    "basictone/palette/HDR/",
    "basictone/palette/SDR/",
)


def main() -> None:
    for old in (ASSETS / "palette", ASSETS / "palette_setting", ASSETS / "basictone"):
        if old.exists():
            shutil.rmtree(old)

    n = 0
    with zipfile.ZipFile(ZIP) as z:
        for name in z.namelist():
            if name.endswith("/") or not name.startswith(KEEP_PREFIX):
                continue
            dst = ASSETS / name
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(z.read(name))
            n += 1
            print(f"{z.getinfo(name).file_size:>9} {name}")

    print(f"\n{n} files -> {ASSETS / 'basictone'}")


if __name__ == "__main__":
    main()
