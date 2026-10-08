package chord.protocol.score;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java MIDI（SMF）→ {@link Score} 转换器，不依赖 javax.sound.midi
 * （Paper/Folia 的裁剪运行时可能不含 java.desktop 模块，客户端同理）。
 *
 * <p>支持 format 0/1（多轨按绝对时间合并）、running status、速度事件（tempo map，
 * 时间精确烘焙为毫秒）、拍号事件。音色不进乐谱——由服务端乐器决定；
 * 力度随音符进谱（CHSC v2：1~127，100=基准；单音收敛时取和弦最高音的力度）。
 *
 * <p>转换规则（对齐 Delta-Force-Harmonica 工具口径，任何 MIDI 都能出可演奏的谱）：
 * <ul>
 *   <li>同 tick 同音高的重复音跨轨合并（双轨复制品导出工件；保留高力度与晚结束）</li>
 *   <li>首个音符之前的前导静默裁掉（startMs 归零）</li>
 *   <li>持续 &lt; 30ms 的音夹到 30ms（太短会丢在网络/调度缝隙里）</li>
 *   <li>同一音高的重叠音强制留 10ms 间隔（服务端活跃音表按半音建键，同音不能叠）</li>
 *   <li>复音超限的两种处理（判定用物理复音，踏板烘焙前）：
 *       旋律乐器（无踏板语义）整曲收敛单旋律——同刻和弦取最高音、长音在下一 onset 截断；
 *       多音乐器（钢琴 sustain）**瞬时封顶**——只在复音真超限的时刻截短最快结束的余音，
 *       其余时刻全部声部保留（0.7.13：旧版按全曲最大复音一刀切收敛，复音尖峰 0.7%
 *       的时间会连坐整首曲子，左手/内声部被整只削掉）</li>
 *   <li>移调：-12~+12 全量搜索「目标音域内音符数最多」的偏移（|t| 小者优先），也可指定</li>
 *   <li>兜底：移调后仍出界的音按 ±12 八度折叠入域（只动八度不动调内位置），
 *       折叠仍够不着的丢弃并计数——常规 MIDI 人声音域内不会触发</li>
 * </ul>
 */
public final class MidiConverter {

    /** 默认目标音域：与两件 V1 乐器（口琴/钢琴）一致的相对中心音半音域。 */
    public static final int DEFAULT_TARGET_MIN = -12;
    public static final int DEFAULT_TARGET_MAX = 25;

    /** 和弦保留上限：同时音超过该值自动收敛为单旋律（对齐服务端和弦上限默认值）。 */
    public static final int DEFAULT_MAX_POLYPHONY = 6;

    private static final int MIN_DURATION_MS = 30;
    private static final int RETRIGGER_GAP_MS = 10;
    /** 同刻成和弦判定窗口（毫秒）：窗口内取最高音。 */
    private static final int ONSET_GROUP_MS = 12;

    /** 转换结果与统计（给命令行反馈用）。 */
    public record Result(Score score, int chosenTranspose, boolean transposeOverridden,
                         int minSemitone, int maxSemitone, int inRangeNotes,
                         int maxSimultaneous, int tracksWithNotes, int tempoEventCount,
                         boolean melodyExtracted, int foldedNotes, int droppedNotes,
                         int pedalExtendedNotes, int dedupedNotes, int cappedNotes) {}

    private MidiConverter() {}

    public static Result convert(byte[] midi, String titleHint, Integer transposeOverride) throws IOException {
        return convert(midi, titleHint, transposeOverride, null);
    }

    /**
     * @param melodyMode null = 自动（同时音 &gt; {@link #DEFAULT_MAX_POLYPHONY} 时收敛为单旋律），
     *                   true = 强制单旋律，false = 强制保留和弦
     */
    public static Result convert(byte[] midi, String titleHint, Integer transposeOverride,
                                 Boolean melodyMode) throws IOException {
        return convert(midi, titleHint, transposeOverride, melodyMode,
                DEFAULT_TARGET_MIN, DEFAULT_TARGET_MAX);
    }

