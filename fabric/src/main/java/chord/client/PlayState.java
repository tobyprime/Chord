package chord.client;

import chord.protocol.EnterPayload;
import chord.protocol.InstrumentDef;
import chord.protocol.NotePayload;
import chord.protocol.PolyPayload;
import chord.protocol.SyncPayload;
import chord.protocol.score.Score;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;


import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 演奏状态机（客户端唯一状态源）。
 *
 * <p>按键模型：所有可演奏的键统一为 {@link KeySlot}（基础半音 + 鼠标修饰键偏移）——
 * 8 个默认音级键（简谱 1~7 高音1，三角洲布局，可在原版按键设置改物理键）+
 * 若干直排音键（chord.json 里直接配置每个键的半音，默认全部制空，物理键在控件设置绑定）。
 * 鼠标修饰键：按住左 −12、右 +12、中 +1，对两类键一律叠加。
 *
 * <p>音符生命周期 = 按键生命周期：按下 note-on，松开 note-off；修饰键实时变调。
 * 键盘事件由 KeyboardHandlerMixin 在分发源头拦截（不透传给原版与其他 Mod 的绑定）。
 */
public final class PlayState {

    /** 8 个默认音级键的基础半音：简谱 1 2 3 4 5 6 7 高音1。 */
    /** 半音 → 简谱名（十二半音全覆盖）。 */
    static final String[] SOLFEGE = {"1", "1♯", "2", "2♯", "3", "4", "4♯", "5", "5♯", "6", "6♯", "7"};
    static final long FLASH_MS = 900;

    /** 演奏态进入方式：手持乐器右键（ITEM）或服务端下发的世界乐器（WORLD，如钢琴交互实体）。 */
    public enum Mode { NONE, ITEM, WORLD }

    /** WORLD 模式：离乐器锚点超过此距离自动退出（服务端音符校验用同一值）。 */
    static final double WORLD_EXIT_DISTANCE = 8.0;

    /** 按住中但当前音高超出音域（修饰键实时变调后暂不可用）的哨兵值。 */
    static final int MUTED_SEMITONE = Integer.MIN_VALUE;
    /** 修饰键半音快照（MIN_VALUE = 待重算）：变化时对按住中的音实时变调。 */
    private static int lastShift = Integer.MIN_VALUE;

    /**
     * 变调宽限窗口（毫秒）：按音后此窗口内修饰键按下/松开，视作「本来就想同时按」，
     * 按住中的音立即修正到新音高；窗口外修饰键变化不再动已按住的音（保持当前八度，
     * 只影响下次按键）——真实乐器语义：已发的音不换调，换把位只影响后面的音。
     */
    static final long RETUNE_GRACE_MS = 150;

    /** 一个可演奏的按键槽。 */
    public static final class KeySlot {
        /** 绑定的物理键（GLFW 键码；UNKNOWN = 未绑定）。 */
        final int keyCode;
        /** 无修饰键时的基础半音（演奏配置页每槽自带）。 */
        final int base;
        /** 自定义显示名（可空 = 自动按简谱名/鼓件名生成）。 */
        final String customLabel;

        boolean held;
        int semitone;
        String label;
        /** 最近一次 note-on 时刻（变调宽限窗口判定）。 */
        long pressedAt;

        KeySlot(int keyCode, int base, String customLabel) {
            this.keyCode = keyCode;
            this.base = base;
            this.customLabel = customLabel;
        }
    }

    /** 全部演奏键槽（ChordClient 启动/配置页保存时注入；键码匹配，与原版按键设置无关）。 */
    static KeySlot[] slots = new KeySlot[0];

    private static SyncPayload sync;
    /** 服务端下发的复音容量表（chord:poly，乐器 id → MIDI 单音收敛阈值）；旧服务端不发 = 空表走启发式。 */
    private static final Map<Integer, Integer> polyByInstrument = new HashMap<>();
    private static boolean playing;
    private static Mode mode = Mode.NONE;
    private static InstrumentDef current;
    private static int enterSlot = -1;
    private static double anchorX;
    private static double anchorY;
    private static double anchorZ;

    // ---- 自动演奏（客户端 MIDI 文件）----

    /** 当前自动演奏的谱（MIDI 文件转换产物；0.7.15 起唯一来源，null = 未装载）。 */
    private static Score score;
    private static boolean autoPlaying;
    /** 播放代次：stop/start 递增，旧调度任务凭此失效。 */
    private static int autoRunId;
    private static long autoStartNanos;
    /** 已发 note-on 未发 note-off 的半音（stop 时补发 note-off）。 */
    private static final Set<Integer> autoActive = new HashSet<>();
    private static final List<ScheduledFuture<?>> autoFutures = new ArrayList<>();
    private static ScheduledExecutorService autoExec;
    /**
     * decay/sustain 乐器当前仍可听得见的半音集合（含按住与已松开的余音），
     * 闷音键/退出演奏态时对这些半音发硬止音。
     */
    private static final Set<Integer> sounding = new HashSet<>();
    /** 延音踏板键物理按住中（sustain 乐器）。 */
    private static boolean pedalKeyDown;
    /**
     * 踏板模式锁（双击 K 切换）：false = 止音模式（默认，松键制音、按住 K 延音）；
     * true = 延音模式（松键默认延音、按住 K 临时变止音——踏板语义整体反转）。
     */
    private static boolean pedalLatched;
    private static long lastPedalPressAt;
    /** 实时 MIDI（MidiInput）：按住中（MIDI 音高 → 本次发声的半音）。 */
    private static final Map<Integer, Integer> midiHeld = new HashMap<>();
    /** 实时 MIDI 延音踏板（CC64 ≥ 64 踩下）；与 K 键踏板语义取或。 */
    private static boolean midiPedalDown;
    /** 当前力度档（40~127，100=基准）；普通键盘由滚轮调节，磁轴等模拟输入源走同一状态。 */
    private static int velocityLevel = 100;
    /** 八度锁（双击左/右键切换）：−12 低八度锁 / 0 无 / +12 高八度锁；与鼠标修饰键叠加。 */
    private static int octaveLatch;
    /** 踏板双击判定窗口（ms，仅踏板键自身的模式切换用）。 */
    private static final long DOUBLE_TAP_MS = 400;
    /** HUD 进度用：下一个音符下标 / 运行期跳过数。 */
    private static int autoNoteIndex;
    private static int autoSkipped;

