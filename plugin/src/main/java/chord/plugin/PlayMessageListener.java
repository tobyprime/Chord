package chord.plugin;

import chord.protocol.AutoPayload;
import chord.protocol.ChordChannels;
import chord.protocol.NotePayload;
import chord.protocol.PedalPayload;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * chord:play / chord:auto 接收端：解码 → 校验（乐器存在/手持校验/音域/限速/持续音上限）
 * → voices 路由 → 广播 + 音符粒子特效。
 *
 * <p>音符生命周期协议（对应客户端按住/松开）：
 * <ul>
 *   <li>note-on（flags=0）：playSound 到范围内听众（手持乐器=演奏者位置、世界乐器=
 *       乐器锚点位置发声——琴在原地响，人走开音源不跟人），记录活跃音符（含听众快照），
 *       并在演奏者头顶冒一个按音高着色的音符粒子（自动演奏再加一缕白光以示区别）</li>
 *   <li>note-off（flags bit0）：对活跃音符逐一听众 stopSound——voices 配置了逐音符事件模板
 *       （sound 含 %d）时每个音符有独立事件 id，停音不影响和弦中的其他音</li>
 * </ul>
 *
 * <p>chord:auto（自动演奏开始/结束通告）：向周围玩家广播「非现场弹奏」提示，
 * <p>chord:auto（自动演奏开始/结束通告）：起止时在演奏者头顶爆发一簇粒子，
 * 期间每个音符用「金色音符 + 白色光点」与人工演奏（音高色相纯音符）区分——
 * 全靠特效变化提示，不发聊天/广播消息，听不到的玩家完全不被打扰。
 *
 * <p>所有校验与音量决策都在服务端，客户端无权指定音色/音量（设计文档 §8）。
 * Folia 说明：本回调在演奏者所在区域线程执行；对其他聆听者只调 playSound/stopSound
 * （定向网络发包）。粒子涉及区块跟踪者广播，统一经演奏者实体调度器
 * 投递到其区域线程再发送。活跃音符表带过期时间：客户端异常未发 note-off 时
 * 表项 10 秒后惰性清除（采样最长 3.2s，届时声音已自然结束）。
 */
public final class PlayMessageListener implements PluginMessageListener {

    /** 活跃音符表项过期时间：兜底客户端崩溃/掉线未发 note-off 的情况。 */
    private static final long NOTE_EXPIRY_MS = 10_000;

    /**
     * decay/sustain 乐器的并发余音上限：超出即掐最旧的（防 note-on 刷屏让余音无限堆叠）。
     * 0.5.0 起钢琴踏板期间也走余音表，放宽到 24（踩踏板的琶音/经过句会合法堆出一片余音）。
     */
    private static final int MAX_RINGING = 24;

    /**
     * 短保持阈值：松键时保持时长低于此值且尾音行配置了 short-sound，改播 0.3s
     * 短尾（切点同 t0、更陡淡出）——断奏物理 = 制音器快速掐弦，不该让 0.65s 的
     * 近全电平尾音盖过 0.1~0.3s 的短按（「再弹一下」感最重的区间，基线 2026-10-09）。
     */
    private static final long SHORT_TAIL_HELD_MS = 300;

    /**
     * 制音尾音增益。素材天然电平已与主音各衰减阶段对齐（按保持时长选档），
     * 0.9 的轻微下压只为掩盖拼接点的相位不连续。
     */
    private static final float RELEASE_TAIL_GAIN = 0.9f;

    /** 自动演奏音符的固定色相（金色，NOTE 粒子 0~24 色相轮）。 */
    private static final float AUTO_HUE = 4.0f;

    /**
     * 世界乐器演奏会话：锚点二选一——实体 UUID（/chord piano spawn 的真实交互实体，
     * 校验用其实时位置）或固定位置（CraftEngine 家具：命中箱是包级虚拟实体，
     * 服务端无实体，用家具位置做锚点）。音符校验一律要求玩家仍在锚点 8 格内。
     */
    public record WorldSession(java.util.UUID entity, Location anchor, int instrumentId, long openedAt) {

        public static WorldSession ofEntity(java.util.UUID entity, int instrumentId) {
            return new WorldSession(entity, null, instrumentId, System.currentTimeMillis());
        }

        public static WorldSession ofAnchor(Location anchor, int instrumentId) {
            return new WorldSession(null, anchor.clone(), instrumentId, System.currentTimeMillis());
        }
    }

    private static final class ActiveNote {
        final String event;
        final SoundCategory category;
        final List<UUID> audience;
        final long startedAt;
        final java.util.UUID performer;
        /**
         * 发声源位置：世界乐器=乐器锚点（主音与制音尾音都从琴的位置播，note-on 时定格）；
         * null=手持乐器——尾音回落到演奏者实时位置（人随身带琴，松键时人在哪尾音在哪）。
         */
        final Location source;
        /** 制音尾音定义（乐器未配置 release-voices 时为 null = 硬切即止）；档位在停音时按保持时长选。 */
        final Instrument.ReleaseVoice releaseVoice;
        final float releasePitch;
        final float releaseVolume;

