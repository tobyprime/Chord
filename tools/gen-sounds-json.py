#!/usr/bin/env python3
"""从 instruments.yml 重新生成资源包 sounds.json。

单一事实来源是 plugin/src/main/resources/instruments.yml 的 voices 表：
  - sound 含 %d 模板 → 为区间内每个半音注册独立事件（全部指向该层同一 ogg，
    服务端逐音 stopSound 的基础），事件名 = 模板替换 %d；
  - 普通事件（鼓件、fallback 用的原版事件除外）只注册一条。
本脚本只处理 chord: 命名空间的自定义事件；voices 里引用的原版事件
（block.note_block.*）不进 sounds.json。

基础事件 → ogg 文件的映射在下方 BASE_OGG 表维护（换采样文件名后同步改这里）。
用法：python tools/gen-sounds-json.py   （在仓库根目录执行）
"""
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
YML = ROOT / "plugin/src/main/resources/instruments.yml"
OUT = ROOT / "assets/resourcepack/assets/chord/sounds.json"
SOUNDS_DIR = ROOT / "assets/resourcepack/assets/chord/sounds"

# 基础事件（不含逐半音后缀）→ ogg 路径（相对 assets/chord/sounds/，无扩展名）
BASE_OGG = {
    # 口琴 12 锚点网格（0.7.14，VCSL Super 64 全部 C/E/G 录音）
    "harmonica.c3": "harmonica/harmonica_c3",
    "harmonica.e3": "harmonica/harmonica_e3",
    "harmonica.g3": "harmonica/harmonica_g3",
    "harmonica.c4": "harmonica/harmonica_c4",
    "harmonica.e4": "harmonica/harmonica_e4",
    "harmonica.g4": "harmonica/harmonica_g4",
    "harmonica.c5": "harmonica/harmonica_c5",
    "harmonica.e5": "harmonica/harmonica_e5",
    "harmonica.g5": "harmonica/harmonica_g5",
    "harmonica.c6": "harmonica/harmonica_c6",
    "harmonica.e6": "harmonica/harmonica_e6",
    "harmonica.g6": "harmonica/harmonica_g6",
    # 钢琴 14 锚点纯四度网格（0.7.16，VCSL JHPiano 每全音采样；旧 5 锚点版
    # high 区拉满 ±12 半音致高音失真，现每采样最大 ±3~4 半音）
    "piano.as0": "piano/piano_as0",
    "piano.fs1": "piano/piano_fs1",
    "piano.c2": "piano/piano_c2",
    "piano.fs2": "piano/piano_fs2",
    "piano.c3": "piano/piano_c3",
    "piano.fs3": "piano/piano_fs3",
    "piano.c4": "piano/piano_c4",
    "piano.fs4": "piano/piano_fs4",
    "piano.c5": "piano/piano_c5",
    "piano.fs5": "piano/piano_fs5",
    "piano.c6": "piano/piano_c6",
    "piano.fs6": "piano/piano_fs6",
    "piano.c7": "piano/piano_c7",
    "piano.fs7": "piano/piano_fs7",
    # 钢琴力度层（0.6.0）：vl2 柔音 / vl4 强音，同锚点网格
    "piano.soft.as0": "piano/piano_soft_as0",
    "piano.soft.fs1": "piano/piano_soft_fs1",
    "piano.soft.c2": "piano/piano_soft_c2",
    "piano.soft.fs2": "piano/piano_soft_fs2",
    "piano.soft.c3": "piano/piano_soft_c3",
    "piano.soft.fs3": "piano/piano_soft_fs3",
    "piano.soft.c4": "piano/piano_soft_c4",
    "piano.soft.fs4": "piano/piano_soft_fs4",
    "piano.soft.c5": "piano/piano_soft_c5",
    "piano.soft.fs5": "piano/piano_soft_fs5",
    "piano.soft.c6": "piano/piano_soft_c6",
    "piano.soft.fs6": "piano/piano_soft_fs6",
    "piano.soft.c7": "piano/piano_soft_c7",
    "piano.soft.fs7": "piano/piano_soft_fs7",
    "piano.loud.as0": "piano/piano_loud_as0",
    "piano.loud.fs1": "piano/piano_loud_fs1",
    "piano.loud.c2": "piano/piano_loud_c2",
    "piano.loud.fs2": "piano/piano_loud_fs2",
    "piano.loud.c3": "piano/piano_loud_c3",
    "piano.loud.fs3": "piano/piano_loud_fs3",
    "piano.loud.c4": "piano/piano_loud_c4",
    "piano.loud.fs4": "piano/piano_loud_fs4",
    "piano.loud.c5": "piano/piano_loud_c5",
    "piano.loud.fs5": "piano/piano_loud_fs5",
    "piano.loud.c6": "piano/piano_loud_c6",
    "piano.loud.fs6": "piano/piano_loud_fs6",
    "piano.loud.c7": "piano/piano_loud_c7",
    "piano.loud.fs7": "piano/piano_loud_fs7",
    # 钢琴制音尾音（0.7.16f）：等 dB 分档 rel_{key}_t{n} 由下方 glob 动态注册（档数随锚点衰减快慢 1~9 个）
    # 吉他/贝斯：锚点采样文件名按实际交付的音名命名，换采样后同步改
    "guitar.low": "guitar/guitar_low_c3",
    "guitar.mid": "guitar/guitar_mid_e4",
    "guitar.high": "guitar/guitar_high_g5",
    "bass.low": "bass/bass_low_c2",
    "bass.mid": "bass/bass_mid_e3",
    "bass.high": "bass/bass_high_e4",
    # 小提琴 15 锚点网格（0.8.0，VSCO 2 CE SViolinVib；p/f 双力度层），
    # 事件名按音名（violin.g3 … violin.c7 / violin.soft.*），重跑 tools/violin-rebuild.py 同步
    "violin.g3": "violin/violin_g3",
    "violin.a3": "violin/violin_a3",
    "violin.c4": "violin/violin_c4",
    "violin.e4": "violin/violin_e4",
    "violin.g4": "violin/violin_g4",
    "violin.a4": "violin/violin_a4",
    "violin.c5": "violin/violin_c5",
    "violin.e5": "violin/violin_e5",
    "violin.g5": "violin/violin_g5",
    "violin.a5": "violin/violin_a5",
    "violin.c6": "violin/violin_c6",
    "violin.e6": "violin/violin_e6",
    "violin.g6": "violin/violin_g6",
    "violin.a6": "violin/violin_a6",
    "violin.c7": "violin/violin_c7",
    "violin.soft.g3": "violin/violin_soft_g3",
    "violin.soft.a3": "violin/violin_soft_a3",
    "violin.soft.c4": "violin/violin_soft_c4",
    "violin.soft.e4": "violin/violin_soft_e4",
    "violin.soft.g4": "violin/violin_soft_g4",
    "violin.soft.a4": "violin/violin_soft_a4",
    "violin.soft.c5": "violin/violin_soft_c5",
    "violin.soft.e5": "violin/violin_soft_e5",
    "violin.soft.g5": "violin/violin_soft_g5",
    "violin.soft.a5": "violin/violin_soft_a5",
    "violin.soft.c6": "violin/violin_soft_c6",
    "violin.soft.e6": "violin/violin_soft_e6",
    "violin.soft.g6": "violin/violin_soft_g6",
    "violin.soft.a6": "violin/violin_soft_a6",
    "violin.soft.c7": "violin/violin_soft_c7",
    # 唢呐 11 锚点 C/E/G 网格（0.9.0，tools/suona-synth.py 原创合成；C4~G6），
    # 重跑 tools/suona-synth.py 同步
    "suona.c4": "suona/suona_c4",
    "suona.e4": "suona/suona_e4",
    "suona.g4": "suona/suona_g4",
    "suona.a4": "suona/suona_a4",
    "suona.c5": "suona/suona_c5",
    "suona.e5": "suona/suona_e5",
    "suona.g5": "suona/suona_g5",
    "suona.a5": "suona/suona_a5",
    "suona.c6": "suona/suona_c6",
    "suona.e6": "suona/suona_e6",
    "suona.g6": "suona/suona_g6",
    # 鼓件：每件一条一次性采样事件，无 %d 模板、无逐半音注册
    "drums.kick": "drums/drums_kick",
    "drums.kick2": "drums/drums_kick2",
    "drums.snare": "drums/drums_snare",
    "drums.rim": "drums/drums_rim",
    "drums.hat.closed": "drums/drums_hat_closed",
    "drums.hat.open": "drums/drums_hat_open",
    "drums.hat.pedal": "drums/drums_hat_pedal",
    "drums.tom.high": "drums/drums_tom_high",
    "drums.tom.mid": "drums/drums_tom_mid",
    "drums.tom.floor": "drums/drums_tom_floor",
    "drums.crash": "drums/drums_crash",
    "drums.ride": "drums/drums_ride",
}