    /**
     * 流水线（对齐 Delta-Force-Harmonica 工具口径，任何 MIDI 都能出可演奏的谱）：
     * 解析 → 毫秒化 → 单音收敛（按需/强制）→ ±12 移调自动适配 → 出界音八度折叠兜底。
     *
     * @param midi              原始 MIDI 字节
     * @param titleHint         曲名（通常取文件名去扩展名）
     * @param transposeOverride 指定移调（null = 自动适配）
     * @param melodyMode        单旋律/和弦模式（null = 自动）
     * @param targetMin/Max     目标音域（相对中心音半音）
     */
    public static Result convert(byte[] midi, String titleHint, Integer transposeOverride,
                                 Boolean melodyMode, int targetMin, int targetMax) throws IOException {
        return convert(midi, titleHint, transposeOverride, melodyMode, targetMin, targetMax,
                DEFAULT_MAX_POLYPHONY, false);
    }

    /**
     * @param maxPolyphony 自动单音收敛阈值：melodyMode=null 时，原始最大复音超过
     *                     该值才收敛为单旋律。按乐器传——多音乐器（钢琴十指）传其
     *                     复音容量（如 10），旋律乐器传 {@link #DEFAULT_MAX_POLYPHONY}。
     *                     真弹录音（十指 + 踏板）复音轻松超 6，若一律按默认阈值收敛，
     *                     左手/和弦内声部会被整只削掉。
     */
    public static Result convert(byte[] midi, String titleHint, Integer transposeOverride,
                                 Boolean melodyMode, int targetMin, int targetMax,
                                 int maxPolyphony) throws IOException {
        return convert(midi, titleHint, transposeOverride, melodyMode, targetMin, targetMax,
                maxPolyphony, false);
    }

    /**
     * @param sustainPedal true = 解析 CC64 延音踏板并烘进音符时长：键松开时踏板踩着，
     *                     note-off 延至踏板抬起时刻（真钢琴制音器随踏板而非随键）。
     *                     只对该语义成立的乐器（钢琴 sustain 模式）开启；服务端零改动
     *                     （CHSC 本就只存「发声起止」）。旋律收敛判定仍用**物理**复音
     *                     （延长后的谱面复音可达数十，按它判会把踏板曲误收敛成单旋律）。
     */
    public static Result convert(byte[] midi, String titleHint, Integer transposeOverride,
                                 Boolean melodyMode, int targetMin, int targetMax,
                                 int maxPolyphony, boolean sustainPedal) throws IOException {
        Parsed parsed = parse(midi);
        int deduped = dedupNotes(parsed.notes); // 双轨复制品工件：同 tick 同音高合并

        String title = sanitizeTitle(titleHint);
        int bpmMilli = (int) Math.round(60_000_000_000.0 / parsed.firstUsPerQuarter);

        Materialized material = materialize(parsed, sustainPedal, melodyMode, maxPolyphony);
        List<Score.Note> notes = material.notes();
        int rawPoly = material.physicalPoly(); // 物理复音（踏板烘焙前）——复音判定用它
        boolean melodyExtracted = material.collapse();
        if (melodyExtracted) {
            notes = toMelody(notes);
        }
        int pedalExtended = material.pedalExtended();
        int capped = material.capped();

        int transpose = transposeOverride != null ? transposeOverride : autoTranspose(notes, targetMin, targetMax);
        if (transpose < Score.MIN_TRANSPOSE || transpose > Score.MAX_TRANSPOSE) {
            throw new IOException("移调超出 [" + Score.MIN_TRANSPOSE + "," + Score.MAX_TRANSPOSE + "]: " + transpose);
        }

        // 出界音兜底：按需 ±12 八度折叠入域（只动八度不动调内位置，至多 2 个八度）；
        // 折叠够不着（超 2 个八度）的丢弃并计数——常规 MIDI 人声音域内不会触发
        List<Score.Note> finalNotes = new ArrayList<>(notes.size());
        int folded = 0, dropped = 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Score.Note note : notes) {
            int semitone = note.pitch() + transpose;
            int delta = 0;
            if (semitone < targetMin) {
                delta = ((targetMin - semitone + 11) / 12) * 12; // 向上折叠到入域
            } else if (semitone > targetMax) {
                delta = -(((semitone - targetMax + 11) / 12) * 12); // 向下折叠到入域
            }
            int finalSemi = semitone + delta;
            if (finalSemi < targetMin || finalSemi > targetMax || Math.abs(delta) > 24) {
                dropped++;
                continue;
            }
            if (delta != 0) {
                folded++;
            }
            min = Math.min(min, finalSemi);
            max = Math.max(max, finalSemi);
            finalNotes.add(delta == 0 ? note
                    : new Score.Note(note.startMs(), note.pitch() + delta, note.durationMs(), note.velocity()));
        }
        if (finalNotes.isEmpty()) {
            throw new IOException("乐谱所有音符都超出折叠可达音域（目标 [" + targetMin + "," + targetMax + "]）");
        }
        Score score = new Score(title, bpmMilli, parsed.timeNum, parsed.timeDen, transpose, finalNotes);

