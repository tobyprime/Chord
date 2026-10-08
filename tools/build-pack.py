#!/usr/bin/env python3
"""重打包 assets/resourcepack → chord.zip（资源包分发件）。

只打包 pack.mcmeta + assets/**；排除 downloads/（原始 wav 留档）、README.md、
chord.zip 自身。旧版打包曾把 downloads 与自身嵌套进 zip（18MB 冗余），勿回归。

流程：先跑 tools/gen-sounds-json.py 再跑本脚本。
"""
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent / "assets/resourcepack"
target = root / "chord.zip"
exclude = {"chord.zip", "README.md"}

with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(root.rglob("*")):
        rel = p.relative_to(root).as_posix()
        if rel in exclude or rel.startswith("downloads/") or p.is_dir():
            continue
        z.write(p, rel)

names = zipfile.ZipFile(target).namelist()
assert "pack.mcmeta" in names, "pack.mcmeta 缺失——资源包非法"
assert not any(n.startswith("downloads/") or n == "chord.zip" for n in names), "冗余条目混入"
size_kb = target.stat().st_size // 1024
print(f"chord.zip: {len(names)} files, {size_kb} KB")
if size_kb > 5000:
    # 熔断：正常应 <1MB；超限说明把原始留档/无关文件打进了包（曾发生 50MB 包事故）
    print(f"错误: 资源包 {size_kb} KB 超过 5MB 熔断线——检查是否混入 downloads/ 等非部署文件",
          file=sys.stderr)
    sys.exit(1)
if not any(n.endswith(".ogg") for n in names):
    print("警告: zip 内没有任何 ogg 采样", file=sys.stderr)
    return_code = 1
else:
    return_code = 0
sys.exit(return_code)