def entry(ogg: str) -> dict:
    return {"sounds": [{"name": f"chord:{ogg}", "volume": 1.0, "pitch": 1.0, "stream": False}]}


def expand_voices(voices, events):
    for voice in voices or []:
        sound = voice["sound"]
        if not sound.startswith("chord:"):
            continue  # fallback-voices 等原版事件不注册
        base = sound[6:]
        if "%d" in base:
            template = base
            plain = base.replace(".%d", "")
            for semitone in range(voice["min"], voice["max"] + 1):
                events[template.replace("%d", str(semitone))] = entry(BASE_OGG[plain])
        else:
            if base not in BASE_OGG:
                print(f"警告: yml 引用但 BASE_OGG 未映射: {base}")
                continue
            events[base] = entry(BASE_OGG[base])


def main() -> int:
    data = yaml.safe_load(YML.read_text(encoding="utf-8"))
    events: dict[str, dict] = {}
    missing = []

    for key, cfg in data.items():
        expand_voices(cfg.get("voices"), events)
        # release-voices 行是 {min, max, base-pitch, sounds: [a, b, c]}——sounds 列表逐项注册
        # release-voices 的 rel 事件不在此注册——等 dB 分档档数可变，
        # 由下方 glob 从 sounds 目录动态注册（见 0.7.16f）
        for layer in cfg.get("layers") or []:
            expand_voices(layer.get("voices"), events)

    # 钢琴制音尾音：等 dB 分档产物动态注册（piano_rel_{key}_t{n}.ogg）
    for rel_ogg in sorted(SOUNDS_DIR.glob("piano/piano_rel_*_t*.ogg")):
        stem = rel_ogg.stem  # piano_rel_c4_t3
        _, _, key, grade = stem.split("_")
        events[f"piano.rel.{key}.{grade}"] = entry(f"piano/{stem}")

    # 基础事件（无逐半音后缀）也要注册——脚本自动补齐 BASE_OGG 里 yml 未直接引用的
    for base, ogg in BASE_OGG.items():
        events.setdefault(base, entry(ogg))

    # 排序：先基础事件（按表序），再逐半音事件（按层、按半音升序）
    base_order = {name: i for i, name in enumerate(BASE_OGG)}
    def sort_key(name: str):
        plain = base_base(name)
        parts = name.split(".")
        if parts[-1].lstrip("-").isdigit():
            return (1, base_order.get(plain, 99), int(parts[-1]), name)
        return (0, base_order.get(name, 99), 0, name)

    ordered = dict(sorted(events.items(), key=lambda kv: sort_key(kv[0])))

    # 校验 ogg 文件存在
    absent = sorted({
        ogg for ogg in BASE_OGG.values()
        if not (SOUNDS_DIR / (ogg + ".ogg")).exists()
    })
    OUT.write_text(json.dumps(ordered, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"sounds.json: {len(ordered)} events → {OUT.relative_to(ROOT)}")
    if missing:
        print("yml 引用但 BASE_OGG 未映射:", missing)
    if absent:
        print("缺失 ogg（占位生成，落地前资源包不完整）:")
        for ogg in absent:
            print("  -", ogg)
        return 1
    return 0


def base_base(name: str) -> str:
    """harmonica.mid.-8 → harmonica.mid（去掉末尾的半音段）。"""
    parts = name.split(".")
    if parts[-1].lstrip("-").isdigit():
        return ".".join(parts[:-1])
    return name


if __name__ == "__main__":
    sys.exit(main())
