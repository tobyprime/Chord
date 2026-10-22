# Chord

[![CI](https://github.com/tobyprime/Chord/actions/workflows/ci.yml/badge.svg)](https://github.com/tobyprime/Chord/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> Minecraft 乐器演奏方案：**演奏者装 Fabric 模组，服务端跑 Folia 插件广播原版协议音频——聆听者零安装即可听到。**
> 操作复刻三角洲行动「守夜人口琴」：`Z X C V B N M ,` 八键一八度，按住鼠标左/右键降/升八度、中键升半音，全半音阶，开放和弦。

设计文档：[docs/design.md](docs/design.md)

## 仓库结构

```
protocol/   共享协议模块（chord:play / chord:sync 的编解码，纯 Java 零依赖）
fabric/     客户端 Fabric 模组共享源码（演奏态、键位、HUD、发包）
fabric/versions/<mc>/   各 MC 版本子项目（gradle.properties + 版本差异源码 overlay）
plugin/     Folia/Paper 插件（Messenger 接收、voices 音色路由、限速、命令）
assets/resourcepack/   乐器采样资源包（口琴/钢琴/吉他/贝斯/小提琴/唢呐/架子鼓，服务端资源包体系使用）
docs/       设计文档与测试清单
tools/      资源包构建与采样复取脚本（gen-sounds-json、build-pack、各乐器 rebuild）
```

## 安装（普通用户）

到 [Releases](../../releases) 页面下载：

| 文件 | 用途 | 放置位置 |
|---|---|---|
| `chord-fabric-<版本>.jar` | 演奏者客户端模组，**全版本合一**（1.21.11 / 26.2 / 26.3 一个文件，装模组时自动识别游戏版本） | `.minecraft/mods/` |
| `chord-fabric-mc<游戏版本>-<版本>.jar` | 同上，按游戏版本拆分的单版本包（与合一包二选一，同一游戏版本只装一个） | `.minecraft/mods/` |
| `plugin-<版本>.jar` | 服务端 Folia/Paper 插件 | 服务器 `plugins/` |
| `chord.zip` | 乐器采样资源包 | 服务端下发（见下文部署） |

## 构建（开发者）

需要 JDK 25。多版本接线参照 Mcedia：共享源码在 `fabric/src/`，各版本差异以**文件级 overlay** 放在 `fabric/versions/<mc>/src/`（同名路径版本目录优先）；26.x 走 `build.fabric.gradle`（免重映射），1.21.x 走 `build.fabric.remap.gradle`（mojmap + remapJar）。新增支持版本 = 加一个 `fabric/versions/<mc>/gradle.properties`（必要时补 overlay）并在根 `gradle.properties` 的 `supported_mc_versions` 里登记。

```bash
./gradlew build                     # 全部版本 + 插件
./gradlew :fabric-26.2:buildAndCollect :fabric-26.3:buildAndCollect :fabric-1.21.11:buildAndCollect
                                    # 模组产物归拢到 build/libs/<mc>/chord-fabric-mc<mc>-<版本>.jar
# 插件产物 plugin/build/libs/plugin-<版本>.jar（均含打包的 protocol）
python tools/gen-sounds-json.py   # instruments.yml → 资源包 sounds.json（需 pyyaml）
python tools/build-pack.py        # assets/resourcepack/ → chord.zip（改采样/instruments 后重新打包）
```

## 部署

1. **服务端**（Folia/Paper 26.2）：放入 `plugin-0.8.1.jar`，重启。生成 `plugins/Chord/{config.yml,instruments.yml}`。**`plugins/` 里只留一个 Chord jar**——新旧 jar 同名（`name: Chord`）时 Bukkit 只加载其一，旧 jar 留场会静默顶掉新功能。
2. **音色**：下发 Release 里的 `chord.zip`（约 4.2MB、711 采样事件；也可用 `tools/build-pack.py` 从 `assets/resourcepack/` 打包，**勿手工压缩整个 resourcepack 目录**——会混入开发留档与说明文件）或把 zip 里的 `pack.mcmeta + assets/` 并入服务器既有资源包，`instruments.yml` 的 `voices.sound` 指向 `chord:` 命名空间的采样事件（逐半音事件表由 `tools/gen-sounds-json.py` 从 instruments.yml 生成）。**联调期可把 voices 的 sound 全换成 `block.note_block.bit` 占位，无需资源包即可跑通全链路。**
3. **发放乐器**：`/chord give <harmonica|guitar|bass> [玩家]`（`chord.admin`）；钢琴/架子鼓是世界装置，用 CraftEngine 家具（world-bind）或 `/chord piano spawn [piano|drums]` 放置。
4. **演奏者**：安装 Chord 模组（放入 mods），手持乐器右键举起，按音级键演奏；蹲下或切换快捷栏退出。**蹲下+右键 = 原版交互放行**（手持乐器也能放置 CraftEngine 家具等，不触发举起）。
5. **聆听者**：什么都不用装。

## 操作（默认三角洲键位）

| 输入 | 效果 |
|---|---|
| 手持乐器右键 / 右键世界乐器装置（钢琴、架子鼓） | 举起，进入演奏态（**蹲下+右键除外**：放行原版交互，如放置 CE 家具） |
| `Z X C V B N M ,` | 简谱 1 2 3 4 5 6 7 高音1 |
| 按住鼠标左键 | 整排降八度（−12），与八度锁叠加可得低低音 |
| 按住鼠标右键 | 整排升八度（+12），与八度锁叠加可得高高音 |
| 按住鼠标中键 | 升半音（+1，可与八度叠加） |
| `G`（自动演奏键，演奏配置页可改） | 打开 **MIDI 中心**（0.7.15 起唯一入口，HUD 灰字常驻提示）：顶部选实时输入设备，下方选 `.minecraft/config/chord/midi/` 里的 .mid 直接播（服务端按自动演奏状态展示）；播放中再按 = 停止 |
| **实时 MIDI 键盘（0.7.12）** | 接上数字钢琴/MIDI 键盘**直接实时演奏**：88 键直通钢琴全域（MIDI 60 = C4），**真实力度 1~127 进力度层**，CC64 延音踏板与 `K` 踏板语义取或；自动连接唯一/上次的输入设备，MIDI 播放器界面顶部可循环切换/关闭设备（选择记入 `chord.json` 的 `midi_device`） |
| 演奏态内滚轮 | **力度调节**（40~127，100=基准）：上调强、下调弱；钢琴按力度路由柔音/常规/强音三层采样并缩放音量（HUD 显示 `力度 76 mp`）。滚轮不再切快捷栏 |
| `K`（踏板键，演奏配置页可改） | **钢琴**：按住 = 延音踏板（踩住期间松键的音延续，松开 K 掐掉所有余音）；**双击 K** 切换踏板模式——延音模式下松键默认延音、按住 K 变临时止音（长连奏不用一直踩着）；**吉他/鼓**：瞬时闷音掐掉全部声音 |
| `I` / `U`（高/低八度锁，演奏配置页可改） | 按一次锁定 +12/−12（HUD 显示 ♯/♭ 指示），再按解锁；锁定后**按住鼠标右/左键修饰 = 高高音/低低音**（独立按键，不占用鼠标修饰键的手势） |
| `J`（演奏配置页，原版按键设置可改） | 任意时刻打开**演奏配置页**——所有演奏输入的唯一配置入口，保存即生效 |
| 蹲下 | 退出演奏态（钢琴还可走远 8 格自动退出）；手持乐器时蹲下+右键 = 原版交互放行，不触发举起 |
| 切换快捷栏 | 退出演奏态（仅手持乐器形态） |

- 音符时长 = 按键时长（按住持续、松开即停；超 2.5~3.2 秒采样自然结束；鼓件是一次性采样，敲一下响完为止）
- **乐器一览**（0.4.0 起五种，`/chord instruments` 查看）：

| 乐器 | 形态 | 释放模式 | 音域（相对 C4） | 特性 |
|---|---|---|---|---|
| 守夜人口琴 | 手持（`GOAT_HORN`） | stop 松键即停 | [−24, +31] C2~G6 | 三角洲同款操作 |
| 三角钢琴 | 世界装置 | sustain 默认制音 | [−39, +48] **A0~C8 全 88 键** | K = 延音踏板（双击切延音模式）；vl2/vl3/vl4 三层力度采样；CE 家具 world-bind |
| 民谣吉他 | 手持（`WOODEN_HOE`） | decay | [−20, +28] E2~E6 | 拨弦自然衰减，K=闷音 |
| 电贝斯 | 手持（`IRON_HOE`） | decay | [−29, +12] E1~C5 | 低音域；默认音级键上段灰显 |
| 架子鼓 | 世界装置 | decay | [−12, +23] 12 鼓件 × 3 八度位 | 打击乐布局，HUD 显示鼓件名 |

**架子鼓键位**（12 半音 = 12 个鼓件，按八度周期重复——八度修饰键切换同一套鼓的三个副本；鼓件不随半音变调，HUD/直排音配置屏直接显示鼓件名）：

| 键 | 音级位 | 鼓件 | | 键 | 音级位 | 鼓件 |
|---|---|---|---|---|---|---|
| `Z` | 1 | 大鼓 | | `B` | 5 | 高嗵 |
| `X` | 2 | 小鼓 | | `N` | 6 | 低嗵 |
| `C` | 3 | 闭镲 | | `M` | 7 | 叮镲（ride） |
| `V` | 4 | 开镲 | | `,` | 高1 | 大鼓（副位） |
| 半音位 | 1♯ 2♯ 4♯ 6♯ 7♯ | 副鼓 / 鼓边 / 踩镲 / 中嗵 / 重镲 | | | | |

- 鼓件不受「同时持续音上限」约束（鼓滚奏需要连发），仍受每秒限速约束；踏板键闷音对鼓无效（一次性采样自然响完）
- 鼓上播放 MIDI 文件 = 按谱面音高触发对应鼓件（音高映射打法，适合做鼓点谱实验）
- **放置方式**：① CraftEngine 家具——按你平常的 CE 流程做一个架子鼓家具（换模型/贴图即可，**无需任何 Chord 专用配置**），把家具资源 id 填进 instruments.yml `drums` 段的 `world-bind.match-values`（示例值 `example:drum_kit`），`/chord reload` 后放置即生效，右键进演奏态；② 无 CE 时 `/chord piano spawn drums` 在准星处放置隐形交互装置，可自行用装饰方块搭建鼓外观
- **释放行为按乐器区分**（instruments.yml 的 `release-mode`）：口琴 `stop` 松键立刻停；吉他/贝斯/鼓 `decay` 松键自然衰减出音尾（K = 瞬时闷音）；**钢琴 `sustain`（0.5.0）还原真钢琴制音器——默认松键即制音**（约 150ms 落弦缓冲，期间被重击/踩踏板接住则取消），**按住 `K` = 延音踏板**：踩住期间松键的音全部延续，松开 `K` 掐掉所有「键已松开」的余音（按住中的键不受影响）；抬键后一瞬踩踏板仍能接住余音（踏板时机捕捉）。退出演奏态/断线一律硬止音
- **修饰键变调是宽限窗口制（150ms）**：修饰键只影响新按下的音——按住 Z 期间松开右键，音保持当前八度不回落，下次按键才是中音；只有按音后 150ms 内的修饰键按下/松开才视作「同时按的笔误」，按住中的音立即修正到新音高
- **演奏输入完全自管（0.7.0）**：物理键 + 半音 + 显示名 + 功能键（自动演奏/踏板/高八度锁/低八度锁）全部在演奏配置页（`J`）配置，**不再注册原版按键设置条目**（除打开配置页的 J 本身）。演奏态内按键在分发源头被拦截消费，天然不与其他 Mod 的绑定冲突。配置持久化在 `.minecraft/config/chord.json`（可手改，重启生效）
- **实时 MIDI 输入（0.7.12）**：`javax.sound.midi` 直连本机 MIDI IN（Java 内置 Sequencer/合成器自动排除，只列真实硬件口）——事件经 `Minecraft.execute` 转回客户端线程，与键盘输入同帧级延迟，之后与键盘共用同一 note-on/note-off/踏板管线（**线协议与服务端零改动**，旧服务端也能收）。出界音按 ±12 八度折叠入域（与文件转谱同规则，如 88 键最左端对口琴 [−24,+31] 域外会折叠）；开界面期间忽略新按键（同键盘语义）；拔线 5 秒内自动断开、重插自动回连，不用重启游戏
- 同刻多键 = 和弦（和弦上限按乐器 `max-polyphony`：旋律乐器 6、钢琴 10；sustain 乐器不设服务端上限——踏板堆叠是正常钢琴语义）
- 演奏时服务端在每个音符于演奏者头顶冒音符粒子（音高对应色相）；自动演奏的音一律金色并带白色光点，起止时头顶有粒子爆发——纯特效区分「非现场弹奏」，不发聊天广播
- **统一键槽（0.7.0 起）**：演奏配置页 24 个键槽（前 8 预填三角洲布局 `Z X C V B N M ,` → `1 2 3 4 5 6 7 高1`），每槽三件套：物理键（点按钮→按任意键捕获，Delete 清除，同键自动去重）、半音 −/+（按住 Shift = ±12 八度，范围 −39~+48 = 钢琴 A0~C8）、显示名。钢琴半音阶布局示例：从第 9 槽起填 `S=1♯、D=2♯…` 即得 `Z=1、S=1♯、X=2`；底部还有四个功能键位（自动演奏 G / 踏板 K / 高八度锁 I / 低八度锁 U）。旧 chord.json 的直排音会自动迁入第 9 槽起（原版控件设置里的旧键绑定需在页内重按一次）。

## 自动演奏（客户端 MIDI）

**乐谱书功能已整体移除（0.7.11 移除服务端导入/录制，0.7.15 移除客户端播放）**：旧乐谱书物品不再有任何 Chord 语义，就是普通成书；谱不再有持久化载体，自动演奏的**唯一来源是客户端 MIDI 文件**。新内容的主路径是实时演奏（0.7.12 起接 MIDI 键盘即插即弹）。

**播放**：把 `.mid` 放进 `.minecraft/config/chord/midi/`（自动创建）→ 进入演奏态后按 `G` 打开 MIDI 中心点击曲目即播（曲目多于可见行数时滚轮翻行）。转谱在客户端完成：双轨复制品去重（0.7.13，同一演奏写两轨的导出工件）、自动移调适配**当前乐器**音域、钢琴类乐器**瞬时封顶**（复音尖峰只截短最快结束的余音，保留全部声部与起振——0.7.13 前按全曲最大复音一刀切收敛单旋律，会把左手/内声部整只削掉；旋律乐器仍收敛单旋律）、出界八度折叠——音符数与整谱时长**无上限**；走自动演奏通道，**旁人看到的就是自动演奏状态**（金色粒子），任意手动键接管。力度：0.7.6 起每音力度 1~127 直传力度层（HUD 闪现力度范围）；踏板延音 0.7.9 起烘焙进音符时长。

自动演奏与手动共用同一音符协议：同样受服务端限速/和弦上限/`/chord mute` 约束，不是绕过手段。

## 命令与权限

| 命令 | 权限 | 说明 |
|---|---|---|
| `/chord instruments` | chord.use | 列出已配置乐器 |
| `/chord give <乐器> [玩家]` | chord.admin | 发放手持乐器物品（仅 entry: item 乐器） |
| `/chord piano inspect` | chord.piano | 查看身边交互实体的标识（PDC/名称）与 Chord 解析结果（仅适用于真实实体命中箱；CraftEngine 家具走事件接入，无需 inspect） |
| `/chord piano spawn [乐器]` | chord.admin | 在准星处放置世界乐器（自带 chord 标记的交互实体；多台世界乐器时需指定键如 `drums`） |
| `/chord piano bind [乐器]` | chord.piano | 兜底：把身边交互实体手动标记为世界乐器 |
| `/chord piano unbind` | chord.piano | 兜底：解除手动标记（保留实体，家具不受影响） |
| `/chord piano remove` | chord.admin | 删除 5 格内 spawn 生成的实体（CraftEngine 家具请用 unbind） |
| `/chord piano list` | chord.admin | 查看当前世界乐器演奏会话 |
| `/chord reload` | chord.admin | 重载配置并重新下发乐器表 |
| `/chord mute <玩家>` | chord.mute | 屏蔽/取消屏蔽某人的演奏 |

特效相关配置（`config.yml` 的 `effects` 段）：`note-particles` 音符粒子开关、`auto-particles` 自动演奏区分特效开关（金色音符 + 白色光点 + 起止爆发；无任何聊天广播）。

## 防滥用（服务端收口）

令牌桶限速（默认 40 音/秒、突发 24——键盘修饰键实时变调的 off/on 连发与实时 MIDI 键盘的快速乐句/刮奏都很密；**0.7.12 前生成的旧 config.yml 需手动把 `notes-per-second`/`burst` 改上来，否则快速乐句会被掐音**）、和弦上限按乐器 `max-polyphony`（instruments.yml，钢琴 10/旋律 6；sustain 乐器不设上限——踏板堆叠是正常钢琴语义）、decay/sustain 乐器并发余音上限（24，超出掐最旧）、音域校验、`require-held-instrument` 手持校验、聆听者个人屏蔽。客户端无权指定音色与音量。

## V1 已知边界 / 后续

- 第一人称口琴模型渲染未做（V1 演奏态靠 HUD 反馈，观感后补；原版旁观者看到手持山羊角）。
- 鼠标修饰键暂不可改键；举起动作固定右键。
- 按聆听者资源包状态的定向音色回退（V1.2）、多乐器（V1.2，纯配置扩展）；乐谱书自动演奏曾在 0.2.0 落地、0.7.15 全线移除。
- 乐器物品标记走 Bukkit PDC（存储于 `minecraft:custom_data` 的 `PublicBukkitValues → chord:instrument`），客户端按此路径识别——模组与服务端读的是同一份数据（乐谱书标记 `chord:score` 已随功能移除，旧书按普通成书处理）。

## 许可证与采样来源

本仓库代码以 [MIT](LICENSE) 协议开源。

乐器采样来自 **CC0 1.0（公共领域）** 音色库（唢呐除外——为本项目 `tools/suona-synth.py` 原创合成，无第三方素材），
可随本仓库自由再分发、无署名义务（出处仍惯例列明如下）。
每个乐器的来源目录、锁定 commit、逐锚点实测基频与 ffmpeg 处理管线，详见
[assets/resourcepack/README.md](assets/resourcepack/README.md)；`tools/` 内有各乐器的采样重建脚本。

| 乐器 | 来源 | 锁定版本 |
|---|---|---|
| 口琴、钢琴、架子鼓 | [VCSL — Versilian Community Sample Library](https://github.com/sgossner/VCSL)（Hohner Super 64 / Steinway B / 鼓组） | commit `c1ea7bc` |
| 民谣吉他 | [freepats/spanish-classical-guitar](https://github.com/freepats/spanish-classical-guitar) | commit `6f4eb1b` |
| 电贝斯 | [sfzinstruments/karoryfer.black-and-blue-basses](https://github.com/sfzinstruments/karoryfer.black-and-blue-basses) | commit `6e7d674` |
| 小提琴 | [VSCO 2 CE — Versilian Community Orchestra 2, Community Edition](https://www.versilian-studios.com/vsco.html/)（SViolinVib patch） | 分发包 `VSCO-2.1-XRNI-20170216` |
| 唢呐 | 本项目原创合成（`tools/suona-synth.py`，无第三方素材） | — |

以上四个采样来源的许可证均为 CC0 1.0 Universal；候选库调研与排除原因（许可证不符/素材不适配）也记录在资源包 README 中。

Minecraft 是 Mojang Studios 的商标，本项目与其无从属关系；CraftEngine 为第三方家具插件，为可选软依赖。
