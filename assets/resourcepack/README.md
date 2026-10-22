# Chord 资源包 — 乐器采样资产

口琴、钢琴、吉他、贝斯、小提琴、唢呐（音高乐器：锚点采样 + 逐半音事件）与架子鼓（12 鼓件一次性采样）。
口琴 0.7.14 起 12 锚点网格，小提琴 0.8.0 起 15 锚点 × 双力度层，唢呐 0.9.0 起 11 锚点（原创合成），
其余音高乐器三锚点、变速覆盖全音域；
唢呐为本项目原创合成（无第三方素材，见文末专章），其余来源与许可证见各章节，全部 **CC0**。
本章节为口琴（Harmonica）：VCSL **Hohner Super 64**（16 孔半音阶口琴），12 锚点同源，音色一致。
打包：`python tools/gen-sounds-json.py && python tools/build-pack.py`（在仓库根执行）。

## 逐半音事件（note-off 用）

`sounds.json` 除 12 个基础事件外，还为每个半音注册了独立事件（指向该半音所在层的同一 ogg）：
`chord:harmonica.c3.-24 … -10`、`chord:harmonica.e3.-9 … -7` … `chord:harmonica.g6.30 … 31`，共 68 个。
这是「按住持续发声、松开即停」的基础——服务端对每个活跃音符单独 stopSound 其专属事件，
停一个音不影响和弦中的其他音。半音区间必须与插件 `instruments.yml` 中 voices 的 min/max 一致；
新增乐器/改区间时同步重新生成此表。

## 采样一览表（0.7.14，12 锚点；数值自成品 ogg 实测）

| 资源包文件（assets/chord/sounds/harmonica/） | Sound Event | 实际音名（实测） | 实测基频 | RMS | VCSL 原始文件名 |
|---|---|---|---|---|---|
| `harmonica_c3.ogg` | `chord:harmonica.c3` | **C3**（-3¢） | 130.6 Hz | -11.3 dB | `Hohner-Super64_Normal _C2.wav` |
| `harmonica_e3.ogg` | `chord:harmonica.e3` | **E3**（-5¢） | 164.3 Hz | -11.4 dB | `Hohner-Super64_Normal _E2.wav` |
| `harmonica_g3.ogg` | `chord:harmonica.g3` | **G3**（-3¢） | 195.7 Hz | -12.7 dB | `Hohner-Super64_Normal _G2.wav` |
| `harmonica_c4.ogg` | `chord:harmonica.c4` | **C4**（+1¢） | 261.8 Hz | -12.9 dB | `Hohner-Super64_Normal _C3.wav` |
| `harmonica_e4.ogg` | `chord:harmonica.e4` | **E4**（-1¢） | 329.5 Hz | -11.4 dB | `Hohner-Super64_Normal _E3.wav` |
| `harmonica_g4.ogg` | `chord:harmonica.g4` | **G4**（-0¢） | 391.9 Hz | -11.5 dB | `Hohner-Super64_Normal _G3.wav` |
| `harmonica_c5.ogg` | `chord:harmonica.c5` | **C5**（-0¢） | 523.1 Hz | -11.4 dB | `Hohner-Super64_Normal _C4.wav` |
| `harmonica_e5.ogg` | `chord:harmonica.e5` | **E5**（+0¢） | 659.4 Hz | -12.4 dB | `Hohner-Super64_Normal _E4.wav` |
| `harmonica_g5.ogg` | `chord:harmonica.g5` | **G5**（+1¢） | 784.2 Hz | -11.4 dB | `Hohner-Super64_Normal _G4.wav` |
| `harmonica_c6.ogg` | `chord:harmonica.c6` | **C6**（-1¢） | 1046.2 Hz | -11.5 dB | `Hohner-Super64_Normal _C5.wav` |
| `harmonica_e6.ogg` | `chord:harmonica.e6` | **E6**（+1¢） | 1319.1 Hz | -11.4 dB | `Hohner-Super64_Normal _E5.wav` |
| `harmonica_g6.ogg` | `chord:harmonica.g6` | **G6**（+0¢） | 1568.2 Hz | -11.4 dB | `Hohner-Super64_Normal _G5.wav` |

时长统一 3.0s（源 3.7~5.2s 裁起振后限长）。

**重要：VCSL 对 Super 64 的文件名标注比科学音名（scientific pitch notation）低一个八度**。
上表"实际音名"为对源 WAV 与成品 ogg 双解码路径（miniaudio 与 ffmpeg）频谱实测的结果：
标注 C2/E3/G4 → 实测 C3/E4/G5（谱峰为干净的基频+泛音列，非八度误判）。
服务端 `base-pitch` 计算**必须以实测音名为准**，不要照抄 VCSL 文件名。

## 来源与许可证

- **来源仓库**：https://github.com/sgossner/VCSL （采样目录：`Aerophones/Free Aerophones/Harmonica-Hohner-Super64/Sustains/Normal/`）
- **锁定 commit**：`c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e`（master，2026-10 抓取）
- **许可证**：**CC0 1.0 Universal（公共领域）** — 仓库 LICENSE 及 README 原文：
  "This collection is under a Creative Commons 0 license. Essentially it's Public Domain — you can do
  whatever you want with these sounds (even make commercial software), no royalties, no credit, no special terms."
- **署名**：CC0 无署名义务；出于惯例仍建议在服务器致谢页提及 "Samples: VCSL / Versilian Studios LLC (Sam Gossner), CC0"。
- **原始文件**：`downloads/harm-src/` 本地缓存（12 wav；打包已结构性排除 downloads/ 并有 5MB 熔断）。重取：`tools/harmonica-rebuild.py` 自动经 `gh api` 拉取（锁定 commit c1ea7bcc，文件名即表中原始文件名）。

## 处理流程（ffmpeg，可复现；0.7.14 脚本 `tools/harmonica-rebuild.py` 一键：下载→加工→实测→产出 yml 片段）

