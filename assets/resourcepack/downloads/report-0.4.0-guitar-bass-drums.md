# Chord 0.4.0 采样交付实测报告 — 架子鼓 / 吉他 / 贝斯

交付日期 2026-10-07。全部 18 个成品 ogg 均为 **vorbis / 44100 Hz / mono**，ffmpeg 全解码无错误，
单文件 6.9–33.4 KB。基频/衰减/峰值全部**从成品 ogg 重新解码实测**（非源文件读数）。

- 鼓件 12 件：`assets/chord/sounds/drums/`（VCSL，CC0）
- 吉他 3 锚点：`assets/chord/sounds/guitar/`（freepats Spanish Classical Guitar，CC0）
- 贝斯 3 锚点：`assets/chord/sounds/bass/`（karoryfer Black And Blue Basses "darkblack"，CC0）
- 原始文件留档本目录（`vcsl_drums_*.wav` / `freepats_guitar_*.flac` / `karoryfer_bass_*.wav`）

---

## 一、架子鼓（VCSL @ commit `c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e`，CC0）

raw URL 前缀实测为 `https://raw.githubusercontent.com/sgossner/VCSL/c1ea7bcc…/<路径>`（**不带** `Samples/` 前缀）。

### 1.1 成品一览（所有数值来自成品 ogg 重解码）

| 成品 | VCSL 源（仓库根相对路径） | 时长 | 峰值 dBFS | t−20dB | 大小 |
|---|---|---|---|---|---|
| drums_kick.ogg | Membranophones/Struck Membranophones/Bass Drum 1/BDrumNew_hit_v2_rr1_Sum.wav | 0.94 s | −2.5 | 0.08 s | 12.1 KB |
| drums_kick2.ogg | …/Bass Drum 1/BDrumNew_hit_v3_rr1_Sum.wav | 0.93 s | −2.6 | 0.07 s | 11.9 KB |
| drums_snare.ogg | …/Snare Drum, Modern 2/Snare3M_HitSN_v4_rr1_Mid.wav | 0.39 s | −3.1 | 0.11 s | 7.8 KB |
| drums_rim.ogg | …/Snare Drum, Modern 2/Snare3M_Xstick_v2_rr1_Mid.wav | 0.30 s | −6.3 | 0.08 s | 7.0 KB |
| drums_hat_closed.ogg | Idiophones/Struck Idiophones/Hi-Hat Cymbal/HiHat_Close_rr1_Mid.wav | 0.28 s | −5.9 | 0.10 s | 7.1 KB |
| drums_hat_open.ogg | …/Hi-Hat Cymbal/HiHat_HitO_rr1_Mid.wav | 1.67 s | −5.5 | 1.04 s | 22.7 KB |
| drums_hat_pedal.ogg | …/Hi-Hat Cymbal/HiHat_HitLoose_rr1_Mid.wav | 0.45 s | −7.1 | 0.27 s | 9.0 KB |
| drums_tom_high.ogg | …/Tom 1/Stick/TomH_HitS_v2_rr1_Mid.wav | 0.72 s | −3.4 | 0.38 s | 10.3 KB |
| drums_tom_mid.ogg | （同 tom_high，同一 wav） | 0.72 s | −3.4 | 0.38 s | 10.3 KB |
| drums_tom_floor.ogg | …/Tom 2/Stick/TomL_HitS_v2_rr1_Mid.wav | 0.85 s | −3.5 | 0.52 s | 11.4 KB |
| drums_crash.ogg | …/Clash Cymbals 1/cymbal_crash1_mf2.wav | 3.25 s | −4.1 | 0.38 s | 33.3 KB |
| drums_ride.ogg | …/Clash Cymbals 2/cymbal_crash2_mp2.wav（替换，见 1.3） | 1.50 s | −4.3 | 0.64 s | 18.9 KB |

t−20dB = 能量包络（10 ms RMS 窗）从最大值衰减 20 dB 的时间。选样校验：闭镲 0.10 s ≪ 开镲 1.04 s ✓；
踩镲 0.27 s 居中 ✓；tom_high 0.38 s < tom_floor 0.52 s ✓；crash（0.38 s 后仍拖 2.9 s 低电平 wash）> ride ✓。

峰值与目标偏差：全部 ≤0.3 dB，唯 rim −6.3（目标 −5.5，差 0.8 dB）——0.29 s 的短瞬样本经 vorbis 后峰值收缩，
闭环补偿 4 轮仍不收敛，取保守方向（更轻），报告如实记录。

### 1.2 处理管线（对 README 口琴管线的一处刻意偏离）