    /** HUD 闪现队列：最近按下的音名，随时间淡出。 */
    private static final ArrayDeque<Flash> flashes = new ArrayDeque<>();

    static final class Flash {
        final String text;
        final boolean warn;
        final long at = System.currentTimeMillis();

        Flash(String text, boolean warn) {
            this.text = text;
            this.warn = warn;
        }
    }

    private PlayState() {}

    /** 注入演奏键槽（配置页/chord.json 的槽位列表；键名解析失败 = 未绑定）。 */
    static void initSlots(java.util.List<ChordConfig.Slot> configured) {
        java.util.List<KeySlot> list = new java.util.ArrayList<>();
        for (ChordConfig.Slot slot : configured) {
            int keyCode = InputConstants.UNKNOWN.getValue();
            if (slot.key != null && !slot.key.isBlank()) {
                try {
                    keyCode = InputConstants.getKey(slot.key).getValue();
                } catch (Exception ignored) {
                    // 非法键名按未绑定处理
                }
            }
            list.add(new KeySlot(keyCode, slot.semitone, slot.label));
        }
        slots = list.toArray(new KeySlot[0]);
    }

    // 默认键码一律按键名运行时解析：26.3 起键码整体重编号，常量内联会跨版本错位
    /** 功能键（演奏配置页可改）。自动演奏默认 G。 */
    private static int autoKeyCode = keyCode("key.keyboard.g");
    /** 踏板键默认 K（双击切延音模式）。 */
    private static int pedalKeyCode = keyCode("key.keyboard.k");
    /** 高/低八度锁（独立按键，按一次锁定 ±12，默认 I / U）。 */
    private static int latchHighKeyCode = keyCode("key.keyboard.i");
    private static int latchLowKeyCode = keyCode("key.keyboard.u");

    /** 键名 → 键码（运行时解析，见 ChordClient.keyCodeOf 注释）。 */
    private static int keyCode(String name) {
        return InputConstants.getKey(name).getValue();
    }

    static void setAutoKey(int keyCode) {
        autoKeyCode = keyCode;
    }

    static void setPedalKey(int keyCode) {
        pedalKeyCode = keyCode;
    }

    static void setLatchKeys(int highKeyCode, int lowKeyCode) {
        latchHighKeyCode = highKeyCode;
        latchLowKeyCode = lowKeyCode;
    }

    // ---- 握手 ----

    /** 换服/断线：清空上一段连接的握手数据与演奏态（新服务器会重新下发 sync）。 */
    static void resetConnection() {
        exit();
        sync = null;
        polyByInstrument.clear();
    }

    /** 服务端下发的复音容量表（chord:poly，随 sync 一并到达）：MIDI 单音收敛阈值按乐器生效。 */
    static void onPoly(PolyPayload payload) {
        polyByInstrument.clear();
        polyByInstrument.putAll(payload.maxPolyphonyByInstrument);
    }

    static void onSync(SyncPayload payload) {
        sync = payload;
        if (playing && current != null) {
            InstrumentDef reloaded = payload.byKey(current.key);
            if (reloaded == null) {
                exit();
            } else {
                current = reloaded;
            }
        }
    }

    public static boolean serverSupported() {
        return sync != null;
    }

    // ---- 演奏态 ----

    public static boolean isPlaying() {
        return playing;
    }

    public static InstrumentDef currentInstrument() {
        return current;
    }

    public static boolean isWorldMode() {
        return mode == Mode.WORLD;
    }

    /** 手持乐器物品右键时尝试进入演奏态（ITEM 模式）。 */
    static boolean tryEnter(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (playing || player == null || mc.gui.screen() != null) {
            return false;
        }
        String key = instrumentKey(player.getMainHandItem());
        if (key == null || sync == null) {
            return false;
        }
        InstrumentDef def = sync.byKey(key);
        if (def == null) {
            return false;
        }
        current = def;
        mode = Mode.ITEM;
        enterSlot = player.getInventory().getSelectedSlot();
        playing = true;
        resetTransientState();
        score = null;
        return true;
    }

