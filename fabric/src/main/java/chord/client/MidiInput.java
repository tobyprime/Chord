package chord.client;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Transmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 实时 MIDI 输入（数字钢琴 / MIDI 键盘 → 演奏管线）。
 *
 * <p>用 javax.sound.midi 打开本机 MIDI IN 设备；ShortMessage 在设备自己的线程回调，
 * 经 {@code Minecraft.execute} 转回客户端线程处理——与键盘事件同为帧级延迟，
 * 之后与键盘输入共用 PlayState 的 note-on/note-off/踏板管线，
 * <b>线协议与服务端零改动</b>（chord:play 的 velocity 字段 + chord:pedal 本就存在）。
 *
 * <p>事件语义：
 * <ul>
 *   <li>NOTE_ON（力度 &gt; 0）→ PlayState.onMidiNoteOn，真实力度 1~127 直传力度层</li>
 *   <li>NOTE_OFF / NOTE_ON 力度 0 → PlayState.onMidiNoteOff</li>
 *   <li>CONTROL_CHANGE 64（延音踏板）值 ≥ 64 = 踩下 → PlayState.onMidiSustain</li>
 * </ul>
 *
 * <p>设备选择（chord.json 的 midi_device）：设备名 = 指定设备；空 = 自动连第一个；
 * {@link #OFF} = 关闭。MIDI 播放器界面可循环切换。后台每 5 秒重扫一次：
 * 拔线释放旧句柄、重插自动回连（不用重启游戏）。
 */
public final class MidiInput {

    private static final Logger LOGGER = LoggerFactory.getLogger("chord");

    /** chord.json midi_device 的「关闭」哨兵。 */
    public static final String OFF = "none";

    private static final long RESCAN_SECONDS = 5;

    private static MidiDevice device;
    private static Transmitter transmitter;
    /** 已连接设备名；volatile：HUD/界面无锁读，写全在锁内。 */
    private static volatile String connectedName;

    /** 设备线程 → 客户端线程路由。所有异常吞掉：坏设备不能崩游戏。 */
    private static final Receiver ROUTER = new Receiver() {
        @Override
        public void send(MidiMessage message, long timeStamp) {
            try {
                if (!(message instanceof ShortMessage sm)) {
                    return;
                }
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) {
                    return;
                }
                int pitch = sm.getData1();
                switch (sm.getCommand()) {
                    case ShortMessage.NOTE_ON -> {
                        int velocity = sm.getData2();
                        if (velocity > 0) {
                            mc.execute(() -> {
                                try {
                                    PlayState.onMidiNoteOn(pitch, velocity);
                                } catch (Throwable ignored) {
                                }
                            });
                        } else {
                            mc.execute(() -> {
                                try {
                                    PlayState.onMidiNoteOff(pitch);
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                    }
                    case ShortMessage.NOTE_OFF -> mc.execute(() -> {
                        try {
                            PlayState.onMidiNoteOff(pitch);
                        } catch (Throwable ignored) {
                        }
                    });
                    case ShortMessage.CONTROL_CHANGE -> {
                        if (pitch == 64) {
                            boolean down = sm.getData2() >= 64;
                            mc.execute(() -> {
                                try {
                                    PlayState.onMidiSustain(down);
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                    }
                    default -> {
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("MIDI 事件处理异常", t);
            }
        }

        @Override
        public void close() {
        }
    };

    private MidiInput() {}

    /** 启动时按 chord.json 连接 + 启动周期重扫（拔线自动断开、重插自动回连）。 */
    public static void init() {
        String want = ChordClient.config != null ? ChordClient.config.midiDevice : "";
        if (OFF.equals(want)) {
            LOGGER.info("MIDI 输入已按配置关闭（midi_device = none）");
        } else if (want == null || want.isBlank()) {
            connect(null); // 自动：连第一个输入设备
        } else if (connect(want) == null) {
            connect(null); // 记忆的设备不在（未插）：先回落自动，重扫线程在设备出现后接管
        }
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Chord-MidiInput");
            thread.setDaemon(true);
            return thread;
        });
        exec.scheduleWithFixedDelay(MidiInput::rescan, RESCAN_SECONDS, RESCAN_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 本机 MIDI IN 设备（带发射器 = 能向我们发事件）。
     * 必须排除 Java 内置的 Sequencer/Synthesizer（Real Time Sequencer 也带发射器，
     * 但永远不会自发事件——自动连接绑上它等于静默失灵）；Windows 软波表等输出口天然不列。
     */
    public static List<MidiDevice.Info> inputDevices() {
        List<MidiDevice.Info> list = new ArrayList<>();
        try {
            for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
                try {
                    MidiDevice candidate = MidiSystem.getMidiDevice(info);
                    if (candidate instanceof javax.sound.midi.Sequencer
                            || candidate instanceof javax.sound.midi.Synthesizer) {
                        continue;
                    }
                    if (candidate.getMaxTransmitters() != 0) {
                        list.add(info);
                    }
                } catch (Exception ignored) {
                    // 单个设备枚举失败不影响其余
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("枚举 MIDI 设备失败: {}", t.toString());
        }
        return list;
    }

    /** 连接指定名字的 MIDI IN（null = 自动选第一个）；成功返回设备名。 */
    public static synchronized String connect(String name) {
        disconnect();
        for (MidiDevice.Info info : inputDevices()) {
            if (name != null && !info.getName().equals(name)) {
                continue;
            }
            try {
                MidiDevice dev = MidiSystem.getMidiDevice(info);
                dev.open();
                Transmitter tx = dev.getTransmitter();
                tx.setReceiver(ROUTER);
                device = dev;
                transmitter = tx;
                connectedName = info.getName();
                LOGGER.info("MIDI 输入已连接: {}", connectedName);
                flashOnClientThread("MIDI 已连接: " + connectedName, false);
                return connectedName;
            } catch (MidiUnavailableException | SecurityException e) {
                LOGGER.warn("连接 MIDI 设备 {} 失败: {}", info.getName(), e.toString());
            }
        }
        return null;
    }

    public static synchronized void disconnect() {
        Transmitter tx = transmitter;
        MidiDevice dev = device;
        String was = connectedName;
        transmitter = null;
        device = null;
        connectedName = null;
        if (tx != null) {
            try {
                tx.close();
            } catch (Throwable ignored) {
            }
        }
        if (dev != null) {
            try {
                dev.close();
            } catch (Throwable ignored) {
            }
        }
        if (was != null) {
            LOGGER.info("MIDI 输入已断开: {}", was);
            flashOnClientThread("MIDI 已断开: " + was, true);
        }
    }

    /** HUD 闪现只能从客户端线程入队（flashes 队列非线程安全；重扫线程会走到这里）。 */
    private static void flashOnClientThread(String text, boolean warn) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> PlayState.flash(text, warn));
        }
    }

    /**
     * 循环切换：当前设备 → 下一个 → … → 关闭 → 第一个；选择写入 chord.json 并立即生效。
     *
     * @return 切换结果描述（界面按钮文案用）
     */
    public static synchronized String cycle() {
        List<MidiDevice.Info> devices = inputDevices();
        if (devices.isEmpty()) {
            return "无 MIDI 输入设备";
        }
        String current = connectedName;
        if (current == null) {
            String name = connect(devices.get(0).getName());
            saveDevice(name);
            return name != null ? name : "连接失败";
        }
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getName().equals(current)) {
                if (i + 1 < devices.size()) {
                    String name = connect(devices.get(i + 1).getName());
                    saveDevice(name);
                    return name != null ? name : "连接失败";
                }
                disconnect();
                saveDevice(OFF);
                return "已关闭";
            }
        }
        // 当前设备已不在列表（拔线后重扫前手动切）：连第一个
        String name = connect(devices.get(0).getName());
        saveDevice(name);
        return name != null ? name : "连接失败";
    }

    private static void saveDevice(String name) {
        if (ChordClient.config != null && name != null) {
            ChordClient.config.midiDevice = name;
            ChordClient.config.save();
        }
    }

    /** 已连接设备名（null = 未连接）；HUD 状态行用。 */
    public static String connectedDeviceName() {
        return connectedName;
    }

    /** 周期重扫：未连接（含拔线）时按配置回连；显式关闭不动。 */
    private static synchronized void rescan() {
        try {
            String want = ChordClient.config != null ? ChordClient.config.midiDevice : "";
            if (OFF.equals(want)) {
                return;
            }
            if (connectedName != null) {
                boolean stillThere = false;
                for (MidiDevice.Info info : inputDevices()) {
                    if (info.getName().equals(connectedName)) {
                        stillThere = true;
                        break;
                    }
                }
                if (stillThere) {
                    return;
                }
                disconnect(); // 拔线：释放旧句柄，走下面的回连
            }
            connect(want == null || want.isBlank() ? null : want);
        } catch (Throwable t) {
            LOGGER.debug("MIDI 重扫异常", t);
        }
    }
}