1. `highpass=f=40` → mono/44.1k；
2. 首尾静音去除：**阈值取「本源峰值 −45 dB」（相对阈值）**，头留 0.01 s、尾留 0.05 s；
3. `atrim` 长度上限（kick/snare/rim/hat 2.5 s、tom 3 s、crash/ride 3.5 s）+ 尾部 0.3 s 淡出；
4. 峰值归一到各件目标（kick −2.5 / snare −3 / rim −5.5 / hat_c −6 / hat_o −5.5 / hat_p −7 / tom −3.5 /
   crash −4 / ride −4.5 dBFS），从中间 wav 闭环编码（编码→解码实测峰值→补偿增益→重编，≤±0.25 dB 收敛；
   禁止从成品 ogg 转码调增益——实测会引入 ±1 dB 以上不可控漂移）；
5. `libvorbis -q:a 5`，mono，44100 Hz。

**偏离原因（重要，后人复用）**：任务原文是绝对 −45 dBFS 阈值，但 VCSL 鼓源录音电平峰值在 −1.7 ～ −26.5 dBFS
（mono 下变换后），绝对阈值等于只保留「峰值下 18～43 dB」的内容：ride（峰值 −26.5 dBFS）被截到 **0.10 s**、
hat_closed 0.11 s，成品是"咔哒"声而非鼓件。改为相对阈值（等价于先把源归一到满电平再跑 README 原管线）后，
各件保留到峰值下 45 dB 的自然衰减，行为对所有源统一。长度上限全部未触发（除 ride 按替换规则 1.5 s 截断）。

### 1.3 鼓件选样与替换说明

- **hat_pedal（踩镲）**：按任务在 HitLoose 与 HitC 系列间二选一。实测（相对峰值衰减）HitLoose t−20dB=0.29 s、
  rel−45dB=0.54 s；HitC_v2/v3/v4 分别为 0.09/0.15/0.18 s、rel−45dB 0.32/0.37/0.42 s。**选 HitLoose**：
  ① 衰减恰居闭镲（0.10 s）与开镲（1.04 s）之间，符合踩镲"闷响+短促余音"；② HitC 本质是"槌击闭镲"，与
  hat_closed（Close_rr1）和 ride 候选同质，会让三件听起来一样；③ 电平目标 −7 dB（最轻的镲件）匹配其松散音色。
- **ride（叮镲）**：任务首选 HiHat_HitC_v2（镲边击"叮"）。实测其 t−20dB=0.09 s、可听衰减仅 0.32 s，
  **比闭镲还短**——是"嗒"不是"叮——"，与任务预设的"衰减太长"风险相反方向地不合格。**改用任务指定的
  备选方案**：Clash Cymbals 2 的 mp2 段（该 patch 无 mf 段，mp2 峰值 −10.8 dBFS 比 mp1 −15.3 录音更健康），
  `atrim 1.5 s + 0.3 s 淡出`，t−20dB=0.64 s，"叮+短 wash"质感成立。若需更接近镲边"叮"音色，
  HitC_v4（三变体中最长 0.42 s）是次选，但衰减仍短。
- **tom_mid（中嗵）**：VCSL 只有 Tom 1（TomH）与 Tom 2（TomL）两只嗵鼓，无独立中嗵采样。按任务指示，
  tom_mid 与 tom_high **共用同一 wav**（TomH_HitS_v2_rr1_Mid）同样处理、两份相同 ogg 交付；
  服务端用 `base-pitch = 2^(−2/12) ≈ 0.891` 下移 2 半音复用（本报告只产音频，路由由你配置）。

---

## 二、吉他（freepats Spanish Classical Guitar，CC0）

- **来源**：GitHub `freepats/spanish-classical-guitar` @ main `6f4eb1b0`（2022-07-23），
  文件 `samples/{C3,E4,G5}.flac`（44.1 kHz / mono / FLAC，时长 4.30/3.75/1.42 s）。
  主页 https://freepats.zenvoid.org/Guitar/acoustic-guitar.html 原文 "Published under the terms of the
  Creative Commons CC0 1.0 public domain dedication"；仓库 LICENSE 为 CC0 法律文本。
- 三锚点 **C3 / E4 / G5 全部为本音采样，零移调**；同乐器同批次（同一次录音会话的同一网格）。

### 2.1 成品一览（f0 为稳态窗实测：起振后 0.3–1.2 s 自相关 + FFT 抛物线精化，自成品 ogg 解码）

| 成品 | 实测基频 | 音名（cents） | 时长 | 峰值 | ffprobe | 大小 |
|---|---|---|---|---|---|---|
| guitar_low_c3.ogg | **130.92 Hz** | C3（+1.4c） | 3.20 s | −3.1 dBFS | vorbis/44100/1ch | 27.3 KB |
| guitar_mid_e4.ogg | **329.89 Hz** | E4（+1.4c） | 3.20 s | −3.1 dBFS | vorbis/44100/1ch | 28.8 KB |
| guitar_high_g5.ogg | **783.10 Hz** | G5（−2.0c） | 1.13 s | −3.0 dBFS | vorbis/44100/1ch | 12.8 KB |