    /** 服务端下发的世界乐器进入/退出（chord:enter，如钢琴交互实体）。 */
    static void onEnterRemote(EnterPayload payload) {
        if (payload.isExit()) {
            if (mode == Mode.WORLD) {
                exit();
            }
            return;
        }
        if (sync == null) {
            return;
        }
        InstrumentDef def = sync.byId(payload.instrumentId);
        if (def == null) {
            return;
        }
        if (playing) {
            // 同一台装置的重复 ENTER：演奏态内按住右键（八度修饰键）时准星还在琴上，
            // 原版对 Interaction 类实体每 4 tick 重发交互包 → 服务端重复下发 ENTER。
            // 忽略之——否则 exit()+重进会硬止音掐掉按住中的音并重置踏板/力度/八度锁。
            if (mode == Mode.WORLD && current != null && current.id == payload.instrumentId) {
                double dx = anchorX - payload.x;
                double dy = anchorY - payload.y;
                double dz = anchorZ - payload.z;
                if (dx * dx + dy * dy + dz * dz < 4.0) {
                    return;
                }
            }
            exit(); // 切换乐器：先释放当前音符
        }
        current = def;
        mode = Mode.WORLD;
        anchorX = payload.x;
        anchorY = payload.y;
        anchorZ = payload.z;
        enterSlot = -1;
        playing = true;
        resetTransientState();
        score = null;
    }

    private static void resetTransientState() {
        flashes.clear();
        sounding.clear();
        pedalKeyDown = false;
        pedalLatched = false;
        midiHeld.clear();
        midiPedalDown = false;
        velocityLevel = 100;
        octaveLatch = 0;
        for (KeySlot slot : slots) {
            slot.held = false;
            slot.label = null;
        }
        lastShift = Integer.MIN_VALUE;
    }

    static void exit() {
        stopAuto(false); // 先停自动演奏（内部补发 note-off）
        releaseAllHeld(true); // 退出演奏态：硬止音，干净收场（余音/制音缓冲也掐掉）
        playing = false;
        mode = Mode.NONE;
        current = null;
        enterSlot = -1;
        pedalKeyDown = false;
        pedalLatched = false;
        midiPedalDown = false;
        velocityLevel = 100;
        octaveLatch = 0;
    }