每个文件执行：
1. `highpass=f=40` 去次声；
2. 首尾静音去除（`silenceremove` 阈值 -45dB，头保留 0.02s，尾保留 0.05s）；
3. **起振裁切（0.7.8b）**：切到首个 5ms 窗 RMS ≥ 峰值−12dB 的「音已成形」处——
   吹气音（C 锚点）建立快（0~85ms），吸气音（E/G 锚点）慢（224~619ms），逐音自动适配；
4. 4ms 淡入 + `atrim=0:3.0` 限长 + 0.15s 淡出；
5. 峰值归一化到 -3 dBFS，随后 **RMS 对齐（0.7.14）**：整文件 RMS 对到 **−11.3dB**
   （旧三层的平均水平，混音平衡不变；纯峰值归一让 12 个相邻音 RMS 散布 −10.6~−15.8dB，
   音阶一响一轻），峰值超 −2.5dBFS 走 `alimiter`（**必须 `level=false`**——alimiter
   默认自动电平会把输出推到 0dBFS，实测踩坑）；
6. 编码：`libvorbis -q:a 5`，**mono，44100 Hz**（mono 是 Minecraft 3D 定位音效的前提）；
7. 逐音实测基频（FFT 峰 + 抛物线插值，限频段 ±25% 防泛音带偏）校验 ±50¢ 内、
   base-pitch = 261.626/实测基频。

## 音区覆盖数学（0.7.14 供服务端 instruments.yml 参考）

客户端 pitch 钳制 [0.5, 2.0]（±1 个八度）。12 锚点 C3~G6 每 3~4 半音一个，
逐半音**就近路由**到锚点：常用区间拉伸 ≤±2 半音（变速重采样透明）；仅底部
C2~B2（−24~−13）借 C3 采样下探，最多 −12（真实 Super 64 自 C3 起，旧版行为一致）。
voices 表（区间连续无重叠）见 instruments.yml `harmonica.voices` 注释。

## 已知缺陷与未尽事项

1. **pack_format = 999 为占位符**，发布前必须改为目标 MC 版本实际值（见 pack.mcmeta 内注释）。
2. **结尾为淡出而非自然衰减**：VCSL 的 Releases/ 目录有同音符的 release 尾音（`*_rel_*.wav`），V1 未拼接；如需更真实的收音，可在后续版本把 release 拼到 sustain 后。
3. **源标注与实测差一个八度**（见上），已实测校正；若后续从 VCSL 补充采样（如同乐器 Vib/Soft 力度层），务必同样实测。
4. 只用了 Normal（中性力度）单力度层；长音力度表现（velocity layer）留给 V2。
5. 音准微差：C3 实测 -4 cents、E4 +0、G5 +1，均在可接受范围；如需极限校准，服务端可按实测基频（130.5 / 329.7 / 784.6 Hz）而非十二平均律标称值换算 base-pitch。
6. 调研中被排除的来源（供后人避坑）：PowerChord（LGPL-3，许可证不符）、MoreColorful（MIT，且仅一个未知音高 ogg）、freesound 候选（Soparnik/ssud_motion/j_e_f_f_g 等 CC0 采样实测多为 F4–C6 或双音/和弦，无 G2–C3 干净单音；下载另需账号，仅 CDN 预览可用）、Philharmonia Sound Exchange（站点改版 404）、University of Iowa MIS（无口琴）、Musical Artifacts（站点无响应）。

## 目录结构

```
assets/resourcepack/
  pack.mcmeta
  README.md
  assets/chord/sounds.json
  assets/chord/sounds/harmonica/harmonica_{c3,e3,g3,c4,e4,g4,c5,e5,g5,c6,e6,g6}.ogg
  downloads/                       # 仅实测报告（原始采样不留档：来源/commit 见各章，gh api 重取）
    report-0.4.0-guitar-bass-drums.md
```

---

# 钢琴（Piano）采样资产

三个音区各一个单音采样。所有采样来自同一 VCSL 仓库同一 commit 的同一 patch：
**Grand Piano, Steinway B**（`Chordophones/Zithers/Grand Piano, Steinway B/`），
目录 `Sus/`（**延音踏板踩下的自然衰减音**，非敲击瞬音），麦克风位 `Close`，
力度层 **vl3**（该 patch 提供 vl2/vl3/vl4 三层，vl3 为中间层 ≈ Normal/MF），round robin rr1。
三层同 patch、同力度层、同批次，音色统一。

## 采样一览表

| 资源包文件（assets/chord/sounds/piano/） | Sound Event | 实际音名（实测） | 实测基频 | 时长 | 峰值 | VCSL 原始文件名 |
|---|---|---|---|---|---|---|
| `piano_low_c3.ogg`   | `chord:piano.low`  | **C3**（+2 cents）  | 131.0 Hz | 6.00 s* | -3.1 dBFS | `JHPiano_Sus_Close_C3_vl3_rr1.wav`  |
| `piano_mid_e4.ogg`   | `chord:piano.mid`  | **E4**（-1 cents）  | 329.5 Hz | 6.00 s* | -2.8 dBFS | `JHPiano_Sus_Close_E4_vl3_rr1.wav`  |
| `piano_high_gs5.ogg` | `chord:piano.high` | **G#5**（+1 cents） | 830.9 Hz | 3.72 s* | -3.1 dBFS | `JHPiano_Sus_Close_G#5_vl3_rr1.wav` |

*0.7.7 起为静音门控自然衰减（相对 −45 dB，上限 6 s 触顶）：低/中音触顶 6 s（0.4 s 淡出），
高音自然衰尽（G#5 3.7~5.5 s、C7 1.4~4.0 s——高音弦物理上衰减快）；此前 2.5 s 定长 + 0.3 s
淡出会在明显可闻段掐断长音/踏板音（用户反馈「音结束听感不佳、停顿太快」）。

