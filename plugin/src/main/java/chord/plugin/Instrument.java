package chord.plugin;

import org.bukkit.Material;
import org.bukkit.SoundCategory;

import java.util.List;
import java.util.Locale;

/**
 * 服务端乐器定义（instruments.yml 的一个条目）。
 *
 * <p>音色路由（voices）公式：pitch(s) = basePitch × 2^(s/12)，客户端再钳制到 [0.5, 2.0]。
 * 分层采样的约定：若某层采样实际录制的音比乐器中心音低 c 个半音，
 * 则 basePitch = 2^(c/12)（低八度采样 → 2.0；中心采样 → 1.0；高八度采样 → 0.5），
 * 这样每层只需在 ±12 半音内移动，永远处于合法变速能力内。
 */
public final class Instrument {

    /** 乐器进入方式：ITEM = 手持乐器物品右键；WORLD = 右键世界里带标记的交互实体（如钢琴）。 */
    public enum Entry { ITEM, WORLD }

    /**
     * 释放模式（instruments.yml 的 release-mode）：
     * <ul>
     *   <li>STOP（默认，口琴）——松键立刻止音；无踏板。</li>
     *   <li>DECAY（吉他/贝斯/鼓）——松键自然衰减出音尾；K 键 = 瞬时闷音（手掌闷音）。</li>
     *   <li>SUSTAIN（钢琴）——真钢琴制音器语义：默认松键短缓冲后制音；
     *       K 键 = 按住生效的延音踏板，踩住期间松键的音进余音表，松开踏板掐掉
     *       所有「键已松开」的余音（按住中的键不受影响）。</li>
     * </ul>
     */
    public enum ReleaseMode { STOP, DECAY, SUSTAIN }

    /** 声明式世界绑定：命中箱实体 PDC 字符串值/自定义名命中即视为本乐器（如 CraftEngine 家具 ID）。 */
    public final List<String> worldBindValues;
    /** 可选：按正则匹配标识。 */
    public final String worldBindRegex;

    public final int id;
    public final String key;
    public final String displayName;
    /** 底子物品；WORLD 进入方式的乐器可为 null（无物品形态）。 */
    public final Material baseItem;
    public final Entry entry;
    public final int minSemitone;
    public final int maxSemitone;
    public final double volume;
    public final SoundCategory category;
    public final List<Voice> voices;
    /** 可选：未接受资源包玩家的降级音色（V1 仅解析存储，V1.2 按聆听者定向下发）。 */
    public final List<Voice> fallbackVoices;
    /**
     * 力度层（instruments.yml 的 `layers:`）：按 note-on 力度路由到不同的 voices 表
     * （钢琴 vl2 柔音 / vl3 常规 / vl4 强音采样层）。空 = 无力度分层，
     * 力度只缩放音量、仍用 voices 路由。
     */
    public final List<VelocityLayer> layers;
    /** 释放模式（instruments.yml 的 release-mode），见 {@link ReleaseMode}。 */
    public final ReleaseMode releaseMode;
    /**
     * 复音容量（instruments.yml 的 max-polyphony，默认 6）：「按住中」的最大同时音符数。
     * 下发客户端（chord:poly）作为 MIDI 自动单音收敛阈值——钢琴十指给 10，
     * 超 6 复音的真弹录音不再被误收敛成单旋律。服务端和弦上限同用此值。
     */
    public final int maxPolyphony;
    /**
     * 制音尾音表（instruments.yml 的 release-voices，可空）：松键制音/松踏板时，
     * stopSound 硬切后叠加的同源收音采样——真钢琴制音器是快速衰减而非瞬间消声，
     * 纯硬切听感「戛然而止」。无该表的乐器保持纯硬切。
     */
    public final List<ReleaseVoice> releaseVoices;

    Instrument(int id, String key, String displayName, Material baseItem, Entry entry,
               int minSemitone, int maxSemitone, double volume, SoundCategory category,
               List<Voice> voices, List<Voice> fallbackVoices,
               List<String> worldBindValues, String worldBindRegex, ReleaseMode releaseMode,
               List<VelocityLayer> layers, int maxPolyphony, List<ReleaseVoice> releaseVoices) {
        this.id = id;
        this.key = key;
        this.displayName = displayName;
        this.baseItem = baseItem;
        this.entry = entry;
        this.worldBindValues = List.copyOf(worldBindValues);
        this.worldBindRegex = worldBindRegex;
        this.minSemitone = minSemitone;
        this.maxSemitone = maxSemitone;
        this.volume = volume;
        this.category = category;
        this.voices = List.copyOf(voices);
        this.fallbackVoices = List.copyOf(fallbackVoices);
        this.releaseMode = releaseMode;
        this.layers = List.copyOf(layers);
        this.maxPolyphony = maxPolyphony;
        this.releaseVoices = List.copyOf(releaseVoices);
    }