注：C3 起振后 0.1–0.7 s 早窗实测 131.74 Hz（+12.1c）——尼龙弦拨击瞬态偏尖，0.5 s 后稳定在 130.8–130.9 Hz；
上表以稳态值为准（游戏内听感由稳态主导），base-pitch 请用 130.92 Hz。

**已知偏差（唯一）**：guitar_high_g5 自然衰减仅 1.13 s（源 flac 总长 1.42 s，静音修剪后 1.13 s），
低于任务"≥1.5 s"偏好 0.37 s。排查了 ±3 半音内全部替代：A5（884.29 Hz，修剪后 1.20 s）、F5（697.77 Hz，
1.12 s）、F#5（未测完，文件更短）——高音区尼龙弦物理衰减就在 1.1–1.2 s，无更长的本音可选；
不换低音区样本上移（破坏音色一致性）。如需更长可考虑 VSCO-2-CE 的 Harp（G5 有 mf 采样，同 CC0），
但竖琴≠吉他、且与低中锚点不同乐器，未采用。

### 2.2 处理管线（与 README 口琴完全一致）

`highpass=f=40` → `silenceremove` 绝对 −45 dB（头 0.02 s / 尾 0.05 s；源峰值 −0.1～−0.3 dBFS 近满电平，
绝对阈值即相对阈值，无鼓件那处的低电平问题）→ `atrim 0:3.2` → 10 ms 淡入 + 0.3 s 淡出 →
峰值 −3 dBFS（闭环编码）→ `libvorbis q5 mono 44100`。

---

## 三、贝斯（karoryfer "Black And Blue Basses" darkblack，CC0）

- **来源**：GitHub `sfzinstruments/karoryfer.black-and-blue-basses` @ master `6e7d674c`（2024-12-04），
  文件 `Samples/darkblack/reg/darkblack_{c3,e4,e5}_mf_rr1.wav`（24-bit / 44.1 kHz / mono / 5.0 s，
  mf 力度层 rr1，同琴同批次）。LICENSE 为 CC0 法律文本；readme.txt 原文 "Royalty-free for all commercial
  and non-commercial use. Copyright 2014 Karoryfer Lecolds."
- **避坑（重要）**：karoryfer 两个贝斯库的**文件名八度 = 科学音名 + 1 个八度**。实测铁证：
  `darkblack_c2`→C1（32.97 Hz）、`c3`→C2（65.28 Hz）、`e3`→E2（82.28 Hz）、`e4`→E3、`e5`→E4、
  `g4`→G3（194.89 Hz）；growlybass 同口径（`db2`→C#1 34.65 Hz **且缺基频**，只有 2f..9f 泛音列）。
  取样必须像本报告一样逐一实测，禁止照抄文件名。

### 3.1 成品一览（稳态窗实测，自成品 ogg 解码）

| 成品 | 实测基频 | 音名（cents） | 锚点偏差 | 时长 | 峰值 | ffprobe | 大小 |
|---|---|---|---|---|---|---|---|
| bass_low_c2.ogg | **65.38 Hz** | C2（−0.7c） | C2 本音 | 3.20 s | −3.1 dBFS | vorbis/44100/1ch | 26.5 KB |
| bass_mid_e3.ogg | **164.09 Hz** | E3（−7.6c） | E3 本音 | 3.20 s | −3.1 dBFS | vorbis/44100/1ch | 26.5 KB |
| bass_high_e4.ogg | **325.59 Hz** | E4（−21.3c） | G4 目标 **−3 半音**（压线） | 3.20 s | −3.0 dBFS | vorbis/44100/1ch | 23.3 KB |

**高音锚点说明**：库内科学音名最高采样为 E4（`e5` 文件）。G4（392 Hz）±3 半音内仅此一件（E4=329.6 Hz），
恰好压在规格边界。渲染 G4 需 `base-pitch = 2^(3/12) ≈ 1.189`（或以实测 325.59 Hz 换算）。
−21.3c 偏平为**乐器本身音准**（该琴高把位一致偏平：mf/f 力度 × rr1–4 共 5 个变体实测 325.48–325.73 Hz，
非采样随机误差），服务端按实测基频换算 base-pitch 即可在输出端完全校正。
同库另一把 babyblue 无 e5（最高 eb5→科学 D#4，距 G4 −4 半音出界）且音准更差（e4→E3 实测 −16.6c），
未采用。处理管线同吉他（§2.2），源峰值 −7.3～−10.7 dBFS，绝对 −45 dB 阈值≈相对 −34～−38 dB，
修剪后仍留满 3.2 s，无需相对阈值。

### 3.2 吉他/贝斯锚点速查表（base-pitch 计算用，0.1 Hz 精度）