说明：

- **高音区为 G#5 而非 G5**：Steinway B 按全音阶网格采样（C/D/E/F#/G#/A# × 各八度），**没有 G 弦采样**。
  距 G5（784 Hz）最近的两个采样是 F#5（740 Hz，实测 -4 cents）与 G#5（831 Hz，实测 +1 cents），
  取音准更好、录音电平更佳的 **G#5**（±2 半音）。服务端渲染 G5 时 base-pitch = `2^(-2/12)` ≈ 0.891
  （或在 voices 路由中把 high 层采样音高按实测 G#5 换算）。
- **Steinway B 的文件名标注与科学音名一致**（实测 C3→131 Hz、E4→329.5 Hz、G#5→830.9 Hz，均与标注相符），
  **没有**口琴目录那种"低一个八度"的问题——VCSL 的八度标注偏差是逐 patch 的，新增乐器必须逐一实测。
- 实测仍用双解码路径（ffmpeg 与 miniaudio，两者逐 Hz 一致）。注意钢琴低音区起振瞬间第 3 泛音
  （C3 的 392 Hz=G4）可强于基频，且延音踏板录音含其他琴弦的共鸣谐振（实测初期版本曾把 G#5 误判成
  其 1/3 频率的 C#4 共鸣）；基频以**起振后稳态窗口**的频谱峰为准，并核对泛音列。
- 源 wav 为 24-bit / 44.1 kHz 立体声、16–33 s 的自然衰减长音。

## 来源与许可证

- **来源仓库**：https://github.com/sgossner/VCSL （采样目录：`Chordophones/Zithers/Grand Piano, Steinway B/Sus/`）
- **锁定 commit**：`c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e`（master，与口琴同批抓取）
- **许可证**：**CC0 1.0 Universal（公共领域）**，同仓库 LICENSE（2026-01-14 提交"Create LICENSE"）。
  patch 附带 `NORMALIZED.txt` 说明：延音/非延音采样已做归一化，力度层次靠 velocity 曲线恢复。
- **原始文件**：不留档（瘦身删除）；重取方式同口琴章（路径 `Chordophones/Zithers/Grand Piano, Steinway B/Sus/`，力度层 vl2/vl3/vl4 同名模式）。

## 处理流程（ffmpeg，与口琴一致的口径）

1. `highpass=f=40` 去次声；
2. 首尾静音去除（-45 dB 阈值，头保留 0.02 s、尾保留 0.05 s；源实际从 t=0 即起振）；
3. **自然衰减取尽**（0.7.7）：不再定长 2.5 s——静音门控让高音自然衰尽（1.4~5.5 s），
   低/中音 `atrim` 上限 **6 s** 触顶（源 16–33 s，游戏内 6 s 处已足够低）；
4. 10 ms 淡入 + 淡出（自然结束 0.15 s / 触顶截断 0.4 s）；
5. 峰值归一化到 **-3 dBFS**：在最终信号域（mono/44.1 kHz）两遍 volumedetect→volume，并对 vorbis
   编码过冲做闭环回退（首轮成品曾冲到 -1.1 dBFS，回退后 -3.0～-3.2 dBFS）；
6. 编码：`libvorbis -q:a 5`，**mono，44100 Hz**。脚本化：`tools/piano-endings.py`（0.7.7）。

验证：ffprobe 确认成品均为 vorbis / 44100 Hz / 1 ch，解码无错误；
成品 ogg 双解码（ffmpeg/miniaudio）实测基频与上表一致（131.0 / 329.5 / 830.9 Hz）。

## 音区覆盖（供服务端 instruments.yml 参考）

客户端 pitch 钳制 [0.5, 2.0]（±1 个八度）：

| 层 | 采样实际音名 | 单层覆盖 | 与相邻层重叠 |
|---|---|---|---|
| low  | C3  | C2 – C4  | 与 mid 重叠 E3–C4（9 半音） |
| mid  | E4  | E3 – E5  | 与 high 重叠 G#4–E5（8 半音） |
| high | G#5 | G#4 – G#6 | — |

拼接总覆盖 **C2 – G#6（43 半音）**，满足设计文档 §7 "≥3 个八度、层间 1–2 半音以上重叠"的要求。
（high 层若需以 G5 为路由中心，把区间上限收到 G6、base-pitch 取 `2^(-2/12)` 即可。）

## 已知缺陷与未尽事项（钢琴）

1. **高音层非 G5 本音**：G#5 采样 +2 半音换算（全音网格无 G），渲染 G5 需 base-pitch 0.891；若追求
   本音可用 `Rel/` 或 Kawai patch 补 G 弦——但会破坏"同 patch 同批次"的音色一致性，V1 不做。
2. ~~2.5 s 截断处钢琴仍在自然衰减中，结尾为 0.3 s 淡出（模拟松踏板），非完整衰减~~
   **0.7.7 已解决**：主采样自然衰减取尽（上限 6 s），松键制音叠加真实收音尾（见下节）。
3. 单力度层（vl3）；~~vl2（更柔）/vl4（更亮）力度表现留给 V2~~ **0.6.0 已交付**（见力度层小节）。

## 制音尾音（0.7.7，松键听感优化）

**问题**：松键制音走 `stopSound` 硬切——Minecraft 声音引擎对播放中的声音只能「立刻停」
或「不管」，没有音量包络。真钢琴制音器落弦是快速指数衰减（低音 ~300 ms、高音近乎瞬时），
瞬间静音听感「戛然而止」。