    /** 一档力度层：力度区间 → 该档的 voices 路由表。 */
    public record VelocityLayer(int minVelocity, int maxVelocity, List<Voice> voices) {

        public boolean contains(int velocity) {
            return velocity >= minVelocity && velocity <= maxVelocity;
        }
    }

    /**
     * 制音尾音行（instruments.yml 的 release-voices）：区间 + base-pitch + 多档同源
     * 尾音（0.7.16b）。「素材位置=保持时长」原则：gradeHeldMs[i] = 素材 i 在主采样中
     * 的源位置毫秒数，松键时按 gradeFor 的 prev/next 混合规则选档；再按主采样实测电平曲线
     * （levelMs/levelDb，200ms 步进 100ms 窗 RMS）插值出 gain = level(素材位置) −
     * level(保持时刻) 折算尾音 volume——电平/衰减斜率/音色三者都接上「松键那一刻
     * 的琴」。割裂感历史：三档固定阈值（<1.2s/<2.4s）与各锚点采样时长脱节，0.77s
     * 保持被给 0.5s 素材实测跳升 3dB（2026-10-08 视频）。素材天然电平不归一；
     * gradeHeldMs 缺省时回落三档阈值（旧配置兼容）。
     *
     * <p>0.8.1 起素材统一带 100ms/-6dB 压头（重切自 tools/piano-endings.py）——服务端
     * 松键先播尾音、主音延迟 2 tick 才 stopSound，相关信号叠加会把衔接处鼓 +2~3dB
     * 被听成「又弹一下」；压头把重叠期的和包络压平。{@code shortSound} 为短保持
     * 专用 0.3s 短尾（short-sound，断奏=制音器快速掐弦），松键保持时长低于
     * PlayMessageListener#SHORT_TAIL_HELD_MS 时改播它。
     */
    public record ReleaseVoice(int min, int max, double basePitch, List<String> sounds,
                               long[] gradeHeldMs, long[] levelMs, double[] levelDb,
                               String shortSound) {

        public ReleaseVoice(int min, int max, double basePitch, List<String> sounds) {
            this(min, max, basePitch, sounds, new long[0], new long[0], new double[0], null);
        }

        public boolean contains(int semitone) {
            return semitone >= min && semitone <= max;
        }

        /**
         * 按保持毫秒数选档（prev/next 混合规则）：next（素材位置略晚于保持时刻）
         * 的增益欠补偿（MC volume>1 无效，钳 1.0）≤2.0dB 时选 next——衰减斜率/音色
         * 贴合松键状态；否则 floor 到 prev——电平由 tailGain 精确补偿。纯 floor 在
         * 档位边缘会回退整档距（实测 460ms 保持被给 80ms 素材 → 斜率突变被听成
         * 「换了一段音」），纯就近在中点有 ≤3dB 跳升，此规则两头兼顾。
         */
        public int gradeFor(long heldMs) {
            if (gradeHeldMs == null || gradeHeldMs.length == 0) {
                return heldMs < 1200 ? 0 : heldMs < 2400 ? 1 : Math.min(2, sounds.size() - 1);
            }
            int prev = 0;
            boolean hasPrev = false;
            int next = -1;
            for (int i = 0; i < gradeHeldMs.length; i++) {
                if (gradeHeldMs[i] <= heldMs) {
                    prev = i;
                    hasPrev = true;
                } else {
                    next = i;
                    break;
                }
            }
            if (!hasPrev) {
                return 0;
            }
            if (next < 0) {
                return prev;
            }
            double overshootDb = (gradeHeldMs[next] - heldMs) / 1000.0 * Math.abs(localSlopeDbPerS(heldMs));
            return overshootDb <= 2.0 ? next : prev;
        }

        /** 电平曲线局部斜率（dB/s，负值）：t 与 t+200ms 的割线。 */
        private double localSlopeDbPerS(long t) {
            return (levelAt(t + 200) - levelAt(t)) / 0.2;
        }

        public String soundFor(long heldMs) {
            return sounds.get(gradeFor(heldMs));
        }

    /**
     * tail 模式配平：主音在 heldMs+delayMs 退出（对冲窗=电平曲线「再降 6dB」时长），
     * 尾音实例在该瞬间的内容位置 = 素材位置+delayMs——按电平曲线把两者拉平，
     * 台阶钉在交叉淡化地板 −3dB（Δ=0）。与旧版 tailGain（配平松键时刻）的区别：
     * 旧目标在对冲窗 100ms、引擎时延 30~125ms 方差下必失配（台阶 −2~−10dB 随机，
     * 2026-10-09 本地复现定案）；新目标随 delayMs 深入低电平区，L 方差影响按比例缩小。
     * gain ≤1（MC 音量 >1 无增益）：差为正=素材位更响，压回；曲线缺省=1。
     */
    public float tailExitGain(long heldMs, long delayMs, int grade, boolean shortTail) {
        if (levelMs == null || levelDb == null || levelMs.length < 2
                || levelMs.length != levelDb.length || heldMs <= 0) {
            return 1.0f;
        }
        long posMs;
        if (shortTail) {
            posMs = gradeHeldMs != null && gradeHeldMs.length > 0 ? gradeHeldMs[0] : 0;
        } else if (gradeHeldMs != null && gradeHeldMs.length > 0) {
            posMs = gradeHeldMs[Math.min(grade, gradeHeldMs.length - 1)];
        } else {
            posMs = 0;
        }
        double gainDb = levelAt(heldMs + delayMs) - levelAt(posMs + delayMs);
        return gainDb >= 0 ? 1.0f : (float) Math.pow(10.0, gainDb / 20.0);
    }

    /** 电平曲线线性插值；末端按最后两点斜率外推（钳 −24dB 防发散）。 */
    private double levelAt(long t) {
        int last = levelMs.length - 1;
        if (t <= levelMs[0]) {
            return levelDb[0];
        }
        if (t >= levelMs[last]) {
            long span = Math.max(1, levelMs[last] - levelMs[last - 1]);
            double slope = (levelDb[last] - levelDb[last - 1]) / span;
            double extra = slope * (t - levelMs[last]);
            return levelDb[last] + Math.max(-24.0, Math.min(0.0, extra));
        }
        int hi = 1;
        while (hi < levelMs.length && levelMs[hi] < t) {
            hi++;
        }
        double f = (double) (t - levelMs[hi - 1]) / Math.max(1, levelMs[hi] - levelMs[hi - 1]);
        return levelDb[hi - 1] + f * (levelDb[hi] - levelDb[hi - 1]);
    }

    /**
     * natural-delay 制音（config effects.piano-damper-mode，0.8.1 默认）的迟切时长：
     * 沿主采样电平曲线求「从松键时刻起再自然衰减 6dB」所需毫秒数，钳 120~600ms。
     * 松键不播第二份尾音采样——主音继续自然衰减到台阶落进不可闻域后无声 stopSound。
     * 依据（2026-10-09 本地复现）：两份独立引擎实例的交叠时序不可控（尾音可听起始
     * 时延 30~125ms+ 且冷事件更大），交叉淡化的暴露台阶 = 10log10(1+10^(Δ/10))，
     * 理论地板 −3dB 且要求退出瞬间等电平——实测基线 −2.7dB 已贴地板，任何素材/时序
     * 参数只能在地板之上随机波动。曲线缺省回落 350ms。
     */
    public long dampDelayMs(long heldMs) {
        if (levelMs == null || levelDb == null || levelMs.length < 2
                || levelMs.length != levelDb.length || heldMs <= 0) {
            return 350L;
        }
        double start = levelAt(heldMs);
        for (long d = 120; d <= 600; d += 20) {
            if (levelAt(heldMs + d) <= start - 6.0) {
                return d;
            }
        }
        return 600L;
    }

        /** [0.5, 2.0]，与 Voice.pitchFor 同公式（fixed 不适用——尾音恒随半音变调）。 */
        public float pitchFor(int semitone) {
            double pitch = basePitch * Math.pow(2.0, semitone / 12.0);
            if (pitch < 0.5) pitch = 0.5;
            if (pitch > 2.0) pitch = 2.0;
            return (float) pitch;
        }
    }

