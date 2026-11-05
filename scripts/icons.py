#!/usr/bin/env python3
"""把 design/icon 里的 SVG 导出成打包和窗口用的图标。改了 SVG 之后重新跑一次。

    python3 scripts/icons.py           导出浅色版（默认）
    python3 scripts/icons.py dark      导出深色版

需要 Google Chrome（无头模式渲染 SVG）和 macOS 自带的 iconutil。
64px 及以下用 *-small.svg：线更粗，省掉中间的提交节点。
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "design" / "icon"
OUT = ROOT / "packaging" / "icons"
RESOURCES = ROOT / "src" / "main" / "resources"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
SIZES = [16, 32, 64, 128, 256, 512, 1024]


def render(svg: Path, size: int, out: Path, tmp: Path) -> None:
    page = tmp / "page.html"
    page.write_text(
        f'<html><body style="margin:0;background:transparent">'
        f'<img src="{svg.as_uri()}" width="{size}" height="{size}" style="display:block"></body></html>'
    )
    subprocess.run(
        [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
         "--default-background-color=00000000", "--force-device-scale-factor=1",
         f"--window-size={size},{size}", f"--screenshot={out}", page.as_uri()],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    if not out.exists():
        sys.exit(f"渲染失败：{svg.name} @ {size}px")


def write_ico(pngs: dict, out: Path) -> None:
    """ICO 文件直接装 PNG 数据，Windows Vista 以后都认。"""
    sizes = [s for s in (16, 32, 64, 128, 256) if s in pngs]
    data = [pngs[s].read_bytes() for s in sizes]
    header = struct.pack("<HHH", 0, 1, len(sizes))
    offset = 6 + 16 * len(sizes)
    entries = b""
    for s, d in zip(sizes, data):
        dim = 0 if s == 256 else s  # 0 表示 256
        entries += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(d), offset)
        offset += len(d)
    out.write_bytes(header + entries + b"".join(data))


def main() -> None:
    variant = sys.argv[1] if len(sys.argv) > 1 else "light"
    if variant not in ("light", "dark"):
        sys.exit("参数只能是 light 或 dark")
    if not os.path.exists(CHROME):
        sys.exit(f"找不到 Chrome：{CHROME}")
    OUT.mkdir(parents=True, exist_ok=True)
    RESOURCES.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        pngs = {}
        for size in SIZES:
            name = f"gitviz-{variant}-small.svg" if size <= 64 else f"gitviz-{variant}.svg"
            pngs[size] = tmp / f"{size}.png"
            render(SRC / name, size, pngs[size], tmp)

        iconset = tmp / "GitViz.iconset"
        iconset.mkdir()
        for size in (16, 32, 128, 256, 512):
            shutil.copy(pngs[size], iconset / f"icon_{size}x{size}.png")
            shutil.copy(pngs[size * 2], iconset / f"icon_{size}x{size}@2x.png")
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(OUT / "GitViz.icns")], check=True)

        write_ico(pngs, OUT / "GitViz.ico")
        shutil.copy(pngs[512], OUT / "GitViz.png")
        shutil.copy(pngs[256], RESOURCES / "icon.png")

    print(f"已导出 {variant} 版：{OUT.relative_to(ROOT)}/GitViz.{{icns,ico,png}}，{RESOURCES.relative_to(ROOT)}/icon.png")


if __name__ == "__main__":
    main()