**方案（0.7.8，按保持时长三档）**：硬切后对同一听众快照叠加同源收音采样。每音区三档
（`sounds: [a, b, c]`），素材取自主采样 0.5/1.5/2.5 s 位置（顶窗 0.2/0.5/0.9 s）、
**不做归一保留天然电平**——服务端按松键时的保持毫秒数选档（<1.2 s → a、<2.4 s → b、
更长 → c），尾音响度与音色尽量接上「松键那一刻的琴音」。播放音量 = 主音 × 0.9 ×
力度缩放。乐器声明式配置：instruments.yml 的 `release-voices`；叠加点 = 松键制音 /
松踏板掐余音 / 闷音键，**不**叠在同半音重击、余音超限淘汰、掉线收尾。

| 窗口 | a 档峰值 | b 档峰值 | c 档峰值 | 实测主导频率（期望） |
|---|---|---|---|---|
| bottom（C1 源） | −8.0 dBFS | −13.6 dBFS | −18.4 dBFS | 32.8 Hz（32.7） |
| low（C3 源）    | −9.6 dBFS | −18.2 dBFS | −22.0 dBFS | 131.2 Hz（130.8） |
| mid（E4 源）    | −18.0 dBFS | −27.3 dBFS | −27.5 dBFS | 334.1 Hz（329.6） |
| high（G#5 源）  | −15.9 dBFS | −27.5 dBFS | −34.6 dBFS | 848.1 Hz（830.6） |
| top（C7 源）    | −19.1 dBFS | −23.4 dBFS | −29.7 dBFS | 与主采样同源保证 |

**已知边界（用户验收口径）**：本方案**不保证完全无缝**——引擎对播放中的声音无音量包络，
硬切点、新旧声音的相位差、三档粒度差（±3 dB 级）都消不掉；它把 0.7.7 固定归一版的
+7~10 dB 跳升压到 ±3 dB 左右。完全无缝的唯一路径是「制音采样」架构（默认音符用自带
~1.5 s 天然结尾的采样、松键不切声音），见 design.md 第十二轮。

**踩坑记录（0.7.7 → 0.7.7b）**：首版尾音用 VCSL `Rel/` 目录 NoSusRel（无踏板演奏录音）
后段——实测均值仅 −70~−90 dB、无基频，归一 +50 dB 后是「高几个八度的怪声」（用户视频
实锤：怪声频率严格按播放变速率跟随所奏音 = 播放参数正确、纯素材问题）。二版改主采样
中段但固定归一 −8 dBFS——按住 0.5~1.5 s 后松键跳升 7~10 dB（视频实测 −40.5 → −32.4）。
教训：①极安静素材 + 大增益归一 = 噪声放大器；②「怪声跟随变调」是区分素材/代码问题的
金标准；③低频测音高要限自相关频段。测量脚本 downloads/measure-pitch.py + video-timeline.py。
4. 排除的其他 VCSL 钢琴 patch：`Grand Piano, Kawai - Legacy`（官方注明"funky tuning"，标注网格不规则）、
   `Grand Piano, Kawai`（目录内 Legacy.txt，旧版残留）、`Upright Piano, Knight / Yamaha`（立式钢琴，
   音色与三角钢琴不统一）。


---

# 架子鼓（Drums）采样资产

世界装置乐器（0.4.0）。12 个鼓件各一条一次性采样事件（`chord:drums.kick` 等，无逐半音注册、
无 note-off）。所有采样来自 **VCSL** 同一 commit `c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e`（CC0），
与口琴/钢琴同仓库同许可。

## 采样一览（数值自成品 ogg 重解码实测；t−20dB = 能量包络衰减 20dB 用时）

| 资源包文件（assets/chord/sounds/drums/） | Sound Event | 时长 | 峰值 | t−20dB | VCSL 源 |
|---|---|---|---|---|---|
| drums_kick.ogg | chord:drums.kick | 0.94 s | −2.5 dBFS | 0.08 s | Bass Drum 1/BDrumNew_hit_v2_rr1_Sum + **合成鼓槌层**（0.7.2：原采样 88% 能量在 250Hz 以下、瞬态无高频成分——笔记本扬声器/小耳机几乎不可闻，用户报「大鼓没声」；起振处混入合成鼓槌层。两轮迭代：首版 −11dB/10ms 仍偏小；**现行版（0.7.2d）= 鼓槌 −6dB/15ms + 鼓体 180Hz 冲击段 +5dB（小喇叭可重放区）+ 75Hz 低架 −3dB + 归一 −2.0dBFS（全鼓组最热）**，前 50ms 高频占比 39%、100~250Hz 冲击段 47%——等响曲线上低频天生吃亏，故大鼓按感知响度而非常峰值校准。重取源：gh api 路径见上表） |
| drums_kick2.ogg | chord:drums.kick2 | 0.93 s | −2.6 | 0.07 s | 同上 v3 变体 |
| drums_snare.ogg | chord:drums.snare | 0.39 s | −3.1 | 0.11 s | Snare Drum, Modern 2/Snare3M_HitSN_v4 |
| drums_rim.ogg | chord:drums.rim | 0.30 s | −6.3 | 0.08 s | 同上 Snare3M_Xstick_v2（十字击=鼓边） |
| drums_hat_closed.ogg | chord:drums.hat.closed | 0.28 s | −5.9 | 0.10 s | Hi-Hat Cymbal/HiHat_Close_rr1 |
| drums_hat_open.ogg | chord:drums.hat.open | 1.67 s | −5.5 | 1.04 s | 同上 HiHat_HitO_rr1 |
| drums_hat_pedal.ogg | chord:drums.hat.pedal | 0.45 s | −7.1 | 0.27 s | 同上 HiHat_HitLoose_rr1（衰减恰居闭/开镲之间） |
| drums_tom_high.ogg | chord:drums.tom.high | 0.72 s | −3.4 | 0.38 s | Tom 1/Stick/TomH_HitS_v2 |
| drums_tom_mid.ogg | chord:drums.tom.mid | 0.72 s | −3.4 | 0.38 s | **与 tom_high 同一 wav**；服务端 base-pitch 0.891 下移 2 半音复用（VCSL 只有两只嗵鼓） |
| drums_tom_floor.ogg | chord:drums.tom.floor | 0.85 s | −3.5 | 0.52 s | Tom 2/Stick/TomL_HitS_v2 |
| drums_crash.ogg | chord:drums.crash | 3.25 s | −4.1 | 0.38 s+wash | Clash Cymbals 1/cymbal_crash1_mf2 |
| drums_ride.ogg | chord:drums.ride | 1.50 s | −4.3 | 0.64 s | Clash Cymbals 2/cymbal_crash2_mp2（atrim 1.5s+0.3s 淡出，见下） |