        ActiveNote(String event, SoundCategory category, List<UUID> audience, long startedAt,
                   java.util.UUID performer, Location source, Instrument.ReleaseVoice releaseVoice,
                   float releasePitch, float releaseVolume) {
            this.event = event;
            this.category = category;
            this.audience = audience;
            this.startedAt = startedAt;
            this.performer = performer;
            this.source = source;
            this.releaseVoice = releaseVoice;
            this.releasePitch = releasePitch;
            this.releaseVolume = releaseVolume;
        }
    }

    /**
     * 制音缓冲中的音（sustain 钢琴松键后、未被踏板接住的最后 150ms）：
     * 到点对听众快照 stopSound；期间同半音重击（制音器先落弦再击弦）或踏板踩下（转余音）会取消。
     */
    private static final class PendingStop {
        final ActiveNote note;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task;
        /** direct 负延迟（交叠）的毫秒级停主音任务，TAIL_SCHEDULER 线程上执行。 */
        volatile java.util.concurrent.ScheduledFuture<?> msTask;
        /** 区域线程与 TAIL_SCHEDULER 线程并发读写。 */
        volatile boolean cancelled;

        PendingStop(ActiveNote note) {
            this.note = note;
        }
    }

    /** 取消一个待停任务（Folia tick 任务 + direct 负延迟的 ms 任务）。 */
    private static void cancelStop(PendingStop stop) {
        stop.cancelled = true;
        if (stop.task != null) {
            stop.task.cancel();
        }
        if (stop.msTask != null) {
            stop.msTask.cancel(false);
        }
    }

    private final ChordPlugin plugin;
    private final RateLimiter rateLimiter;
    /** 演奏者 → (半音 → 活跃音符)。 */
    private final Map<UUID, Map<Integer, ActiveNote>> activeNotes = new HashMap<>();
    /**
     * 演奏者 → (半音 → 余音)。decay 乐器（吉他/鼓）：松键后采样自然衰减；
     * sustain 乐器（钢琴）：踏板踩住期间松键的音。表项保留听众快照供硬止音/
     * 松开踏板定向 stopSound，到期惰性清除。
     */
    private final Map<UUID, Map<Integer, ActiveNote>> ringingNotes = new HashMap<>();
    /** 演奏者 → (半音 → 制音缓冲中的音)。仅 sustain 乐器（钢琴）松键后的 150ms 缓冲。 */
    private final Map<UUID, Map<Integer, PendingStop>> pendingStops = new HashMap<>();
    /** 演奏者 → 世界乐器会话（进入时记录，音符校验用）。 */
    private final Map<UUID, WorldSession> worldSessions = new HashMap<>();
    /** 演奏者 → 自动演奏曲名（chord:auto START 记录，STOP/掉线清除；重复 START 去重）。 */
    private final Map<UUID, String> autoSessions = new HashMap<>();
    private final boolean requireHeldInstrument;
    private final boolean includePerformer;
    private final boolean noteParticles;
    private final boolean autoParticles;
    /**
     * 钢琴（sustain 乐器）松键制音方式（config effects.piano-damper-mode）：
     * direct（当前试验默认，0.8.1）= 松键播尾音采样，延迟 piano-tail-delay-ticks 后
     *   stopSound 主音（0 tick = 同刻切）。拼接质量只取决于该延迟与尾音实例引擎
     *   可听时延 L 的相对关系：延迟=L 时贴合，延迟<L 出静默缝，延迟>L 出交叠鼓包
     *   （2026-10-09 本地复现 spec_l0.png / exp2 扫描）。
     * natural-delay = 不播尾音采样，主音自然衰减 dampDelayMs 后无声收掉——单实例无拼接。
     * tail-voices = 同 direct 但延迟独立配置（0.7.16j 对冲语义），台阶随时延方差波动。
     */
    private enum DamperMode { DIRECT, NATURAL_DELAY, TAIL_VOICES }