        return new Result(score, transpose, transposeOverride != null, min, max, finalNotes.size(),
                melodyExtracted ? 1 : rawPoly,
                parsed.tracksWithNotes, parsed.tempoEvents.size(), melodyExtracted, folded, dropped,
                pedalExtended, deduped, capped);
    }

    // ---- 解析 ----

    private record RawNote(long startTick, long endTick, int midiPitch, int velocity, int channel) {}

    /** note-on 时刻在弦之音（开音表值）：开始 tick + 该次击键力度。 */
    private record OpenNote(long tick, int velocity) {}

    /** CC64 延音踏板事件（≥64 = 踩下）。 */
    private record PedalEvent(long tick, int channel, boolean down) {}

    /** 毫秒化的音符（绝对时间，未去前导），materialize 内部用。 */
    private record Timed(int startMs, int endMs, int pitch, int velocity, int channel) {}

    private static final class Parsed {
        int division = 480;
        int firstUsPerQuarter = 500_000;
        List<long[]> tempoEvents = new ArrayList<>(); // [tick, usPerQuarter]
        int timeNum = 4;
        int timeDen = 4;
        List<RawNote> notes = new ArrayList<>();
        List<PedalEvent> pedalEvents = new ArrayList<>();
        int tracksWithNotes = 0;
    }

    private static Parsed parse(byte[] midi) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(midi));
        if (!readAscii(in, 4).equals("MThd")) {
            throw new IOException("不是 MIDI 文件（缺 MThd）");
        }
        long headerLen = readU32(in);
        if (headerLen < 6) {
            throw new IOException("MThd 长度异常: " + headerLen);
        }
        int format = in.readUnsignedShort();
        int trackCount = in.readUnsignedShort();
        int division = in.readUnsignedShort();
        if ((division & 0x8000) != 0) {
            throw new IOException("SMPTE 时间格式的 MIDI 暂不支持");
        }
        if (division <= 0) {
            throw new IOException("division 非法: " + division);
        }
        in.skipBytes((int) headerLen - 6);
        if (format != 0 && format != 1) {
            throw new IOException("MIDI format " + format + " 暂不支持（仅 0/1）");
        }

        Parsed parsed = new Parsed();
        parsed.division = division;

        for (int t = 0; t < trackCount && in.available() > 0; t++) {
            String id = readAscii(in, 4);
            long len = readU32(in);
            byte[] track = new byte[(int) len];
            in.readFully(track);
            if (!id.equals("MTrk")) {
                continue; // 未知块跳过
            }
            if (parseTrack(track, parsed)) {
                parsed.tracksWithNotes++;
            }
        }
        if (parsed.notes.isEmpty()) {
            throw new IOException("MIDI 里没有任何音符");
        }
        parsed.tempoEvents.sort((a, b) -> Long.compare(a[0], b[0]));
        if (parsed.tempoEvents.isEmpty() || parsed.tempoEvents.get(0)[0] > 0) {
            parsed.tempoEvents.add(0, new long[]{0, 500_000}); // 默认 120 BPM
        }
        parsed.firstUsPerQuarter = (int) parsed.tempoEvents.get(0)[1];
        return parsed;
    }

    /** 解析单轨；返回该轨是否含音符。 */
    private static boolean parseTrack(byte[] track, Parsed parsed) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(track));
        long tick = 0;
        int running = 0;
        Map<Integer, OpenNote> open = new HashMap<>(); // key = ch<<8|note → 开音
        boolean hasNotes = false;

        while (in.available() > 0) {
            tick += readVlq(in);
            in.mark(1);
            int first = in.readUnsignedByte();
            int status;
            if (first >= 0x80) {
                status = first;
                if (status < 0xF0) {
                    running = status;
                } else {
                    running = 0; // 系统/元事件后 running status 失效
                }
            } else {
                if (running == 0) {
                    throw new IOException("非法 running status（无前导状态字节）");
                }
                status = running;
                in.reset(); // 回退数据字节，下方按完整事件参数读取
            }

            if (status == 0xFF) { // 元事件
                int type = in.readUnsignedByte();
                long len = readVlq(in);
                byte[] data = new byte[(int) len];
                in.readFully(data);
                if (type == 0x51 && data.length == 3) {
                    int usPerQuarter = (data[0] & 0xFF) << 16 | (data[1] & 0xFF) << 8 | (data[2] & 0xFF);
                    if (usPerQuarter > 0) {
                        parsed.tempoEvents.add(new long[]{tick, usPerQuarter});
                    }
                } else if (type == 0x58 && data.length >= 2 && parsed.timeNum == 4 && parsed.timeDen == 4) {
                    parsed.timeNum = Math.max(1, Math.min(32, data[0]));
                    int pow = Math.max(0, Math.min(5, data[1]));
                    parsed.timeDen = 1 << pow;
                }
            } else if (status == 0xF0 || status == 0xF7) { // 系统专属（含水印数据）
                long len = readVlq(in);
                in.skipBytes((int) len);
            } else {
                int type = status & 0xF0;
                int channel = status & 0x0F;
                switch (type) {
                    case 0x90 -> {
                        int note = in.readUnsignedByte();
                        int velocity = in.readUnsignedByte();
                        int key = (channel << 8) | note;
                        if (velocity == 0) {
                            closeNote(open, key, tick, note, parsed);
                        } else {
                            // 个别导出工具写 >127 的力度字节（实测见过 129），钳到标准上限
                            open.put(key, new OpenNote(tick, Math.min(velocity, 127)));
                            hasNotes = true;
                        }
                    }
                    case 0x80 -> {
                        int note = in.readUnsignedByte();
                        in.readUnsignedByte(); // release velocity
                        closeNote(open, (channel << 8) | note, tick, note, parsed);
                    }
                    case 0xB0 -> {
                        int controller = in.readUnsignedByte();
                        int value = in.readUnsignedByte();
                        if (controller == 64) {
                            parsed.pedalEvents.add(new PedalEvent(tick, channel, value >= 64));
                        }
                    }
                    case 0xA0, 0xE0 -> in.skipBytes(2);
                    case 0xC0, 0xD0 -> in.skipBytes(1);
                    default -> throw new IOException("非法事件状态: 0x" + Integer.toHexString(status));
                }
            }
        }
        return hasNotes;
    }

    private static void closeNote(Map<Integer, OpenNote> open, int key, long tick, int note, Parsed parsed) {
        OpenNote start = open.remove(key);
        if (start != null) {
            parsed.notes.add(new RawNote(start.tick(), Math.max(tick, start.tick() + 1), note,
                    start.velocity(), key >> 8));
        }
    }

    // ---- tick → 毫秒（tempo map 分段线性）----

    private static double tickToMs(Parsed parsed, long tick) {
        double us = 0;
        double segStartTick = 0;
        double usPerQuarter = 500_000;
        for (long[] tempo : parsed.tempoEvents) {
            if (tempo[0] >= tick) {
                break;
            }
            us += (tempo[0] - segStartTick) * usPerQuarter / parsed.division;
            segStartTick = tempo[0];
            usPerQuarter = tempo[1];
        }
        us += (tick - segStartTick) * usPerQuarter / parsed.division;
        return us / 1000.0;
    }

    // ---- RawNote → Score.Note（去重、去前导静默、时长下限、同音高去重叠、复音处理、踏板烘焙）----

    /**
     * 同 tick 同音高的重复音合并（跨轨/跨通道）。双轨复制品导出工件：同一演奏被写进
     * 两条轨/两个通道，每音成对——物理复音虚高一倍、总音数逼近 {@link Score} 上限，
     * 不合并时同音对还会被同音重叠规则拆成「30ms 桩音 + 推迟起振」。
     * 合并保留力度较高者的力度与通道、较晚的结束时刻。
     *
     * @return 被合并掉的重复音数
     */
    private static int dedupNotes(List<RawNote> notes) {
        if (notes.size() < 2) {
            return 0;
        }
        Map<Long, RawNote> merged = new HashMap<>(notes.size() * 2);
        int deduped = 0;
        for (RawNote n : notes) {
            long key = n.startTick() * 128L + n.midiPitch();
            RawNote best = merged.get(key);
            if (best == null) {
                merged.put(key, n);
            } else {
                deduped++;
                merged.put(key, new RawNote(n.startTick(),
                        Math.max(best.endTick(), n.endTick()), n.midiPitch(),
                        Math.max(best.velocity(), n.velocity()),
                        n.velocity() > best.velocity() ? n.channel() : best.channel()));
            }
        }
        if (deduped > 0) {
            notes.clear();
            notes.addAll(merged.values());
        }
        return deduped;
    }

    /** materialize 结果：音符（踏板烘焙后）+ 去前导基准 + 物理复音（烘焙前）+ 统计。 */
    private record Materialized(List<Score.Note> notes, double firstStartMs, int physicalPoly,
                                int pedalExtended, boolean collapse, int capped) {}

    private static Materialized materialize(Parsed parsed, boolean sustainPedal,
                                            Boolean melodyMode, int maxPolyphony) {
        List<Timed> timed = new ArrayList<>(parsed.notes.size());
        double firstStart = Double.MAX_VALUE;
        for (RawNote raw : parsed.notes) {
            double start = tickToMs(parsed, raw.startTick());
            double end = tickToMs(parsed, raw.endTick());
            firstStart = Math.min(firstStart, start);
            timed.add(new Timed((int) Math.round(start), (int) Math.round(end),
                    raw.midiPitch() - 60, raw.velocity(), raw.channel()));
        }

        List<Score.Note> physical = buildNotes(timed, firstStart);
        int physicalPoly = maxSimultaneous(physical);
        // 复音超限的处理方式（判定用物理复音——踏板烘焙前的按住音）：
        // 旋律乐器（无踏板语义）= 整曲收敛单旋律（口径不变）；
        // 多音乐器（钢琴 sustain，服务端本就无和弦上限）= 瞬时封顶——只在真超限的
        // 时刻截短余音，不再让 0.7% 时间的复音尖峰连坐整首曲子
        boolean collapse = melodyMode == null
                ? physicalPoly > maxPolyphony && !sustainPedal
                : melodyMode;
        int capped = 0;
        if (!collapse && physicalPoly > maxPolyphony) {
            capped = capMoments(timed, maxPolyphony);
        }

        int pedalExtended = 0;
        if (sustainPedal && !parsed.pedalEvents.isEmpty()) {
            Map<Integer, List<double[]>> pedal = pedalIntervalsMs(parsed);
            for (int i = 0; i < timed.size(); i++) {
                Timed t = timed.get(i);
                for (double[] iv : pedal.getOrDefault(t.channel(), List.of())) {
                    if (t.endMs() >= iv[0] && t.endMs() < iv[1]) {
                        timed.set(i, new Timed(t.startMs(), (int) Math.round(iv[1]),
                                t.pitch(), t.velocity(), t.channel()));
                        pedalExtended++;
                        break;
                    }
                }
            }
        }
        return new Materialized(buildNotes(timed, firstStart), firstStart, physicalPoly,
                pedalExtended, collapse, capped);
    }

    /**
     * 瞬时封顶：复音尖峰时刻把「最快结束的余音」截短到当前时刻（保留全部起振，
     * 被截音已发声的部分原样保留——硬件合成器的 voice-stealing 口径）；刚起振、
     * 剩余不足 {@link #MIN_DURATION_MS} 的可截量整颗丢弃（同刻成堆超过上限的和弦）。
     * 就地改写 {@code timed}（截短 end / 丢弃）。
     *
     * @return 被截短或丢弃的音符数
     */
    private static int capMoments(List<Timed> timed, int cap) {
        List<Timed> sorted = new ArrayList<>(timed);
        sorted.sort((a, b) -> a.startMs() != b.startMs() ? Integer.compare(a.startMs(), b.startMs())
                : Integer.compare(a.pitch(), b.pitch()));
        int[] end = new int[sorted.size()];
        boolean[] dropped = new boolean[sorted.size()];
        List<Integer> active = new ArrayList<>(); // 下标（按进入顺序；元素保证 end > 当前时刻）
        int capped = 0;
        for (int i = 0; i < sorted.size(); i++) {
            int start = sorted.get(i).startMs();
            int retireBefore = start; // 起振同刻的先收尾再进人（与新音同刻结束不算同时发声）
            active.removeIf(j -> end[j] <= retireBefore);
            while (active.size() >= cap) {
                int victim = -1;
                for (int j : active) {
                    if (victim < 0 || end[j] < end[victim]) {
                        victim = j;
                    }
                }
                if (start - sorted.get(victim).startMs() >= MIN_DURATION_MS) {
                    end[victim] = start; // 截短：已发声部分保留
                } else {
                    dropped[victim] = true; // 刚起振无可截：整颗丢弃
                }
                active.remove(Integer.valueOf(victim));
                capped++;
            }
            end[i] = sorted.get(i).endMs();
            active.add(i);
        }
        if (capped > 0) {
            timed.clear();
            for (int i = 0; i < sorted.size(); i++) {
                if (!dropped[i]) {
                    Timed t = sorted.get(i);
                    timed.add(new Timed(t.startMs(), end[i], t.pitch(), t.velocity(), t.channel()));
                }
            }
        }
        return capped;
    }

    /** Timed（绝对毫秒）→ Score.Note（相对首音；同音高重叠=截断旧音、保留新音原起点）。 */
    private static List<Score.Note> buildNotes(List<Timed> timedRaw, double firstStart) {
        List<Timed> sorted = new ArrayList<>(timedRaw);
        sorted.sort((a, b) -> a.startMs() != b.startMs() ? Integer.compare(a.startMs(), b.startMs())
                : Integer.compare(a.pitch(), b.pitch()));
        List<Score.Note> notes = new ArrayList<>(sorted.size());
        // 同音高 → 最近一颗音在 notes 里的下标。重叠时**截断旧音**（引擎同半音单声部，
        // 重击语义=制音器随新击弦落下）；只有极快重击（间隔 < 30+10ms 兜底）才顺延新音——
        // 0.7.9 曾反过来推迟新音起点，踏板把旧音延长到秒级后，同音重击会被整体推迟到
        // 踏板抬起之后，听感成「随机吞音/错位」
        Map<Integer, Integer> lastIndexByPitch = new HashMap<>();
        for (Timed t : sorted) {
            int start = t.startMs() - (int) firstStart;
            int end = Math.max(start + MIN_DURATION_MS, t.endMs() - (int) firstStart);
            Integer lastIndex = lastIndexByPitch.get(t.pitch());
            if (lastIndex != null) {
                Score.Note prev = notes.get(lastIndex);
                int prevEnd = prev.startMs() + prev.durationMs();
                if (prevEnd > start - RETRIGGER_GAP_MS) {
                    int truncatedEnd = Math.max(prev.startMs() + MIN_DURATION_MS,
                            start - RETRIGGER_GAP_MS);
                    notes.set(lastIndex, new Score.Note(prev.startMs(), prev.pitch(),
                            truncatedEnd - prev.startMs(), prev.velocity()));
                    if (start < truncatedEnd + RETRIGGER_GAP_MS) { // 极快重击兜底：新音顺延
                        start = truncatedEnd + RETRIGGER_GAP_MS;
                        end = Math.max(end, start + MIN_DURATION_MS);
                    }
                }
            }
            lastIndexByPitch.put(t.pitch(), notes.size());
            notes.add(new Score.Note(start, t.pitch(), end - start, t.velocity()));
        }
        return notes;
    }

    /** CC64 ≥64 = 踩下；通道 → [downMs, upMs] 区间（升序）。末尾仍踩着的踏板按 +1500ms 兜底收口。 */
    private static Map<Integer, List<double[]>> pedalIntervalsMs(Parsed parsed) {
        parsed.pedalEvents.sort((a, b) -> Long.compare(a.tick(), b.tick()));
        Map<Integer, List<double[]>> result = new HashMap<>();
        Map<Integer, Long> open = new HashMap<>();
        for (PedalEvent e : parsed.pedalEvents) {
            if (e.down()) {
                open.putIfAbsent(e.channel(), e.tick()); // 半踏抖动的重复踩下取首个
            } else {
                Long down = open.remove(e.channel());
                if (down != null) {
                    result.computeIfAbsent(e.channel(), k -> new ArrayList<>())
                            .add(new double[]{tickToMs(parsed, down), tickToMs(parsed, e.tick())});
                }
            }
        }
        for (Map.Entry<Integer, Long> e : open.entrySet()) {
            double down = tickToMs(parsed, e.getValue());
            result.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new double[]{down, down + 1500});
        }
        return result;
    }

    /**
     * 单音收敛（对齐 Delta-Force-Harmonica 工具口径：口琴单音乐器）：
     * 同刻（±{@value #ONSET_GROUP_MS}ms）成和弦取最高音，长音在下一 onset 截断（连奏不重叠）。
     */
    static List<Score.Note> toMelody(List<Score.Note> notes) {
        List<Score.Note> tops = new ArrayList<>(notes.size());
        int i = 0;
        while (i < notes.size()) {
            int groupStart = notes.get(i).startMs();
            Score.Note top = notes.get(i);
            int j = i;
            while (j < notes.size() && notes.get(j).startMs() - groupStart <= ONSET_GROUP_MS) {
                if (notes.get(j).pitch() > top.pitch()) {
                    top = notes.get(j);
                }
                j++;
            }
            tops.add(top);
            i = j;
        }
        List<Score.Note> result = new ArrayList<>(tops.size());
        for (int k = 0; k < tops.size(); k++) {
            Score.Note cur = tops.get(k);
            int originalEnd = cur.startMs() + cur.durationMs();
            int end = originalEnd;
            if (k + 1 < tops.size()) {
                int nextStart = tops.get(k + 1).startMs();
                if (nextStart >= originalEnd) {
                    end = originalEnd; // 本来就不重叠，保留原时长（休止符语义）
                } else {
                    end = Math.max(cur.startMs() + MIN_DURATION_MS, nextStart - RETRIGGER_GAP_MS);
                }
            }
            result.add(new Score.Note(cur.startMs(), cur.pitch(), end - cur.startMs(), cur.velocity()));
        }
        return result;
    }

    private static int autoTranspose(List<Score.Note> notes, int targetMin, int targetMax) {
        int bestT = 0;
        int bestCount = -1;
        for (int abs = 0; abs <= 12; abs++) {
            for (int t : (abs == 0 ? new int[]{0} : new int[]{abs, -abs})) {
                if (t < Score.MIN_TRANSPOSE || t > Score.MAX_TRANSPOSE) {
                    continue;
                }
                int count = 0;
                for (Score.Note n : notes) {
                    int semi = n.pitch() + t;
                    if (semi >= targetMin && semi <= targetMax) {
                        count++;
                    }
                }
                if (count > bestCount) {
                    bestCount = count;
                    bestT = t;
                }
            }
        }
        return bestT;
    }

    private static int maxSimultaneous(List<Score.Note> notes) {
        record Edge(int ms, int delta) {}
        List<Edge> edges = new ArrayList<>(notes.size() * 2);
        for (Score.Note n : notes) {
            edges.add(new Edge(n.startMs(), 1));
            edges.add(new Edge(n.startMs() + n.durationMs(), -1));
        }
        edges.sort((a, b) -> a.ms() != b.ms() ? Integer.compare(a.ms(), b.ms())
                : Integer.compare(a.delta(), b.delta())); // 同刻先 off 后 on
        int cur = 0, max = 0;
        for (Edge e : edges) {
            cur += e.delta();
            max = Math.max(max, cur);
        }
        return max;
    }

    private static String sanitizeTitle(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.toLowerCase(java.util.Locale.ROOT).endsWith(".mid")) {
            s = s.substring(0, s.length() - 4);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < Score.MAX_TITLE_CHARS; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '§') {
                continue;
            }
            sb.append(c);
        }
        if (sb.isEmpty()) {
            sb.append("未命名乐谱");
        }
        return sb.toString();
    }

    // ---- 底层读取 ----

    private static String readAscii(DataInputStream in, int len) throws IOException {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) in.readUnsignedByte());
        }
        return sb.toString();
    }

    private static long readU32(DataInputStream in) throws IOException {
        return (long) in.readInt() & 0xFFFFFFFFL;
    }

    private static long readVlq(DataInputStream in) throws IOException {
        long value = 0;
        int b;
        do {
            b = in.readUnsignedByte();
            value = (value << 7) | (b & 0x7F);
        } while ((b & 0x80) != 0);
        return value;
    }
}