**ride 选样说明**：首选的 HiHat_HitC（镲边击）实测可听衰减仅 0.32s——比闭镲还短，是"嗒"不是"叮"，
故改用 Clash Cymbals 2 mp2 段截 1.5s。峰值目标按鼓组平衡分件设定（kick −2.5 … hat_pedal −7），
选样校验全部通过（闭镲 ≪ 开镲、tom 高 < tom 低、crash 拖 wash > ride）。

**0.7.2e 全组响度重母带（用户反馈整组远轻于钢琴）**：上表峰值是首版按峰值平衡的产物——鼓是瞬态衰减声，等峰值下感知响度天然低于钢琴持续音（钢琴 vl3 持续 RMS −21.6dB，而鼓边/闭镲首版前 200ms RMS 只有 −25dB 上下）。已按「前 200ms RMS 对齐钢琴 + 分件偏置」整体提升：鼓边/踩镲 +5dB、闭/开镲 +5/+4dB、军鼓/嗵 +3.5dB、大鼓 2.5kHz 鼓槌频段 +3dB，全部经 −1dBFS 限幅天花板（attack 3ms 保瞬态）；乐器 volume 同步 1.3→1.6（可听距离 ≈25.6 格）。教训：打击乐与持续乐器混排必须按响度对齐，峰值归一会系统性偏轻。

**0.7.11c 定稿对齐（用户视频实测：鼓 −27dB vs 钢琴 −11.7dB）**：两个发现——
① **原版 playSound 的 volume>1 只扩大可听距离（16×volume 格）不增加响度**（增益钳制
1.0），drums volume 1.6 从未带来实际增益，0.7.2e/0.7.11b 的「yml 增益折算」模型是错的；
响度对齐只能落在文件 RMS 上。② 视频里鼓音只有 −27dB，与仓库任何近期版本都对不上
（0.7.2e 后鼓件 ≥−20dB）——**客户端在跑 0.7.2e 之前的陈旧资源包**（testing.md 故障
速查的老症状）。本轮把主鼓件推到文件级 −9dB（实测落地 −8.6~−11.4，全部压过新钢琴
起振 −11.5~−12.4dB），角色件（闭镲/鼓边）−13dB；脚本 `tools/drums-boost.py` 按目标差
值增益 + 重限幅，可重复跑。教训：**换包后用「鼓 vs 钢琴同屏对比」验证部署**，别信
客户端缓存的旧包。

## 处理管线（与口琴管线的一处刻意偏离）

1. `highpass=f=40`；2. 首尾静音去除——**阈值取「本源峰值 −45 dB」相对阈值**（VCSL 鼓源电平在
−1.7～−26.5 dBFS，绝对 −45 dBFS 阈值会把低电平源的可听衰减截光，ride 曾被截到 0.10s）；3. 长度上限
（kick/snare/rim/hat 2.5s、tom 3s、crash/ride 3.5s）+ 尾部 0.3s 淡出；4. 分件峰值目标（上表）从中间 wav
闭环编码补偿（禁从成品 ogg 转调增益，实测有 ±1dB 漂移）；5. `libvorbis -q:a 5`，mono，44100 Hz。

---

# 吉他（Guitar）采样资产

手持拨弦乐器（0.4.0），三锚点采样 + 逐半音事件（同口琴架构）。来源 **freepats Spanish Classical
Guitar**（GitHub `freepats/spanish-classical-guitar` @ `6f4eb1b0`，**CC0 1.0**——仓库 LICENSE 与
freepats.zenvoid.org 站点双重声明），三锚点 C3/E4/G5 全部本音采样、同批次。

| 资源包文件（assets/chord/sounds/guitar/） | 实测基频 | 音名 | 时长 | 峰值 |
|---|---|---|---|---|
| guitar_low_c3.ogg | 130.9 Hz | C3（+1.4c） | 3.20 s | −3.1 dBFS |
| guitar_mid_e4.ogg | 329.9 Hz | E4（+1.4c） | 3.20 s | −3.1 dBFS |
| guitar_high_g5.ogg | 783.1 Hz | G5（−2.0c） | **1.13 s** | −3.0 dBFS |

- cents 偏差 ≤2，base-pitch 不校正（2.0 / 0.7937006 / 0.3337158）。
- **高音锚点自然衰减仅 1.13s**：±3 半音内替代（A5/F5/F#5）实测 1.12–1.20s，尼龙弦高音区物理极限，
  库内无更长本音；V1 接受（拨弦高音本就衰减快），换源需整个乐器同批次。
- C3 起振早窗（0.1–0.7s）实测 +12.1c——拨击瞬态偏尖，0.5s 后稳定，base-pitch 以稳态值为准。
- 处理管线与口琴完全一致（源近满电平，绝对 −45dB 阈值无鼓件的低电平问题）。

---

# 贝斯（Bass）采样资产

手持拨弦乐器（0.4.0）。来源 **karoryfer "Black And Blue Basses" darkblack**（Squier 类电贝斯，
GitHub `sfzinstruments/karoryfer.black-and-blue-basses` @ `6e7d674c`，**CC0 1.0**；
readme 原文 "Royalty-free for all commercial and non-commercial use. Copyright 2014 Karoryfer Lecolds."），
mf 力度层 rr1 同批次。