    public boolean inRange(int semitone) {
        return semitone >= minSemitone && semitone <= maxSemitone;
    }

    /**
     * 按半音 + 力度解析 voice：先找包含该力度的分层，在其 voices 内路由；
     * 无匹配层（或无分层）回落到顶层 voices。层内区间不重叠时首个命中即生效。
     */
    public Voice resolveVoice(int semitone, int velocity) {
        for (VelocityLayer layer : layers) {
            if (layer.contains(velocity)) {
                Voice voice = resolveIn(layer.voices(), semitone);
                if (voice != null) {
                    return voice;
                }
            }
        }
        return resolveIn(voices, semitone);
    }

    /** 首个命中区间的 voice 胜出（区间可重叠，交界的 1-2 个半音靠前层兜底）。 */
    public Voice resolve(int semitone) {
        return resolveIn(voices, semitone);
    }

    /** 制音尾音路由（首个命中区间）；未配置 release-voices 或未命中返回 null = 纯硬切。 */
    public ReleaseVoice releaseVoiceFor(int semitone) {
        for (ReleaseVoice rv : releaseVoices) {
            if (rv.contains(semitone)) {
                return rv;
            }
        }
        return null;
    }

    private static Voice resolveIn(List<Voice> table, int semitone) {
        for (Voice voice : table) {
            if (voice.contains(semitone)) {
                return voice;
            }
        }
        return null;
    }