    private final DamperMode damperMode;
    /**
     * 尾音延迟（config effects.piano-tail-delay-ms，direct 模式生效，毫秒级）：
     * 松键**先 stopSound 主音，延迟 n 毫秒后再 playSound 尾音**——手调切口的时序。
     * 0=停完即放（同批次）；n>0 在主音停止与尾音起振之间留出可听静默（缝 = n + 尾音
     * 实例引擎时延 L）。ms 精度靠专用调度线程异步发包（playSound/stopSound 仅入
     * 连接队列，离线玩家异常由 try/catch 兜底）；客户端音频按帧启动，可听粒度仍是
     * 一帧（~50ms）。/chord reload 重建本监听器=热生效。
     */
    private final int tailDelayMs;
    /** direct 模式毫秒级尾音延迟的专用发送线程（仅入玩家连接队列，不碰世界状态）。 */
    private static final java.util.concurrent.ScheduledExecutorService TAIL_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Chord-Tail-Delay");
                t.setDaemon(true);
                return t;
            });
    /** 粒子发送只告警一次（避免每音符刷日志）。 */
    private boolean particlesWarned;

    public PlayMessageListener(ChordPlugin plugin) {
        this.plugin = plugin;
        this.rateLimiter = new RateLimiter(
                plugin.getConfig().getDouble("limits.notes-per-second", 20.0),
                plugin.getConfig().getDouble("limits.burst", 10.0));
        this.requireHeldInstrument = plugin.getConfig().getBoolean("require-held-instrument", true);
        this.includePerformer = plugin.getConfig().getBoolean("broadcast.include-performer", true);
        this.noteParticles = plugin.getConfig().getBoolean("effects.note-particles", true);
        this.autoParticles = plugin.getConfig().getBoolean("effects.auto-particles", true);
        this.damperMode = parseDamperMode(plugin.getConfig().getString("effects.piano-damper-mode", "direct"));
        this.tailDelayMs = Math.max(-500, Math.min(2000,
                plugin.getConfig().getInt("effects.piano-tail-delay-ms", 0)));
    }

    private static DamperMode parseDamperMode(String raw) {
        if (raw == null) {
            return DamperMode.DIRECT;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "natural-delay" -> DamperMode.NATURAL_DELAY;
            case "tail-voices" -> DamperMode.TAIL_VOICES;
            default -> DamperMode.DIRECT;
        };
    }


    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (ChordChannels.AUTO.equals(channel)) {
            handleAuto(player, message);
            return;
        }
        if (ChordChannels.PEDAL.equals(channel)) {
            handlePedal(player, message);
            return;
        }
        if (!ChordChannels.PLAY.equals(channel)) {
            return;
        }
        NotePayload note;
        try {
            note = NotePayload.fromBytes(message);
        } catch (Exception e) {
            return; // 畸形包直接丢弃
        }
        Instrument instrument = plugin.instruments().byId(note.instrumentId);
        if (instrument == null || !instrument.inRange(note.semitone)) {
            return;
        }
        boolean noteOff = (note.flags & NotePayload.FLAG_NOTE_OFF) != 0;
        boolean forceStop = (note.flags & NotePayload.FLAG_FORCE_STOP) != 0;
        boolean auto = (note.flags & NotePayload.FLAG_AUTO) != 0;
        boolean sustained = (note.flags & NotePayload.FLAG_SUSTAIN) != 0;
        if ((note.flags & ~(NotePayload.FLAG_NOTE_OFF | NotePayload.FLAG_AUTO
                | NotePayload.FLAG_FORCE_STOP | NotePayload.FLAG_SUSTAIN)) != 0) {
            return; // 未定义的 flag 位
        }

        Map<Integer, ActiveNote> mine = activeNotes.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        pruneExpired(mine);

        if (noteOff) {
            Map<Integer, ActiveNote> ring = ringingNotes.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
            pruneExpired(ring);
            ActiveNote stopped = mine.remove(note.semitone);
            if (forceStop) {
                // 硬止音（闷音键/退出演奏态）：活跃、余音、制音缓冲一律掐掉
                //（natural-delay 下闷音语义=立即静，不叠尾音；tail-voices 下叠尾音）
                if (stopped != null) {
                    broadcastStop(stopped, damperMode == DamperMode.TAIL_VOICES);
                }
                ActiveNote ringing = ring.remove(note.semitone);
                if (ringing != null) {
                    broadcastStop(ringing, damperMode == DamperMode.TAIL_VOICES);
                }
                stopPending(player, note.semitone);
            } else if (stopped != null) {
                switch (instrument.releaseMode) {
                    case DECAY -> {
                        // decay（吉他/鼓）：松键只是释放琴键，采样自然衰减——进余音表待硬止音
                        putRinging(player, ring, note.semitone, stopped);
                    }
                    case SUSTAIN -> {
                        // sustain（钢琴）：踏板踩住期间的松键 → 余音；否则 → 150ms 制音缓冲
                        if (sustained) {
                            putRinging(player, ring, note.semitone, stopped);
                        } else {
                            schedulePendingStop(player, note.semitone, stopped);
                        }
                    }
                    default -> broadcastStop(stopped);
                }
            }
            return;
        }

        // note-on
        Location anchorLoc = null;
        if (instrument.entry == Instrument.Entry.WORLD) {
            // 世界乐器（钢琴等）：必须已通过右键进入（有会话），且仍在锚点 8 格内。
            // 实体锚点用实时位置（家具被拆除=实体消失即失效）；
            // 位置锚点（CraftEngine 家具）用固定的家具位置。
            WorldSession session = worldSessions.get(player.getUniqueId());
            if (session == null) {
                return;
            }
            if (session.entity() != null) {
                org.bukkit.entity.Entity anchor = Bukkit.getEntity(session.entity());
                if (anchor == null || !anchor.getWorld().equals(player.getWorld())) {
                    return;
                }
                anchorLoc = anchor.getLocation();
            } else if (session.anchor() != null) {
                if (!session.anchor().getWorld().equals(player.getWorld())) {
                    return;
                }
                anchorLoc = session.anchor();
            }
            if (anchorLoc == null) {
                return;
            }
            double anchorRange = 8.0;
            if (anchorLoc.distanceSquared(player.getLocation()) > anchorRange * anchorRange) {
                return;
            }
        } else if (requireHeldInstrument && !plugin.holdingInstrument(player, instrument)) {
            return;
        }
        if (!rateLimiter.tryAcquire(player.getUniqueId())) {
            long drops = rateLimiter.drops(player.getUniqueId());
            if (drops == 1 || drops % 200 == 0) {
                plugin.getLogger().warning(player.getName() + " 音符限速触发（累计丢弃 " + drops + "）");
            }
            return;
        }
        if (instrument.releaseMode != Instrument.ReleaseMode.SUSTAIN
                && mine.size() >= instrument.maxPolyphony && !mine.containsKey(note.semitone)) {
            // 和弦上限按乐器复音容量；sustain 乐器（钢琴）不设此限——踏板延音会把
            // 松开的音合法地留在活跃表里堆叠（MIDI 踏板烘焙后的长 note-off），
            // 远超十指属正常钢琴语义，且活跃表按半音建键天然有界，滥用由限速器约束
            return;
        }
        // 力度：1~127（100=基准）；note-off 载荷的力度无意义，按 1 兜底防越层
        int velocity = Math.max(1, Math.min(127, note.velocity));
        Instrument.Voice voice = instrument.resolveVoice(note.semitone, velocity);
        if (voice == null) {
            return;
        }
        String event = instrument.eventFor(voice, note.semitone);
        float velScale = Instrument.velocityScale(velocity);

        // 同一半音重触发（两个不同键位组合按出同一音，或变调重击）：
        // stop/sustain 乐器先停旧音再启新音（真钢琴重击=制音器先落弦）；
        // decay 乐器旧音自然延续（像自然衰减中的重击）。制音缓冲中的同半音音必先取消，
        // 否则 150ms 后的迟到 stopSound 会把新音一起掐掉（逐半音事件同 id）。
        stopPending(player, note.semitone);
        ActiveNote old = mine.remove(note.semitone);
        if (old != null) {
            if (instrument.releaseMode == Instrument.ReleaseMode.DECAY) {
                Map<Integer, ActiveNote> ring = ringingNotes.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
                pruneExpired(ring);
                putRinging(player, ring, note.semitone, old);
            } else {
                broadcastStop(old);
            }
        }

        // 声源：世界乐器从乐器锚点发声（主音/尾音/听众距离判定都以琴为基准）；
        // 手持乐器从演奏者位置发声（人即乐器）。粒子永远在演奏者身上（spawnNoteParticles）。
        Location source = anchorLoc != null ? anchorLoc : player.getLocation();
        List<UUID> audience = broadcast(player, instrument, voice.pitchFor(note.semitone), event, velScale, source);
        if (Instrument.supportsNoteOff(voice)) {
            // 制音尾音随主音一并解析（同半音），停音时按保持时长选档、硬切后叠加
            Instrument.ReleaseVoice relVoice = instrument.releaseVoiceFor(note.semitone);
            mine.put(note.semitone, new ActiveNote(event, instrument.category, audience,
                    System.currentTimeMillis(), player.getUniqueId(),
                    anchorLoc,
                    relVoice,
                    relVoice == null ? 0f : relVoice.pitchFor(note.semitone),
                    relVoice == null ? 0f
                            : (float) Math.min(4.0f, instrument.volume * velScale * RELEASE_TAIL_GAIN)));
        }
        spawnNoteParticles(player, note.semitone, auto);
    }

    /** 玩家右键进入世界乐器时开演奏会话（真实交互实体锚点，音符校验用）。 */
    public void openWorldSession(UUID player, UUID entity, int instrumentId) {
        worldSessions.put(player, WorldSession.ofEntity(entity, instrumentId));
    }

    /** 玩家右键进入世界乐器时开演奏会话（固定位置锚点：CraftEngine 家具等无服务端实体的装置）。 */
    public void openWorldAnchorSession(UUID player, Location anchor, int instrumentId) {
        worldSessions.put(player, WorldSession.ofAnchor(anchor, instrumentId));
    }

    public Map<UUID, WorldSession> worldSessions() {
        return java.util.Collections.unmodifiableMap(worldSessions);
    }

    /** 进入包防重发记录：玩家 → (乐器id, 锚点, 上次发送时刻)。 */
    private final Map<UUID, Object[]> lastEnterSend = new HashMap<>();

    /**
     * 同一世界乐器 1 秒内的重复 ENTER 只发一次（演奏态内按住右键时，原版对
     * Interaction 类实体每 4 tick 重发交互包，会话/进入包被狂刷）。
     * 不影响真实重进——退出演奏态后再右键通常远隔 1 秒。
     */
    boolean enterRecentlySent(Player player, int instrumentId, Location anchor) {
        Object[] last = lastEnterSend.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        boolean same = last != null
                && (int) last[0] == instrumentId
                && last[1] instanceof Location prev
                && prev.getWorld().equals(anchor.getWorld())
                && prev.distanceSquared(anchor) < 0.25;
        if (same && now - (long) last[2] < 1000) {
            return true;
        }
        lastEnterSend.put(player.getUniqueId(), new Object[]{instrumentId, anchor.clone(), now});
        return false;
    }

    // ---- 自动演奏会话（chord:auto）----

    private void handleAuto(Player player, byte[] message) {
        AutoPayload payload;
        try {
            payload = AutoPayload.fromBytes(message);
        } catch (Exception e) {
            return;
        }
        if (payload.isStart()) {
            autoSessions.put(player.getUniqueId(), payload.title);
            autoFlourish(player, true); // 开始（含重启）：大簇爆发
        } else if (autoSessions.remove(player.getUniqueId()) != null) {
            autoFlourish(player, false); // 结束：小簇收尾
        }
    }

    // ---- 延音踏板（chord:pedal，仅 sustain 释放模式乐器如钢琴）----

    /**
     * 踩下：制音缓冲中的音被「接住」转余音（真钢琴的踏板时机捕捉——抬键后瞬间踩踏板
     * 仍然接住余振）。松开：所有「键已松开」的余音被制音；按住中的键不受影响
     * （它们不在余音表，制音器随琴键）。
     */
    private void handlePedal(Player player, byte[] message) {
        PedalPayload payload;
        try {
            payload = PedalPayload.fromBytes(message);
        } catch (Exception e) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Map<Integer, PendingStop> pending = pendingStops.get(playerId);
        Map<Integer, ActiveNote> ring = ringingNotes.computeIfAbsent(playerId, k -> new HashMap<>());
        pruneExpired(ring);
        if (payload.isDown()) {
            if (pending != null && !pending.isEmpty()) {
                for (Map.Entry<Integer, PendingStop> e : pending.entrySet()) {
                    PendingStop stop = e.getValue();
                    if (stop.cancelled) {
                        continue;
                    }
                    cancelStop(stop);
                    putRinging(player, ring, e.getKey(), stop.note);
                }
                pending.clear();
            }
        } else {
            for (Map.Entry<Integer, ActiveNote> e : ring.entrySet()) {
                if (damperMode == DamperMode.NATURAL_DELAY) {
                    // natural-delay：主音仍在响——不引入第二份声音，转入制音缓冲按曲线迟切。
                    // 踏板接住的音（键已松、主音未停）由此获得无缝延音→衰减过渡。
                    schedulePendingStop(player, e.getKey(), e.getValue());
                } else {
                    // direct / tail-voices：松踏板掐余音，叠尾音软化硬切
                    broadcastStop(e.getValue(), true);
                }
            }
            ring.clear();
        }
    }

    /**
     * sustain 松键 → 按制音模式分流（见 {@link DamperMode}）。
     *
     * <p>direct：同刻播尾音 + 立即停主音，无对冲窗、无待停任务。拼接缝 = 尾音实例的
     * 引擎可听时延 L（warm 事件小、冷首击大——本次试验的实测项）。
     *
     * <p>natural-delay：不引入第二个声音——主音自然衰减 dampDelayMs（电平曲线求
     * 「再降 6dB」时长，120~600ms）后无声 stopSound，衔接在构造上连续。
     *
     * <p>tail-voices：同 direct 的「尾音先行 + 延迟停主音」，延迟取同一旋钮
     * （piano-tail-delay-ticks）。heldMs 在松键时刻立即计算（延迟后计算会虚增缓冲
     * 时长）。同半音重击/闷音经 stopPending 取消本任务，不会误杀新音。
     */
    private void schedulePendingStop(Player player, int semitone, ActiveNote note) {
        long heldMs = System.currentTimeMillis() - note.startedAt;
        if (damperMode == DamperMode.DIRECT) {
            if (tailDelayMs >= 0) {
                // n ≥ 0：主音先停 → n ms 后放尾音（broadcastTail 内部毫秒级异步发送）
                stopMainSound(note);
                broadcastTail(player, note, heldMs);
                return;
            }
            // n < 0 = 交叠：尾音立即响，|n| ms 后才停主音。可被同半音重击/踏板接住/收尾取消
            //（重击掩蔽新起振；踏板接住时主音还在响=无缝延音）。ms 任务只发停音包，
            // 不碰 pendingStops 表（HashMap 仅区域线程读写），表项由区域线程路径清理。
            UUID playerId = player.getUniqueId();
            Map<Integer, PendingStop> pending = pendingStops.computeIfAbsent(playerId, k -> new HashMap<>());
            PendingStop stop = new PendingStop(note);
            pending.put(semitone, stop);
            broadcastTail(player, note, heldMs);
            stop.msTask = TAIL_SCHEDULER.schedule(() -> {
                if (stop.cancelled) {
                    return;
                }
                stopMainSound(note);
            }, -tailDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        UUID playerId = player.getUniqueId();
        Map<Integer, PendingStop> pending = pendingStops.computeIfAbsent(playerId, k -> new HashMap<>());
        PendingStop stop = new PendingStop(note);
        pending.put(semitone, stop);
        long ticks;
        if (damperMode == DamperMode.NATURAL_DELAY) {
            ticks = dampDelayTicks(note, heldMs);
        } else {
            // tail-voices（0.7.16j 对冲语义）：尾音先行 + 主音 2 tick 后停
            broadcastTail(player, note, heldMs);
            ticks = 2;
        }
        stop.task = player.getScheduler().runDelayed(plugin, task -> {
            if (stop.cancelled) {
                return;
            }
            Map<Integer, PendingStop> current = pendingStops.get(playerId);
            if (current != null && current.get(semitone) == stop) {
                current.remove(semitone);
            }
            stopMainSound(note);
        }, () -> {
            // 演奏者实体失效（退出/掉线）：forget() 会统一收尾
            stop.cancelled = true;
        }, ticks);
    }

    /** natural-delay 迟切 tick 数：电平曲线求「再降 6dB」毫秒数（120~600ms），换算 tick 钳 3~12。 */
    private long dampDelayTicks(ActiveNote note, long heldMs) {
        long ms = note.releaseVoice != null ? note.releaseVoice.dampDelayMs(heldMs) : 350L;
        return Math.max(3, Math.min(12, (ms + 49) / 50));
    }

    /** reload 回显用：当前制音模式名。 */
    public String damperModeName() {
        return damperMode.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** reload 回显用：当前尾音延迟毫秒数（direct 模式：主音停后 n ms 放尾音；0=停完即放）。 */
    public int tailDelayMs() {
        return tailDelayMs;
    }

    /** 立即取消并掐掉某半音的制音缓冲音（硬止音/同半音重击时调用）。 */
    private void stopPending(Player player, int semitone) {
        Map<Integer, PendingStop> pending = pendingStops.get(player.getUniqueId());
        if (pending == null) {
            return;
        }
        PendingStop stop = pending.remove(semitone);
        if (stop == null || stop.cancelled) {
            return;
        }
        cancelStop(stop);
        broadcastStop(stop.note);
    }

    /** 余音入表 + 超上限掐最旧。 */
    private void putRinging(Player player, Map<Integer, ActiveNote> ring, int semitone, ActiveNote note) {
        ring.put(semitone, note);
        while (ring.size() > MAX_RINGING) {
            Map.Entry<Integer, ActiveNote> oldest = null;
            for (Map.Entry<Integer, ActiveNote> e : ring.entrySet()) {
                if (oldest == null || e.getValue().startedAt < oldest.getValue().startedAt) {
                    oldest = e;
                }
            }
            if (oldest == null) {
                break;
            }
            broadcastStop(oldest.getValue());
            ring.remove(oldest.getKey());
        }
    }

    // ---- 粒子特效（经演奏者实体调度器投递到区域线程，Folia 安全）----

    /**
     * NOTE 粒子的数据类型随服务端构建而异：多数构建为 Float（色相下标可作 data 传入），
     * 但 shiroha 等 26.2 构建为 Void——传 Float 直接抛 IllegalArgumentException。
     * Void 构建改走 speed 参数携带色相（客户端按 speed×24 取色相下标）。
     */
    private static final boolean NOTE_FLOAT_DATA = Particle.NOTE.getDataType() == Float.class;

    private static void noteParticle(org.bukkit.World world, Location loc,
                                      int count, double ox, double oy, double oz, float hue24) {
        if (NOTE_FLOAT_DATA) {
            world.spawnParticle(Particle.NOTE, loc, count, ox, oy, oz, 0, hue24);
        } else {
            world.spawnParticle(Particle.NOTE, loc, count, ox, oy, oz, hue24 / 24f);
        }
    }

    /** 单个音符粒子：人工 = 音高色相 NOTE；自动 = 金色 NOTE + 一缕白色 END_ROD。 */
    private void spawnNoteParticles(Player performer, int semitone, boolean auto) {
        if (!noteParticles || (auto && !autoParticles)) {
            return;
        }
        Location loc = performer.getLocation().add(0, 2.0, 0);
        // 相对中心音 [-12,+25] 映射到 NOTE 粒子的 0~24 色相
        float hue = Math.max(0f, Math.min(24f, (semitone + 12) * 24f / 37f));
        scheduleParticles(performer, () -> {
            var world = performer.getWorld();
            noteParticle(world, loc, 1, 0.06, 0.04, 0.06, auto ? AUTO_HUE : hue);
            if (auto) {
                world.spawnParticle(Particle.END_ROD, loc, 1, 0.15, 0.1, 0.15, 0.0);
            }
        });
    }

    /** 自动演奏起止爆发：头顶一簇彩色音符 + 白色光点（开始大簇、结束小簇）。 */
    private void autoFlourish(Player performer, boolean big) {
        if (!autoParticles) {
            return;
        }
        Location loc = performer.getLocation().add(0, 2.2, 0);
        int notes = big ? 8 : 4;
        scheduleParticles(performer, () -> {
            var world = performer.getWorld();
            noteParticle(world, loc, notes, 0.45, 0.35, 0.45, 1.0f);
            noteParticle(world, loc, notes, 0.45, 0.35, 0.45, 9.0f);
            noteParticle(world, loc, notes, 0.45, 0.35, 0.45, 17.0f);
            world.spawnParticle(Particle.END_ROD, loc, big ? 4 : 2, 0.3, 0.25, 0.3, 0.0);
        });
    }

    /** 粒子涉及区块跟踪者广播：统一投到演奏者所在区域线程发送；失败只告警一次。 */
    private void scheduleParticles(Player performer, Runnable action) {
        performer.getScheduler().run(plugin, task -> {
            try {
                action.run();
            } catch (Exception e) {
                if (!particlesWarned) {
                    particlesWarned = true;
                    plugin.getLogger().log(Level.WARNING, "音符粒子发送失败（后续不再重复提示）: " + e, e);
                }
            }
        }, null);
    }

    /** 停掉该玩家所有活跃音、余音与制音缓冲（掉线/重载兜底，干净收场）并清理状态。 */
    public void forget(UUID playerId) {
        rateLimiter.forget(playerId);
        worldSessions.remove(playerId);
        autoSessions.remove(playerId);
        lastEnterSend.remove(playerId);
        Map<Integer, ActiveNote> mine = activeNotes.remove(playerId);
        if (mine != null) {
            for (ActiveNote note : mine.values()) {
                broadcastStop(note);
            }
        }
        Map<Integer, ActiveNote> ring = ringingNotes.remove(playerId);
        if (ring != null) {
            for (ActiveNote note : ring.values()) {
                broadcastStop(note);
            }
        }
        Map<Integer, PendingStop> pending = pendingStops.remove(playerId);
        if (pending != null) {
            for (PendingStop stop : pending.values()) {
                cancelStop(stop);
                broadcastStop(stop.note);
            }
        }
    }

    /** 惰性清除超期表项（采样早已自然结束，无需 stopSound）。 */
    private void pruneExpired(Map<Integer, ActiveNote> mine) {
        if (mine.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        mine.values().removeIf(note -> now - note.startedAt > NOTE_EXPIRY_MS);
    }

    private void broadcastStop(ActiveNote note) {
        broadcastStop(note, false);
    }

    /**
     * 停音。releaseTail = 乐器配置了 release-voices 时，硬切后对同一听众快照叠加
     * 制音尾音（真钢琴制音器是快速衰减而非瞬间消声）。尾音档位按该音的实际保持
     * 时长选（素材取自主采样 0.5/1.5/2.5s 位置、天然电平）——电平与音色接得上
     * 松键瞬间的琴音。仅用于「音乐性的制音」（松键/松踏板/闷音键）；重触发旧音、
     * 余音超限淘汰、掉线收尾不叠——前者会与新音起振打架，后两者人已离场。
     */
    private void broadcastStop(ActiveNote note, boolean releaseTail) {
        boolean tail = releaseTail && note.releaseVoice != null;
        Player performer = tail ? Bukkit.getPlayer(note.performer) : null;
        long heldMs = tail ? System.currentTimeMillis() - note.startedAt : 0;
        for (UUID uuid : note.audience) {
            Player listener = Bukkit.getPlayer(uuid);
            if (listener == null) {
                continue;
            }
            stopMainSound(note, listener);
            if (tail) {
                broadcastTail(note, listener, performer, heldMs);
            }
        }
    }

    /** 仅停主音（尾音时序对冲用：stopSound 即时生效，与尾音起播解耦）。 */
    private void stopMainSound(ActiveNote note) {
        for (UUID uuid : note.audience) {
            Player listener = Bukkit.getPlayer(uuid);
            if (listener != null) {
                stopMainSound(note, listener);
            }
        }
    }

    private void stopMainSound(ActiveNote note, Player listener) {
        try {
            listener.stopSound(note.event, note.category);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "stopSound 失败: " + note.event, e);
        }
    }

    /** 对全部听众播制音尾音（电平按 heldMs 曲线补偿；schedulePendingStop 立即路径用）。 */
    private void broadcastTail(Player performer, ActiveNote note, long heldMs) {
        for (UUID uuid : note.audience) {
            Player listener = Bukkit.getPlayer(uuid);
            if (listener != null) {
                broadcastTail(note, listener, performer, heldMs);
            }
        }
    }

    private void broadcastTail(ActiveNote note, Player listener, Player performer, long heldMs) {
        if (note.releaseVoice == null) {
            return;
        }
        try {
            // 尾音跟主音同源：世界乐器=琴的锚点（note.on 定格）；手持乐器=演奏者实时位置
            Location at = note.source != null ? note.source
                    : performer != null ? performer.getLocation() : listener.getLocation();
            // 短保持（断奏）走 0.3s 短尾：素材切点同 t0（gradeHeldMs[0]），增益按第 0 档曲线折算
            boolean shortTail = heldMs < SHORT_TAIL_HELD_MS && note.releaseVoice.shortSound() != null;
            int grade = note.releaseVoice.gradeFor(heldMs);
            // 响度补偿：素材取自「素材位置」的天然电平，按实测衰减曲线把
            // level(素材位置)−level(松键) 的差折成 volume（≤1，恒减益）。
            // delayMs=0 = 配平松键时刻（direct/短保持的语义；交叠方案才用退出时刻配平）
            float gain = note.releaseVoice.tailExitGain(heldMs, 0L, shortTail ? 0 : grade, shortTail);
            String relEvent = shortTail
                    ? note.releaseVoice.shortSound()
                    : note.releaseVoice.sounds().get(grade);
            float volume = note.releaseVolume * gain;
            if (damperMode == DamperMode.DIRECT && tailDelayMs > 0) {
                // direct 延迟：冻结参数后由专用线程毫秒级异步发包（playSound 仅入
                // 连接队列，不碰世界状态；离线玩家异常由 try/catch 兜底）。
                // 用户手调语义：主音已停，n ms 后尾音才起振（缝 = n + 引擎时延 L）。
                final Location atF = at.clone();
                final String evF = relEvent;
                final float volF = volume, pitF = note.releasePitch;
                final var catF = note.category;
                TAIL_SCHEDULER.schedule(() -> {
                    try {
                        listener.playSound(atF, evF, catF, volF, pitF);
                    } catch (Exception ex) {
                        // 玩家已离线等：静默丢弃（延迟期内的正常竞态）
                    }
                }, tailDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            }
            listener.playSound(at, relEvent, note.category, volume, note.releasePitch);
        } catch (Exception e) {
            // 尾音播放失败（事件越界/客户端包缺失等）必须可见：症状=松键硬切无声
            plugin.getLogger().log(Level.WARNING, "制音尾音播放失败 held=" + heldMs
                    + "ms rel=" + note.releaseVoice.sounds() + ": " + note.event, e);
        }
    }

    /**
     * 播放并返回实际收到的听众快照（note-off 时定向停音）。velScale = 力度音量缩放。
     * source = 发声源位置：手持乐器=演奏者位置；世界乐器=乐器锚点（可听范围也从琴算起，
     * 围观者围着钢琴听、离琴即弱）。停音按事件 id 定向、与位置无关。
     */
    private List<UUID> broadcast(Player performer, Instrument instrument, float pitch, String sound,
                                 float velScale, Location source) {
        double audible = 16.0 * instrument.volume;
        double audibleSq = audible * audible;
        float volume = (float) Math.min(4.0f, instrument.volume * velScale);
        List<UUID> audience = new ArrayList<>();
        for (Player listener : source.getWorld().getPlayers()) {
            if (!includePerformer && listener.equals(performer)) {
                continue;
            }
            if (plugin.mutes().isMuted(listener.getUniqueId(), performer.getUniqueId())) {
                continue;
            }
            if (listener.getLocation().distanceSquared(source) > audibleSq) {
                continue;
            }
            try {
                listener.playSound(source, sound, instrument.category, volume, pitch);
                audience.add(listener.getUniqueId());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "playSound 失败: " + sound, e);
                return audience;
            }
        }
        return audience;
    }
}