| 资源包文件（assets/chord/sounds/bass/） | 实测基频 | 音名 | 锚点说明 | 时长 | 峰值 |
|---|---|---|---|---|---|
| bass_low_c2.ogg | 65.4 Hz | C2（−0.7c） | C2 本音 | 3.20 s | −3.1 dBFS |
| bass_mid_e3.ogg | 164.1 Hz | E3（−7.6c） | E3 本音 | 3.20 s | −3.1 dBFS |
| bass_high_e4.ogg | 325.6 Hz | E4（−21.3c） | 库内最高采样；−21.3c 为乐器高把位固有偏平 | 3.20 s | −3.0 dBFS |

- **karoryfer 八度命名陷阱**：文件名八度比科学音名**高一个八度**（`e4` 文件 = 实测 E3、`e5` = E4），
  两个贝斯库实测一致——取样必须逐一实测，禁止照抄文件名（与 VCSL 口琴「低一个八度」方向相反）。
- base-pitch 按实测校正：mid 1.5943130（+7.6c）、high 0.8035140（+21.3c）；low 4.0（−0.7c 忽略）。
  high 锚点实为 E4（非 G4 设计目标，库内 G4 ±3 半音仅此一件压线），voices 窗口随锚点调整为
  mid 顶 +3 / high 从 +4 起（shift=0 处音准最佳，同时避开 2.0 钳制缘）。
- 处理管线同吉他。

---

# 小提琴（Violin）采样资产

手持拉弦乐器（0.8.0），`release-mode: stop`——弓离弦即止音，硬切物理上成立（同口琴口径），
无 release 尾音表。15 锚点 G3~C7 × p/f 双力度层（复用钢琴 0.6.0 力度管线：柔音层
velocity 1~63，常规层 64~127 = 顶层 voices）。

## 来源与许可证

- **来源**：Versilian Studios **VSCO 2 CE**（Versilian Community Orchestra 2, Community Edition），
  官方 S3 分发包 `VSCO-2.1-XRNI-20170216.zip`（776MB，整包未留档），patch = **SViolinVib**
  （独奏小提琴 arco vibrato 延音，p/f 双力度层；另有 SViolinVib-Quiet / Trem / Spic / Pizz 未用）。
- **许可证**：**CC0 1.0 Universal**（包内 LICENSE 与官网双重声明；VPO3 镜像 `libs/VSCO2-CE/LICENSE.txt` 同文可查）。
- **锁定**：S3 对象无 commit 概念，以文件名日期 `20170216` 为锁定版本；重取
  `python tools/violin-rebuild.py`（HTTP Range 解析中央目录后**只抽 SViolinVib.xrni 一个条目**，
  勿整包下载 776MB；xrni 与 flac 缓存 `downloads/violin-src/`，打包结构性排除）。
- VCSL（0.4~0.7 各乐器的来源库）**没有提琴类**（树完整遍历确认）；SSO/Sonatina 小提琴（VPO3 内）
  为 CC-BY-SA 许可证不符，排除。

## 采样一览表（数值自成品 ogg 实测，多窗中位数口径）

15 锚点 = patch 原生 G/A/C/E 网格（间距 2~4 半音），每锚点两个文件：
`violin_{音名}.ogg`（常规层 vl-f）/ `violin_soft_{音名}.ogg`（柔音层 vl-p），事件族
`chord:violin.{音名}.%d` / `chord:violin.soft.{音名}.%d`。

| 锚点音 | 常规层 f0（偏差） | 柔音层 f0（偏差） | voices 窗口 |
|---|---|---|---|
| G3 | 195.4 Hz（−5¢） | 195.9 Hz（−2¢） | [−5,−5] |
| A3 | 220.2 Hz（+2¢） | 220.4 Hz（+4¢） | [−4,−2] |
| C4 | 259.4 Hz（−15¢） | 265.1 Hz（+23¢） | [−1,+1] |
| E4 | 330.5 Hz（+5¢） | 330.8 Hz（+6¢） | [+2,+5] |
| G4 | 393.1 Hz（+5¢） | 388.7 Hz（−15¢） | [+6,+7] |
| A4 | 443.6 Hz（+14¢） | 440.8 Hz（+3¢） | [+8,+10] |
| C5 | 523.0 Hz（−1¢） | 522.6 Hz（−2¢） | [+11,+13] |
| E5 | 658.9 Hz（−1¢） | 661.2 Hz（+5¢） | [+14,+17] |
| G5 | 788.3 Hz（+9¢） | 779.5 Hz（−10¢） | [+18,+19] |
| A5 | 877.1 Hz（−6¢） | 876.7 Hz（−6¢） | [+20,+22] |
| C6 | 1035.2 Hz（−19¢） | 1035.3 Hz（−19¢） | [+23,+25] |
| E6 | 1329.1 Hz（+14¢） | 1329.4 Hz（+14¢） | [+26,+29] |
| G6 | 1587.4 Hz（+21¢） | 1568.7 Hz（+1¢） | [+30,+31] |
| A6 | 1755.9 Hz（−4¢） | 1754.9 Hz（−5¢） | [+32,+34] |
| C7 | 2086.8 Hz（−5¢） | 2102.2 Hz（+8¢） | [+35,+40] |

- 成品全部 vorbis / 44100 Hz / **mono** / 3.0 s；RMS 全部对齐 **−11.3 dB**（口琴同一混音基准）。
- **偏差口径**：揉弦采样单窗 FFT 会锁到揉弦瞬时相位（G6 常规层四窗实测 −42¢~+25¢ 摆动），
  感知音高 = 摆动中心——`violin-rebuild.py` 把 0.15~2.95s 切 4 窗分别测、取**音分中位数**。
  ±23¢ 内不视为跑调；base-pitch 按中位数换算，播放端精确补偿。