    /** 力度 → 音量缩放：100 = 基准 ×1.0，下限防无声，上限允许 127 重音轻微过冲。 */
    public static float velocityScale(int velocity) {
        double v = velocity <= 0 ? 1 : Math.min(127, velocity);
        return (float) Math.max(0.1, Math.min(1.27, v / 100.0));
    }

    /** 松键后有余音语义（decay 或 sustain）——客户端据此维护「可能仍在响」的止音集合。 */
    public boolean releaseDecay() {
        return releaseMode != ReleaseMode.STOP;
    }

    /** K 键是按住生效的延音踏板（sustain 模式）而非瞬时闷音。 */
    public boolean sustainPedal() {
        return releaseMode == ReleaseMode.SUSTAIN;
    }

    /**
     * 音符实际播放的事件 id。
     *
     * <p>voices 的 sound 含 %d 模板时（资源包为每个半音注册独立事件，指向同一采样），
     * 每个音符有专属事件 id——note-off 时对该事件 stopSound 即可精确停音且不影响和弦中的其他音。
     * 不含 %d 的普通事件无 note-off（播完整条采样）。
     */
    public String eventFor(Voice voice, int semitone) {
        String sound = voice.sound();
        return sound.contains("%d")
                ? sound.replace("%d", Integer.toString(semitone))
                : sound;
    }

    /** 该半音命中的 voice 区间显示名（如鼓件名）；无 label 的 voice 返回 null。 */
    public String labelFor(int semitone) {
        Voice voice = resolve(semitone);
        return voice == null ? null : voice.label();
    }

    /** 该 voice 是否支持精确 note-off（逐音符事件模板）。 */
    public static boolean supportsNoteOff(Voice voice) {
        return voice.sound().contains("%d");
    }

    public boolean hasWorldBind() {
        return !worldBindValues.isEmpty() || worldBindRegex != null;
    }

    /** 标识（实体 PDC 字符串值/自定义名）是否命中本乐器的声明式绑定。 */
    public boolean matchesWorldBind(String marker) {
        if (marker == null || marker.isEmpty()) {
            return false;
        }
        if (worldBindValues.contains(marker)) {
            return true;
        }
        return worldBindRegex != null && marker.matches(worldBindRegex);
    }

    @Override
    public String toString() {
        return "Instrument{" + key + " [" + minSemitone + "," + maxSemitone + "] voices=" + voices.size() + "}";
    }

    /**
     * 一个音区的路由。
     *
     * <p>fixed = true（打击乐，如架子鼓）：pitchFor 恒等于 base-pitch，半音只用来选鼓件、
     * 不做变速——否则同一鼓件会被半音偏移变调成「低音军鼓/高音军鼓」。
     * label = 可选的区间显示名（下发到客户端 HUD/直排音配置屏，如「大鼓」「闭镲」；
     * 无 label 的乐器客户端回退到音级名 低1/高2♯…）。
     */
    public record Voice(int min, int max, String sound, double basePitch, boolean fixed, String label) {

        public Voice(int min, int max, String sound, double basePitch) {
            this(min, max, sound, basePitch, false, null);
        }

        public boolean contains(int semitone) {
            return semitone >= min && semitone <= max;
        }

        /** [0.5, 2.0]，与原版客户端钳制一致。 */
        public float pitchFor(int semitone) {
            double pitch = fixed ? basePitch : basePitch * Math.pow(2.0, semitone / 12.0);
            if (pitch < 0.5) pitch = 0.5;
            if (pitch > 2.0) pitch = 2.0;
            return (float) pitch;
        }

        public static SoundCategory parseCategory(String raw, SoundCategory def) {
            if (raw == null || raw.isBlank()) return def;
            try {
                return SoundCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return def;
            }
        }
    }
}