    /** 每 tick 校验退出条件；界面打开时静音按住的音（不退出演奏态）。 */
    static void tick(Minecraft mc) {
        if (!playing) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || player.isRemoved()) {
            exit();
            return;
        }
        // 通用退出条件：蹲下
        if (player.isShiftKeyDown()) {
            exit();
            return;
        }
        if (mode == Mode.ITEM) {
            // 手持乐器形态：切快捷栏 / 手中不再是乐器 → 退出（设计文档 §5）
            if (player.getInventory().getSelectedSlot() != enterSlot
                    || instrumentKey(player.getMainHandItem()) == null) {
                exit();
                return;
            }
        } else if (mode == Mode.WORLD) {
            // 世界乐器形态：离乐器锚点太远 → 退出
            if (player.distanceToSqr(anchorX, anchorY, anchorZ) > WORLD_EXIT_DISTANCE * WORLD_EXIT_DISTANCE) {
                exit();
                return;
            }
        }
        if (mc.gui.screen() != null) {
            // 聊天/背包等界面打开：松开所有按住的音，键盘事件由界面接管（天然不触发音符）
            // 普通 note-off（钢琴余音自然延续）；自动演奏不受影响——它走独立调度
            releaseAllHeld(false);
            return;
        }
        // 修饰键变调：仅按音后 RETUNE_GRACE_MS 内的修饰键变化会修正按住中的音
        //（视作同时按下的笔误修正）；宽限外修饰键/八度锁只影响下一次按键
        //（八度锁是独立按键事件，切换后由这里把 shift 变化传导给按住中的音）
        int shift = effectiveShift(mc);
        if (shift != lastShift) {
            if (lastShift != Integer.MIN_VALUE) {
                retuneHeldNotes(shift);
            }
            lastShift = shift;
        }
    }

    /** 滚轮力度调节（演奏态内消费，避免滚轮切快捷栏导致退出）：上调强、下调弱，40~127。 */
    public static boolean handleScroll(double yoffset) {
        if (!playing || current == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) {
            return false;
        }
        int delta = yoffset > 0 ? 6 : -6;
        velocityLevel = Math.max(40, Math.min(127, velocityLevel + delta));
        flash("力度 " + velocityLevel + " · " + dynLabel(velocityLevel), false);
        return true;
    }

    /** 力度 → 音乐术语缩写（HUD/闪现用）。 */
    static String dynLabel(int velocity) {
        if (velocity < 50) return "pp";
        if (velocity < 68) return "p";
        if (velocity < 88) return "mp";
        if (velocity < 110) return "mf";
        if (velocity < 122) return "f";
        return "ff";
    }

    /** 当前力度档（HUD 指示用；100 = 基准不显示）。 */
    public static int velocityLevel() {
        return velocityLevel;
    }

    /** 八度锁（−12/0/+12，HUD 指示用）。 */
    public static int octaveLatch() {
        return octaveLatch;
    }

    /** 踏板键短名（HUD 提示用；直接读配置键名，演奏配置页可改键不能硬编码 K）。 */
    public static String pedalKeyName() {
        String name = ChordClient.config != null && ChordClient.config.pedalKey != null
                ? ChordClient.config.pedalKey : "key.keyboard.k";
        return name.startsWith("key.keyboard.") ? name.substring("key.keyboard.".length()).toUpperCase() : name;
    }

    // ---- 键盘事件（KeyboardHandlerMixin 分发源头拦截） ----

    /**
     * 演奏态内的键盘事件路由（音级键 + 直排音统一处理）。
     *
     * @return true = 该键是我们的演奏键且已被消费（不透传给原版与其他 Mod 的按键绑定）
     */
    public static boolean handleKeyEvent(int action, KeyEvent event) {
        if (!playing || slots.length == 0) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null) {
            return false;
        }
        int pressedKey = event.key();
        // 自动演奏键：按一次开始/结束（松开也消费，避免漏给其他绑定）
        if (pressedKey == autoKeyCode) {
            if (action == InputConstants.PRESS) {
                toggleAutoPlay(mc);
            }
            return true;
        }
        // 高/低八度锁：独立按键（默认 I / U，演奏配置页可改），按一次锁定/再按解锁；
        // 锁定后按住鼠标修饰键 = 叠加成高高/低低音
        if (action == InputConstants.PRESS && (pressedKey == latchHighKeyCode || pressedKey == latchLowKeyCode)) {
            boolean high = pressedKey == latchHighKeyCode;
            int target = high ? 12 : -12;
            octaveLatch = octaveLatch == target ? 0 : target;
            flash(octaveLatch == 0 ? "八度解锁"
                    : (high ? "♯ 高八度锁（再按住右键修饰 = 高高音）" : "♭ 低八度锁（再按住左键修饰 = 低低音）"), false);
            return true;
        }
        // 踏板/闷音键：sustain 乐器（钢琴）K = 延音踏板；双击 K 切换踏板模式——
        // 止音模式（默认）：松键制音，按住 K = 延音；
        // 延音模式：松键默认延音，按住 K = 临时止音（踏板语义整体反转，适合长连奏）。
        // decay 乐器（吉他/鼓）K = 瞬时闷音（原行为）
        if (pressedKey == pedalKeyCode) {
            if (current != null && current.sustainPedal) {
                    if (action == InputConstants.PRESS) {
                        long now = System.currentTimeMillis();
                        boolean doubleTap = now - lastPedalPressAt < DOUBLE_TAP_MS;
                        lastPedalPressAt = now;
                        pedalKeyDown = true;
                        if (doubleTap) {
                            pedalLatched = !pedalLatched;
                            flash(pedalLatched ? "延音模式：松键延音，按住 K 临时止音" : "止音模式：松键制音，按住 K 延音", false);
                        }
                        syncPedal();
                    } else if (action == InputConstants.RELEASE && pedalKeyDown) {
                        pedalKeyDown = false;
                        syncPedal();
                    }
            } else if (action == InputConstants.PRESS) {
                damper();
            }
            return true;
        }
        for (KeySlot slot : slots) {
            if (slot.keyCode == InputConstants.UNKNOWN.getValue() || slot.keyCode != pressedKey) {
                continue;
            }
            if (action == InputConstants.PRESS) {
                noteOn(mc, slot);
            } else if (action == InputConstants.RELEASE) {
                noteOff(slot);
            }
            // GLFW_REPEAT 忽略：noteOn 自带按住去重
            return true;
        }
        return false;
    }

    /** 按下演奏键：基础半音 + 当前修饰键（含八度锁）→ note-on（超音域本地静音）。手动按键会打断自动演奏。 */
    private static void noteOn(Minecraft mc, KeySlot slot) {
        if (autoPlaying) {
            stopAuto(true); // 手动接管：先停自动演奏
        }
        if (slot.held) {
            return; // 按住中不重触发（含被视作按下的 REPEAT）
        }
        int shift = effectiveShift(mc);
        int semitone = slot.base + shift;
        if (!current.inRange(semitone)) {
            flash("超出音域", true);
            return;
        }
        ChordPayloads.sendNote(current.id, semitone, velocityLevel, 0);
        if (current.releaseDecay) {
            sounding.add(semitone); // decay 乐器：该音可能留下余音，记入止音集合
        }
        slot.held = true;
        slot.semitone = semitone;
        slot.pressedAt = System.currentTimeMillis();
        slot.label = labelFor(slot, shift);
        flash(slot.label, false);
    }

    /** 松开演奏键：按当前半音发 note-off（变调后超域静音的键没有活跃音，不发）。 */
    private static void noteOff(KeySlot slot) {
        if (!slot.held) {
            return;
        }
        slot.held = false;
        if (current != null && slot.semitone != MUTED_SEMITONE) {
            ChordPayloads.sendNote(current.id, slot.semitone, offFlags());
            // sustain 钢琴未踩踏板的松键 → 150ms 后被服务端制音，可移出止音集合；
            // 踩踏板/decay 乐器：余音仍在，保留待硬止音
            if (current.sustainPedal && !effectiveSustainOn()) {
                sounding.remove(slot.semitone);
            }
        }
        slot.label = null;
    }

    /** 松键 flags：有效延音状态下的 sustain 乐器音符转余音而非制音（含踏板模式锁）。 */
    private static int offFlags() {
        int flags = NotePayload.FLAG_NOTE_OFF;
        if (current != null && current.sustainPedal && effectiveSustainOn()) {
            flags |= NotePayload.FLAG_SUSTAIN;
        }
        return flags;
    }

    /** 当前鼠标修饰键的半音偏移（左 −12 / 右 +12 / 中 +1，可叠加）。 */
    private static int currentShift(Minecraft mc) {
        int shift = 0;
        if (mc.mouseHandler.isLeftPressed()) {
            shift -= 12;
        }
        if (mc.mouseHandler.isRightPressed()) {
            shift += 12;
        }
        if (mc.mouseHandler.isMiddlePressed()) {
            shift += 1;
        }
        return shift;
    }

    /** 有效修饰偏移 = 八度锁 + 鼠标修饰键（高八度锁 + 按住右键 = 高高音，反之低低音）。 */
    private static int effectiveShift(Minecraft mc) {
        return octaveLatch + currentShift(mc);
    }

    /** 有效延音状态：踏板模式锁 XOR 踏板键按住（延音模式下不按 K 才是延音），或实时 MIDI 踏板踩下。 */
    static boolean effectiveSustainOn() {
        return pedalLatched != pedalKeyDown || midiPedalDown;
    }

    /**
     * 踏板状态变化后同步服务端：发送当前有效状态；转为止音时服务端会掐掉所有
     * 「键已松开」的余音，客户端止音集合同步收缩为按住中的键。
     */
    private static void syncPedal() {
        boolean down = effectiveSustainOn();
        ChordPayloads.sendPedal(down);
        if (!down) {
            sounding.clear();
            for (KeySlot slot : slots) {
                if (slot.held && slot.semitone != MUTED_SEMITONE) {
                    sounding.add(slot.semitone);
                }
            }
            sounding.addAll(midiHeld.values());
        }
    }

    /**
     * 修饰键变化：宽限窗口内按下的音立即换到新音高（旧音 note-off + 新音 note-on），
     * 新音高超出音域时静音保持（哨兵值）、修饰键回到音域内自动恢复。
     * 宽限窗口外按下的音不受影响——保持当前音高直到松键，修饰键只影响下次按键。
     */
    private static void retuneHeldNotes(int shift) {
        long now = System.currentTimeMillis();
        for (KeySlot slot : slots) {
            if (!slot.held) {
                continue;
            }
            if (now - slot.pressedAt > RETUNE_GRACE_MS) {
                continue; // 已过宽限：按住中的音保持原音高（真实乐器语义）
            }
            int oldSemitone = slot.semitone;
            int newSemitone = slot.base + shift;
            if (oldSemitone != MUTED_SEMITONE) {
                ChordPayloads.sendNote(current.id, oldSemitone, offFlags());
                if (current.sustainPedal && !effectiveSustainOn()) {
                    sounding.remove(oldSemitone); // 松键制音语义同 noteOff
                }
            }
            if (current.inRange(newSemitone)) {
                ChordPayloads.sendNote(current.id, newSemitone, velocityLevel, 0);
                if (current.releaseDecay) {
                    sounding.add(newSemitone); // 旧音自然延续（decay）+ 新音起，像重击
                }
                slot.semitone = newSemitone;
                slot.label = labelFor(slot, shift);
            } else {
                slot.semitone = MUTED_SEMITONE;
                slot.label = null;
            }
        }
    }

    /**
     * 松开所有按住的音。
     *
     * @param force true = 硬止音（退出演奏态：decay 乐器的余音也一并掐掉）；
     *              false = 普通 note-off（打开界面等临时场合：钢琴余音自然延续）
     */
    static void releaseAllHeld(boolean force) {
        boolean decay = current != null && current.releaseDecay;
        for (KeySlot slot : slots) {
            if (slot.held) {
                if (force && decay && slot.semitone != MUTED_SEMITONE) {
                    slot.held = false;
                    ChordPayloads.sendNote(current.id, slot.semitone,
                            NotePayload.FLAG_NOTE_OFF | NotePayload.FLAG_FORCE_STOP);
                    slot.label = null;
                } else {
                    noteOff(slot);
                }
            }
        }
        releaseMidiHeld(force);
        if (force) {
            forceStopSounding();
        }
    }

    /** 释放实时 MIDI 按住中的音（开界面 = 普通 note-off；退出 = 与键位一致的硬止音语义）。 */
    private static void releaseMidiHeld(boolean force) {
        if (midiHeld.isEmpty()) {
            return;
        }
        if (current == null) {
            midiHeld.clear();
            return;
        }
        for (Map.Entry<Integer, Integer> entry : new ArrayList<>(midiHeld.entrySet())) {
            int semitone = entry.getValue();
            if (force && current.releaseDecay) {
                ChordPayloads.sendNote(current.id, semitone,
                        NotePayload.FLAG_NOTE_OFF | NotePayload.FLAG_FORCE_STOP);
            } else {
                ChordPayloads.sendNote(current.id, semitone, offFlags());
                if (current.sustainPedal && !effectiveSustainOn()) {
                    sounding.remove(semitone);
                }
            }
        }
        midiHeld.clear();
    }

    /** 硬止音：对 sounding 集合（按住中 + 余音）逐一半音发 FORCE note-off，然后清空。 */
    private static void forceStopSounding() {
        if (current == null) {
            sounding.clear();
            return;
        }
        for (int semitone : sounding) {
            ChordPayloads.sendNote(current.id, semitone,
                    NotePayload.FLAG_NOTE_OFF | NotePayload.FLAG_FORCE_STOP);
        }
        sounding.clear();
    }

    /** 闷音：掐掉当前所有声音（含按住中的音与余音）。仅 decay 乐器（吉他/鼓）有意义。 */
    static void damper() {
        if (!playing || current == null || !current.releaseDecay || current.sustainPedal || sounding.isEmpty()) {
            return;
        }
        // 按住中的键也一并静掉：置为未按住，后续松键事件空转
        for (KeySlot slot : slots) {
            if (slot.held) {
                slot.held = false;
                slot.label = null;
            }
        }
        midiHeld.clear(); // MIDI 按住的音同理（后续 note-off 空转）
        forceStopSounding();
        flash("闷音", false);
    }

    /** 当前乐器是否为 decay 释放模式（吉他/鼓：松键自然衰减，K=瞬时闷音）。HUD 提示用。 */
    public static boolean decayRelease() {
        return current != null && current.releaseDecay && !current.sustainPedal;
    }

    /** 当前乐器是否为 sustain 释放模式（钢琴：默认松键制音，K=按住的延音踏板）。HUD 提示用。 */
    public static boolean sustainPedalMode() {
        return current != null && current.sustainPedal;
    }

    /** 有效延音是否开启（踏板按住或延音模式；仅 sustain 乐器）。HUD 指示用。 */
    public static boolean pedalHeld() {
        return effectiveSustainOn();
    }

    /** 踏板模式是否锁定为延音模式（双击 K 切换）。HUD 指示用。 */
    public static boolean pedalLatched() {
        return pedalLatched;
    }

    /** 显示名：直排音配置了 label 且无修饰时用 label，否则按半音自动生成（鼓件名 → 简谱名）。 */
    static String labelFor(KeySlot slot, int shift) {
        if (slot.customLabel != null && shift == 0) {
            return slot.customLabel;
        }
        return semitoneName(slot.base + shift);
    }

    /** 半音偏移 → 当前乐器的显示名：服务端定义了区间标签（鼓件名）优先，否则简谱名。 */
    static String semitoneName(int semitone) {
        if (current != null) {
            String label = current.labelFor(semitone);
            if (label != null) {
                return label;
            }
        }
        int octave = Math.floorDiv(semitone, 12);
        int within = Math.floorMod(semitone, 12);
        String prefix = switch (octave) {
            case -1 -> "低";
            case 0 -> "";
            case 1 -> "高";
            default -> octave > 0 ? "高" + "高".repeat(octave - 1) : "低" + "低".repeat(-octave - 1);
        };
        return prefix + SOLFEGE[within];
    }

    /** HUD：当前按住（持续发声）的音名，供渲染持续提示。 */
    static String heldNotesLabel() {
        if (!playing) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (KeySlot slot : slots) {
            if (slot.label != null) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append('♪').append(slot.label);
            }
        }
        if (!midiHeld.isEmpty()) {
            List<Integer> midi = new ArrayList<>(midiHeld.values());
            java.util.Collections.sort(midi);
            for (int semitone : midi) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append('♪').append(semitoneName(semitone));
            }
        }
        return sb.toString();
    }

    // ---- 实时 MIDI 输入（MidiInput 设备事件 → 与键盘同一演奏管线）----

    /**
     * MIDI 键盘按下（60 = 乐器中心音 C4）：真实力度 1~127 直传服务端力度层；
     * 出界音八度折叠入域（与 MIDI 文件转谱同规则）；手动音符打断自动演奏，同键盘。
     * 开界面期间忽略（与键盘事件一致；按住中的音已由 tick 的 releaseAllHeld 释放）。
     */
    static void onMidiNoteOn(int pitch, int velocity) {
        Minecraft mc = Minecraft.getInstance();
        if (!playing || current == null || mc == null || mc.player == null || mc.gui.screen() != null) {
            return;
        }
        if (autoPlaying) {
            stopAuto(true); // 手动接管：先停自动演奏
        }
        int semitone = foldMidiPitch(pitch);
        if (semitone == MUTED_SEMITONE) {
            return; // 八度折叠也够不着：丢弃
        }
        velocity = Math.max(1, Math.min(127, velocity));
        ChordPayloads.sendNote(current.id, semitone, velocity, 0);
        if (current.releaseDecay) {
            sounding.add(semitone);
        }
        midiHeld.put(pitch, semitone); // 同音高重触发：服务端先停旧音再启新音
        flash(semitoneName(semitone), false);
    }

    /** MIDI 松开：按本次发声的半音发 note-off（踏板期间自然转余音）。 */
    static void onMidiNoteOff(int pitch) {
        Integer semitone = midiHeld.remove(pitch);
        if (semitone == null || !playing || current == null) {
            return;
        }
        ChordPayloads.sendNote(current.id, semitone, offFlags());
        if (current.sustainPedal && !effectiveSustainOn()) {
            sounding.remove(semitone);
        }
    }

    /** MIDI 延音踏板（CC64 ≥ 64 踩下）：与 K 键踏板取或，状态变化即同步服务端。 */
    static void onMidiSustain(boolean down) {
        if (!playing || current == null || !current.sustainPedal || midiPedalDown == down) {
            return;
        }
        midiPedalDown = down;
        syncPedal();
    }

    /**
     * MIDI 音高 → 乐器半音：钢琴 [-39,48] 覆盖 88 键全域直映射；其他乐器出界音
     * 按 ±12 八度折叠入域（只动八度不动调内位置），折叠仍够不着的丢弃。
     */
    private static int foldMidiPitch(int pitch) {
        int semitone = pitch - 60;
        if (current.inRange(semitone)) {
            return semitone;
        }
        while (semitone > current.maxSemitone) {
            semitone -= 12;
        }
        while (semitone < current.minSemitone) {
            semitone += 12;
        }
        return current.inRange(semitone) ? semitone : MUTED_SEMITONE;
    }

    /** 实时 MIDI 输入设备名（null = 未连接）；HUD 状态行用。 */
    public static String midiDeviceName() {
        return MidiInput.connectedDeviceName();
    }

    // ---- 自动演奏（客户端 MIDI 文件引擎）----

    /**
     * 播放客户端 MIDI 文件（ChordMidiScreen 点击触发）：本机转谱（自动移调适配当前
     * 乐器音域、超声部收敛、出界八度折叠——任何 MIDI 100% 可奏），复用自动演奏引擎，
     * 音符全部带 AUTO 标记并通告 chord:auto 会话——服务端以自动演奏状态展示（金色粒子）。
     */
    /** 未收到 chord:poly（旧服务端）时的兜底：踏板乐器（钢琴）按十指容量，其余按旋律默认 6。 */
    private static final int FALLBACK_POLY_SUSTAIN = 10;

    /** MIDI 自动单音收敛阈值：优先服务端声明值（chord:poly），旧服务端按释放模式启发式。 */
    static int effectiveMaxPolyphony(InstrumentDef def) {
        Integer declared = polyByInstrument.get(def.id);
        return declared != null ? declared
                : def.sustainPedal ? FALLBACK_POLY_SUSTAIN
                : chord.protocol.score.MidiConverter.DEFAULT_MAX_POLYPHONY;
    }

    static void playMidiFile(Minecraft mc, java.nio.file.Path file) {
        if (!playing || current == null) {
            flash("先进入演奏态（手持乐器右键 / 右键钢琴）再播放 MIDI", true);
            return;
        }
        if (autoPlaying) {
            stopAuto(true); // 换曲
        }
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(file);
        } catch (Exception e) {
            flash("读取 MIDI 失败: " + e.getMessage(), true);
            return;
        }
        String title = file.getFileName().toString();
        int dot = title.toLowerCase(java.util.Locale.ROOT).lastIndexOf('.');
        if (dot > 0) {
            title = title.substring(0, dot);
        }
        chord.protocol.score.MidiConverter.Result result;
        try {
            result = chord.protocol.score.MidiConverter.convert(bytes, title, null, null,
                    current.minSemitone, current.maxSemitone, effectiveMaxPolyphony(current),
                    current.sustainPedal);
        } catch (Exception e) {
            flash("解析 MIDI 失败: " + e.getMessage(), true);
            return;
        }
        score = result.score();
        StringBuilder info = new StringBuilder("MIDI《").append(title).append("》 ")
                .append(score.notes.size()).append(" 音");
        if (result.chosenTranspose() != 0) {
            info.append(" · 移调 ").append(result.chosenTranspose() > 0 ? "+" : "").append(result.chosenTranspose());
        }
        int minVel = 127, maxVel = 1;
        for (Score.Note n : score.notes) {
            minVel = Math.min(minVel, n.velocity());
            maxVel = Math.max(maxVel, n.velocity());
        }
        if (minVel != Score.DEFAULT_VELOCITY || maxVel != Score.DEFAULT_VELOCITY) {
            info.append(" · 力度 ").append(minVel).append("~").append(maxVel);
        }
        if (result.pedalExtendedNotes() > 0) {
            info.append(" · 踏板延音 ").append(result.pedalExtendedNotes());
        }
        if (result.dedupedNotes() > 0) {
            info.append(" · 去重 ").append(result.dedupedNotes());
        }
        if (result.melodyExtracted()) {
            info.append(" · 已收敛单旋律");
        }
        if (result.cappedNotes() > 0) {
            info.append(" · 尖峰封顶 ").append(result.cappedNotes());
        }
        if (result.foldedNotes() > 0 || result.droppedNotes() > 0) {
            info.append(" · 折叠 ").append(result.foldedNotes()).append(" · 丢弃 ").append(result.droppedNotes());
        }
        flash(info.toString(), false);
        startAuto(mc);
    }

    /** 自动演奏键：播放中按 = 停止；空闲按 = 打开客户端 MIDI 中心（0.7.15 起唯一入口）。 */
    static void toggleAutoPlay(Minecraft mc) {
        if (autoPlaying) {
            stopAuto(true);
            flash("停止自动演奏", false);
            return;
        }
        mc.setScreenAndShow(new ChordMidiScreen());
    }

    /**
     * 启动播放：后台单线程按谱面时刻调度，动作经 mc.execute 回到客户端线程发包。
     * 音符以 FLAG_AUTO 标记；会话通告（chord:auto START/STOP）告知服务端做「非现场弹奏」提示。
     */
    private static void startAuto(Minecraft mc) {
        autoPlaying = true;
        autoRunId++;
        int runId = autoRunId;
        autoStartNanos = System.nanoTime();
        autoNoteIndex = 0;
        autoSkipped = 0;
        autoActive.clear();
        ChordPayloads.sendAuto(chord.protocol.AutoPayload.ACTION_START, score.title);
        if (autoExec == null) {
            autoExec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Chord-Autoplay");
                thread.setDaemon(true);
                return thread;
            });
        }
        autoFutures.clear();
        for (int i = 0; i < score.notes.size(); i++) {
            final int index = i;
            Score.Note note = score.notes.get(i);
            int semitone = note.pitch() + score.transpose;
            final int velocity = note.velocity();
            autoFutures.add(autoExec.schedule(
                    () -> mc.execute(() -> autoNoteOn(runId, index, semitone, velocity)),
                    note.startMs(), TimeUnit.MILLISECONDS));
            autoFutures.add(autoExec.schedule(
                    () -> mc.execute(() -> autoNoteOff(runId, semitone)),
                    note.startMs() + (long) note.durationMs(), TimeUnit.MILLISECONDS));
        }
        autoFutures.add(autoExec.schedule(
                () -> mc.execute(() -> autoFinished(runId)),
                score.durationMs() + 60, TimeUnit.MILLISECONDS));
        flash("自动演奏《" + score.title + "》", false);
    }

    private static void autoNoteOn(int runId, int index, int semitone, int velocity) {
        if (runId != autoRunId || !autoPlaying || !playing) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || current == null) {
            stopAuto(false);
            return;
        }
        if (!current.inRange(semitone)) {
            autoSkipped++;
            return;
        }
        ChordPayloads.sendNote(current.id, semitone, velocity, NotePayload.FLAG_AUTO);
        autoActive.add(semitone);
        if (current.releaseDecay) {
            sounding.add(semitone); // 自动演奏的钢琴音同样留余音，止音键可掐
        }
        autoNoteIndex = index + 1;
    }

    private static void autoNoteOff(int runId, int semitone) {
        if (runId != autoRunId || !autoPlaying || current == null) {
            return;
        }
        if (autoActive.remove(semitone)) {
            ChordPayloads.sendNote(current.id, semitone, NotePayload.FLAG_NOTE_OFF | NotePayload.FLAG_AUTO);
        }
    }

    private static void autoFinished(int runId) {
        if (runId == autoRunId && autoPlaying) {
            stopAuto(true);
            flash("自动演奏完毕", false);
        }
    }

    /** 停止播放：取消调度、补发残余 note-off、通告服务端（announce=false 用于断线/退出兜底）。 */
    static void stopAuto(boolean announce) {
        if (!autoPlaying) {
            return;
        }
        autoPlaying = false;
        autoRunId++;
        for (ScheduledFuture<?> future : autoFutures) {
            future.cancel(false);
        }
        autoFutures.clear();
        if (current != null) {
            for (int semitone : autoActive) {
                ChordPayloads.sendNote(current.id, semitone,
                        NotePayload.FLAG_NOTE_OFF | NotePayload.FLAG_AUTO);
            }
        }
        autoActive.clear();
        if (announce) {
            ChordPayloads.sendAuto(chord.protocol.AutoPayload.ACTION_STOP, "");
        }
    }

    public static boolean isAutoPlaying() {
        return autoPlaying;
    }

    /** 自动演奏键短名（HUD 提示用；读配置键名，演奏配置页可改键不能硬编码 G）。 */
    public static String autoKeyName() {
        String name = ChordClient.config != null && ChordClient.config.autoKey != null
                ? ChordClient.config.autoKey : "key.keyboard.g";
        return name.startsWith("key.keyboard.") ? name.substring("key.keyboard.".length()).toUpperCase() : name;
    }

    /** HUD：未在自动演奏时的入口提示行（0.7.15 谱书移除后自动演奏键的唯一空闲语义）。 */
    public static String midiCenterHintLabel() {
        if (!playing || autoPlaying) {
            return "";
        }
        return "按 " + autoKeyName() + " 打开 MIDI 中心（实时输入 · 文件自动演奏）";
    }

    /** HUD：自动演奏进度行（小节 i/N + 音符 i/N + 跳过数）。 */
    public static String autoProgressLabel() {
        if (!autoPlaying || score == null) {
            return "";
        }
        double measureMs = score.timeSigNum * (4.0 / score.timeSigDen) * (60000.0 / score.bpm());
        int totalMeasures = Math.max(1, (int) Math.ceil(score.durationMs() / measureMs));
        long elapsed = (long) ((System.nanoTime() - autoStartNanos) / 1_000_000.0);
        int measure = (int) Math.min(totalMeasures, elapsed / (long) Math.max(1, Math.round(measureMs)) + 1);
        StringBuilder sb = new StringBuilder("▶ 自动演奏《").append(score.title).append("》 ")
                .append(measure).append('/').append(totalMeasures).append(" 小节 · ")
                .append(autoNoteIndex).append('/').append(score.notes.size()).append(" 音");
        if (autoSkipped > 0) {
            sb.append(" · 跳过").append(autoSkipped);
        }
        return sb.toString();
    }

    // ---- 显示辅助 ----

    static void flash(String text, boolean warn) {
        flashes.addLast(new Flash(text, warn));
        while (flashes.size() > 8) {
            flashes.removeFirst();
        }
    }

    static Iterator<Flash> flashes() {
        return flashes.iterator();
    }

    static void pruneFlashes() {
        long now = System.currentTimeMillis();
        while (!flashes.isEmpty() && now - flashes.peekFirst().at > FLASH_MS) {
            flashes.removeFirst();
        }
    }

    // ---- 物品识别 ----

    /**
     * 识别 Chord 乐器物品。
     *
     * <p>物品由服务端插件发放，标记写在 minecraft:custom_data 组件内、
     * Bukkit PDC 路径 PublicBukkitValues -&gt; "chord:instrument"（字符串）。
     */
    static String instrumentKey(ItemStack stack) {
        return bukkitValue(stack, "chord:instrument");
    }

    static boolean isInstrument(ItemStack stack) {
        return instrumentKey(stack) != null;
    }

    /** 读物品 Bukkit PDC 字符串（custom_data 组件 PublicBukkitValues 下），乐器标记读取。 */
    private static String bukkitValue(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag root = data.copyTag();
        CompoundTag bukkit = root.getCompound("PublicBukkitValues").orElse(null);
        if (bukkit == null) {
            return null;
        }
        return bukkit.getString(key).filter(s -> !s.isEmpty()).orElse(null);
    }
}