- **键位窗口沿用 patch 原生键位表**（xrni Instrument.xml 逐音核对，就近路由、最大拉伸 ±2 半音），
  顶部 C7 锚点上探 +4 到 E7（s=+40，播放 pitch 1.26，钳制内）。

## 处理流程（ffmpeg，口琴 0.7.14 口径 + 两处偏离）

1. `highpass=f=40` 去次声；
2. 首尾静音去除（−45dB，头留 0.005s、尾留 0.05s）；
3. 起振裁切：首个 5ms 窗 RMS ≥ 峰值−12dB。小提琴揉弦起弓普遍很快（多数 0~200ms），
   少数慢起弓（C4 两层 / c6 / e6 柔音层）裁到 400~960ms——自动适配，不手工干预；
4. 4ms 淡入 + 限长 3.0s（源 10~16s 的长延音，限制为与口琴一致）+ 0.15s 淡出；
5. 峰值归一 −3dBFS 后 **RMS 对齐 −11.3dB**，峰值超 −2.5dBFS 走 `alimiter`（必须 `level=false`）。
   **与口琴的偏离 ①：p/f 两层同对齐 −11.3**——层切换只变音色（弓压/亮度）不变响度，
   避免 63/64 力度边界跳变，强弱交给 velocityScale 连续缩放（钢琴 vl2/vl4 是峰值归一、
   层间自然电平差保留，两者口径不同是刻意的）；
   **偏离 ②：测音为 4 窗中位数**（见上）；
6. 编码 `libvorbis -q:a 5`，mono，44100 Hz；逐音 ffprobe 验证 + 事件→ogg 链路脚本校验。

## 音区覆盖（供服务端 instruments.yml 参考）

`semitone-range: [-5, 40]` = G3~E7 真实音域直接放开：**底部不做采样下探假音**
（口琴章「下探把音色压窄」的教训——低音区留给将来的中提琴/大提琴）；顶部 C7 拉 +4 到 E7。
默认音级键可达 [0, +25]，G3~B3（−5~−2）经低八度修饰键/直排音演奏。
力度：柔音 vl-p（1~63）/ 常规 vl-f（64~127），层间同响度（见上），无第三层
（SViolinVib-Quiet 实为另一录制，未采用——两层已覆盖滚轮 40~127 的表达范围）。

## 已知缺陷与未尽事项

1. **C4 两层偏差反向**（常规 −15¢ / 柔音 +23¢）：同音两层录制的音准差，均已按实测精确补偿，
   播放端听不出；若未来换源可关注。
2. **单力度层揉弦速率固定**：真小提琴揉弦随力度/情绪变化，两层采样各有固定揉弦；
   弓法/揉弦表现（tremolo、pizzicato）VSCO 同 patch 还有 SViolinTrem / SViolinPizz，
   留作技法扩展（对照 design.md 技法方案 A+C+E）。
3. **3.0s 限长 + 淡出收尾**：长音按住超过 3s 后会淡出（采样结束），长弓乐句受限；
   如需更长可把 `violin-rebuild.py` 限长参数提高（源 10~16s 足够），注意 5MB 包熔断线
   （当前包 3.8MB）。

---

# 0.4.0 采样交付总表

三乐器 18 个 ogg 全部 vorbis/44100Hz/mono、ffmpeg 全解码零错误。逐文件实测数据（含鼓件能量包络、
选样对比、排除候选清单与 GitHub 下载通道备忘）见 **`downloads/report-0.4.0-guitar-bass-drums.md`**。
许可证：VCSL / freepats / karoryfer 全部 **CC0 1.0**，无署名法律义务（惯例致谢建议保留）。
原始 wav/flac 不留档（瘦身删除，路径见各章来源段，gh api 重取）；downloads/ 仅保留实测报告。


---

# 钢琴力度分层采样（0.6.0）

`chord:piano.soft.*` / `chord:piano.loud.*` 事件族（vl2 柔音 / vl4 强音，与既有 vl3 常规层同锚点）。
同一 VCSL Steinway B Sus Close patch、同 commit，处理管线与 vl3 完全一致（0.7.7 起为自然衰减取尽/6s 触顶 + −3dBFS + vorbis q5 mono 44.1k，脚本 `tools/piano-endings.py`）。

| 资源包文件 | 力度层（velocity） | 实测基频 | 与标注偏差 |
|---|---|---|---|
| piano_soft_low_c3 / soft_mid_e4 / soft_high_gs5 | vl2，1~63 | 130.9 / 329.1 / 828.0 Hz | +0.6 / −2.8 / −5.4 c（≤6c 不校正 base-pitch） |
| piano_loud_low_c3 / loud_mid_e4 / loud_high_gs5 | vl4，114~127 | 131.2 / 329.6* / 828.8 Hz | +5.8 / 0* / −3.8 c |

\* loud_mid_e4 稳态窗二泛音（659Hz）略强于基频（382 vs 473），粗测自相关会误锁高八度——泛音列核对确认基频 329.6Hz 存在且能量充足，采样正确；此为 vl4 强奏的亮度特征，非标注错误（前人口径：钢琴基频必须核对泛音列）。

下载通道备注：raw.githubusercontent 本机偶发 SSL 中断，改用 `gh api -H "Accept: application/vnd.github.raw" repos/sgossner/VCSL/contents/<路径>`；路径中的 `#` 需编码为 `%23`（G#5 文件名）。


## 88 键全域扩展（0.7.4）

两端各加一锚点八度（同 patch、同管线、vl2/vl3/vl4 三层）：

| 锚点 | 事件族 | 实测基频 | 标称偏差（Railsback 拉伸调音） | base-pitch | 窗口 |
|---|---|---|---|---|---|
| C1 | chord:piano.bottom.* | 32.50 Hz | −10.8c（低音区偏平） | 8.0500 | [−39,−25]（A0 起） |
| C7 | chord:piano.top.* | 2109.2 Hz | +13.3c（高音区偏尖） | 0.1240 | [+33,+48]（至 C8，+48 恰在钳制 1.985） |