| 层 | 文件 | 实测 f0 (Hz) | 音名 | cents |
|---|---|---|---|---|
| guitar low | guitar_low_c3.ogg | 130.9 | C3 | +1.4 |
| guitar mid | guitar_mid_e4.ogg | 329.9 | E4 | +1.4 |
| guitar high | guitar_high_g5.ogg | 783.1 | G5 | −2.0 |
| bass low | bass_low_c2.ogg | 65.4 | C2 | −0.7 |
| bass mid | bass_mid_e3.ogg | 164.1 | E3 | −7.6 |
| bass high | bass_high_e4.ogg | 325.6 | E4 | −21.3 |

---

## 四、许可证清单

| 用途 | 来源 | 许可证 | 署名义务 |
|---|---|---|---|
| 鼓件 12 | sgossner/VCSL @ `c1ea7bcc` | **CC0 1.0**（仓库 LICENSE，README 明示公共领域） | 无（建议惯例致谢 "VCSL / Versilian Studios LLC, CC0"） |
| 吉他 3 | freepats/spanish-classical-guitar @ `6f4eb1b0` | **CC0 1.0**（仓库 LICENSE + 站点声明） | 无 |
| 贝斯 3 | sfzinstruments/karoryfer.black-and-blue-basses @ `6e7d674c` | **CC0 1.0**（LICENSE；readme："Royalty-free for all commercial and non-commercial use. Copyright 2014 Karoryfer Lecolds."） | 无 |

**本次交付未使用任何 CC-BY / 需署名来源**，无署名法律义务。

---

## 五、排除的候选来源（避坑清单）

1. **VCSL 吉他/贝斯**：树缓存全查——无 Guitar、无 Electric Bass、无任何 pizz 目录；拨弦只有
   Concert Harp / Folk Harp / Strumstick（竖琴/Strumstick 非吉他）。
2. **VSCO-2-CE**（sgossner，GitHub LICENSE 实为 **CC0** 而非任务假设的 CC-BY 4.0）：无吉他无电贝斯；
   Solo Contrabass pizz 网格太低太稀（科学音名 E0–B2，E3/G4 锚点无样本）；Cello Section pizzT
   （B1/E3/F4 可覆盖贝斯三锚点）与 Harp（C3/D4/F4/G5 可覆盖吉他）是可用兜底，但均为乐团/竖琴音色，未采用。
3. **freepats electric-bass-YR**（Yamaha RBX，CC0）：sustain 只采了 E1–D#2 一个八度（SFZ 键位 26–45），
   E3/G4 锚点差 9–17 半音，不可用。
4. **karoryfer growlybass**（Squier Jazz Bass，CC0）：sustain 网格科学音名最高 D#4（311 Hz），
   距 G4 锚点 −4 半音出界；且 `db2` 样本缺基频（只有 2f 以上泛音），低音实测易误判高八度。
5. **black-and-blue-basses 的 babyblue**：无 e5（最高科学 D#4）、音准差于 darkblack（见 §3.1）。
6. **sfzinstruments/guitars 索引中的 Karoryfer 吉他**（Black_And_Green / Shinyguitar 等）：虽 CC0，
   但 352 MB–2 GB 整包、托管在 karoryfer.com 非 GitHub raw，取三个单音成本过高；其余条目 MIT/自定义/商业许可混杂。
7. **freesound CC0 预览 CDN**：未走到（在之前已拿到全质量 CC0 源）；提醒后人其预览仅 128k 有损音质。
8. **Karoryfer 八度命名陷阱**：文件名比科学音名高一个八度（§3 避坑，两个仓库实测一致）。
9. **鼓件绝对 −45 dB 静音阈值陷阱**：对低电平源会截掉可听衰减（ride 曾被截到 0.10 s），见 §1.2。
10. GitHub API 匿名限额易满（本机已遇 403）：用 `gh api` 认证路径；raw.githubusercontent.com 偶发 SSL 中断，
    `gh api -H "Accept: application/vnd.github.raw" repos/…/contents/…` 是可靠替代下载通道。

## 六、验证方法备忘

- 格式：ffprobe 逐文件确认 vorbis/44100/1ch；`ffmpeg -v error -i x.ogg -f null -` 全解码零错误。
- 基频：**从成品 ogg 解码**，稳态窗（起振后 0.3–1.2 s）归一化自相关（抗缺基频）+ FFT 抛物线插值精化，
  并以泛音列核对（全部样本 harmonic score ≥0.99；growlybass db2 因缺基频被正确识别并弃用）。
  本报告仅 ffmpeg 单解码路径（无 miniaudio 环境）；早窗/稳态窗双窗交叉验证过 C3 与 bass_high 的读数稳定性。
- 鼓件：10 ms RMS 能量包络的 t−20dB、时长、峰值（volumedetect 交叉核对）。