C1 基频过低（32.7Hz）无法直接谱峰测量，用泛音列（谱峰按最接近标称的整数倍除回）推算；两端实测偏差即钢琴拉伸调音曲线，已折算进 base-pitch。中央三锚点（C3/E4/G#5）不变。

---

# 唢呐（Suona）采样资产（0.9.0）

**本项目原创合成采样**：调研确认无现成 CC0 唢呐采样库（商业 Kong Audio 不免费；
freesound 有登录墙属已排除通道；GM 音源 Shanai 补丁采样来源授权不清），故由
`tools/suona-synth.py`（numpy）合成，**无任何第三方素材**，授权随本仓库（无额外
负担）。11 锚点 C/E/G 网格，`chord:suona.<锚点>` 事件族 + 逐半音事件 43 个，
`release-mode: stop`（气息类，同口琴）。

## 合成配方（可复现；参数集中在脚本顶部，重渲染即调音）

物理依据：唢呐 = 锥管 + 双簧 → 全谐波亮谱（近似锯齿波、上谐波段偏强）+ 喇叭口
共振峰；标志性打音起振（音高上滑）与迟起颤音。

1. 带限锯齿加法合成：谐波幅度 `1/n^tilt`（tilt = 0.85 + 0.006×半音，低音更亮、
   高音不过分刺耳），逐谐波随机初相（固定种子 `20260+semi`，可复现）；
2. 共振峰整形（喇叭口/锥管）：1050 / 2500 / 4800 Hz 三峰 boost + 7kHz 滚降，
   上限 16kHz 带限；
3. 打音起振：频率从 −60 音分上滑（时间常数 20ms），振幅 18ms 起振 + 6% 过冲
   回落，叠加 800~6000Hz 带通白噪 × 25ms 衰减的气息瞬态；
4. 颤音 5.4Hz × 13 音分（250ms 后 350ms 渐入）+ ±2.5 音分缓慢漂移 + 1.5% 气颤
   ——避免合成感的「直」；
5. 4ms 硬淡入 + 限长 3.0s + 0.15s 淡出；峰值 −3dBFS 后 RMS 对齐 **−11.3dB**
   （全乐器混音基准，alimiter `level=false` 同踩坑口径）；编码 vorbis q5 mono 44.1k。

## 采样一览表（11 锚点；数值自成品 ogg 实测）

| 资源包文件（assets/chord/sounds/suona/） | Sound Event | 实际音名（实测） | 实测基频 | base-pitch | 体积 |
|---|---|---|---|---|---|
| `suona_c4.ogg` | `chord:suona.c4` | **C4**（−1¢） | 261.5 Hz | 1.0003066 | 40KB |
| `suona_e4.ogg` | `chord:suona.e4` | **E4**（+3¢） | 330.3 Hz | 0.7921357 | 23KB |
| `suona_g4.ogg` | `chord:suona.g4` | **G4**（−1¢） | 391.9 Hz | 0.6676234 | 40KB |
| `suona_a4.ogg` | `chord:suona.a4` | **A4**（+2¢） | 440.6 Hz | 0.5937612 | 20KB |
| `suona_c5.ogg` | `chord:suona.c5` | **C5**（−2¢） | 522.5 Hz | 0.5006731 | 39KB |
| `suona_e5.ogg` | `chord:suona.e5` | **E5**（−0¢） | 659.1 Hz | 0.3969589 | 23KB |
| `suona_g5.ogg` | `chord:suona.g5` | **G5**（−3¢） | 782.7 Hz | 0.3342714 | 20KB |
| `suona_a5.ogg` | `chord:suona.a5` | **A5**（−3¢） | 878.3 Hz | 0.2978681 | 32KB |
| `suona_c6.ogg` | `chord:suona.c6` | **C6**（−2¢） | 1045.4 Hz | 0.2502542 | 18KB |
| `suona_e6.ogg` | `chord:suona.e6` | **E6**（−5¢） | 1314.9 Hz | 0.1989666 | 19KB |
| `suona_g6.ogg` | `chord:suona.g6` | **G6**（+8¢） | 1574.9 Hz | 0.1661214 | 17KB |

时长统一 3.0s，共 291KB。实测偏差即颤音/漂移的时窗偏置（≤8¢），已折算进
base-pitch。频谱体检（替代人耳验收口径）：频谱质心全音域 3.4~3.8kHz（亮而统一）；
低中音区前 6 谐波形满（双簧「嗡」），高音区基频主导（「啸」不过分刺耳）。

## 音区覆盖（供服务端 instruments.yml 参考）

`semitone-range: [0, 31]`（C4~G6，高音主奏定位——真实唢呐即为高音乐器，不做
低音区采样下探假音，沿用小提琴章「下探把音色压窄」的教训）。11 锚点间距 ≤4
半音，逐半音就近路由最大拉伸 ±2 半音（变速透明），全部落在客户端钳制内。
默认音级键可达 [0, +25]，顶部 6 个半音（+26~+31）经 direct_notes 直排音演奏。

## 已知边界与替换路径

1. **合成器级真实度**：游戏内辨识度够（亮、嗡、啸俱全），但骗不过听过真唢呐的
   行家（缺气声细节与指孔噪声）。
2. **真人采样替换路径**：拿到录音后照 `tools/harmonica-rebuild.py` 写
   `suona-rebuild.py`（下载/读入→同一套裁切/归一化/实测管线），voices 与
   base-pitch 由脚本重新生成，`gen-sounds-json.py` 的 BASE_OGG 行与
   instruments.yml 片段同步重贴即可，其余管线不变。
3. p/f 双力度层未做（包体预算：当前 chord.zip 4.17MB / 熔断 5MB），需要时照
   violin 层结构扩展（合成脚本的 layer 化即 tilt/brightness 参数两档）。
