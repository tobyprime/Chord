package chord.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端配置（config/chord.json）。
 *
 * <p>0.7.0 起演奏输入完全自管：物理键与半音/显示名同页配置（游戏内「演奏配置」键，
 * 默认 J），不再注册原版按键设置条目（除打开配置页的 J 本身）。演奏态内按键在
 * 分发源头被拦截消费，天然不与其他 Mod 的绑定冲突。
 *
 * <p>slots：一个演奏键槽 = { key: 物理键（InputConstants 键名，如 "key.keyboard.z"，
 * null=未绑定）、semitone: 相对乐器中心音的半音、label: HUD 显示名（可省略）}。
 * 鼠标左/右/中修饰键（∓12 八度 / +1 半音）对所有槽通用、照常叠加。
 *
 * <p>功能键：auto_key 自动演奏（默认 G）、pedal_key 踏板（默认 K；双击切延音模式）、
 * latch_high_key / latch_low_key 高/低八度锁（默认 I / U；按一次锁定 ±12，
 * 锁定后按住鼠标修饰键叠加成高高/低低音）。
 */
public final class ChordConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("chord");

    /** 演奏键槽总数（默认前 8 槽预填三角洲音级布局）。 */
    public static final int MAX_SLOTS = 24;

    public static final class Slot {
        /** InputConstants 键名（"key.keyboard.z"）；null/空 = 未绑定。 */
        public String key;
        /** 相对乐器中心音的半音偏移（修饰键叠加在其上）。 */
        public int semitone;
        /** HUD 显示名，可省略（省略时按半音自动生成简谱名/鼓件名）。 */
        public String label;

        public Slot copy() {
            Slot slot = new Slot();
            slot.key = key;
            slot.semitone = semitone;
            slot.label = label;
            return slot;
        }
    }

    /** 旧版直排音条目（迁移用，读后并入 slots）。 */
    static class LegacyDirectNote {
        public String label;
        public int semitone;
    }

    @SerializedName("slots")
    public List<Slot> slots = new ArrayList<>();

    @SerializedName("auto_key")
    public String autoKey = "key.keyboard.g";

    @SerializedName("pedal_key")
    public String pedalKey = "key.keyboard.k";

    @SerializedName("latch_high_key")
    public String latchHighKey = "key.keyboard.i";

    @SerializedName("latch_low_key")
    public String latchLowKey = "key.keyboard.u";

    /**
     * 实时 MIDI 输入设备名（0.7.12）：精确名 = 指定设备；空 = 自动连第一个 MIDI IN；
     * "none" = 关闭。游戏内 MIDI 播放器界面可循环切换（自动写入本字段）。
     */
    @SerializedName("midi_device")
    public String midiDevice = "";

    /** 旧版 direct_notes（读入迁移，保存时不再写出）。 */
    @SerializedName("direct_notes")
    private List<LegacyDirectNote> legacyDirectNotes;

    public static ChordConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("chord.json");
        try {
            if (Files.notExists(file)) {
                Files.writeString(file, defaultJson(), StandardCharsets.UTF_8);
                return defaults();
            }
            ChordConfig config = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), ChordConfig.class);
            if (config == null) {
                return defaults();
            }
            config.migrate();
            return config;
        } catch (Exception e) {
            LOGGER.warn("读取 chord.json 失败，使用默认键位: {}", e.toString());
            return defaults();
        }
    }

    /** 默认槽位：前 8 = 三角洲音级布局 Z X C V B N M , → 1 2 3 4 5 6 7 高1。 */
    public static ChordConfig defaults() {
        ChordConfig config = new ChordConfig();
        String[] keys = { "z", "x", "c", "v", "b", "n", "m", "comma" };
        int[] semitones = { 0, 2, 4, 5, 7, 9, 11, 12 };
        for (int i = 0; i < MAX_SLOTS; i++) {
            Slot slot = new Slot();
            if (i < keys.length) {
                slot.key = "key.keyboard." + keys[i];
                slot.semitone = semitones[i];
            }
            config.slots.add(slot);
        }
        return config;
    }

    /** 旧版迁移：slots 缺失时用默认布局；旧 direct_notes 的半音/显示名追加到空槽（物理键需重绑）。 */
    private void migrate() {
        if (slots == null || slots.isEmpty()) {
            ChordConfig def = defaults();
            slots = def.slots;
        }
        while (slots.size() < MAX_SLOTS) {
            slots.add(new Slot());
        }
        if (slots.size() > MAX_SLOTS) {
            slots = new ArrayList<>(slots.subList(0, MAX_SLOTS));
        }
        if (legacyDirectNotes != null && !legacyDirectNotes.isEmpty()) {
            int next = 8; // 前 8 槽是默认音级布局，从第 9 槽起并入
            for (LegacyDirectNote note : legacyDirectNotes) {
                if (next >= MAX_SLOTS || note == null) {
                    break;
                }
                Slot slot = slots.get(next++);
                slot.semitone = note.semitone;
                slot.label = note.label;
            }
            legacyDirectNotes = null;
        }
        if (autoKey == null || autoKey.isBlank()) autoKey = "key.keyboard.g";
        if (pedalKey == null || pedalKey.isBlank()) pedalKey = "key.keyboard.k";
        if (latchHighKey == null || latchHighKey.isBlank()) latchHighKey = "key.keyboard.i";
        if (latchLowKey == null || latchLowKey.isBlank()) latchLowKey = "key.keyboard.u";
        if (midiDevice == null) midiDevice = "";
    }

    /** 保存回 chord.json（保留说明性头字段）。 */
    public void save() {
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve("chord.json");
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("_说明", "Chord 客户端配置。游戏内按「演奏配置」键（默认 J，原版按键设置可改）可视化编辑，保存即生效；也可手动改本文件（需重启）。");
            root.addProperty("_格式", "slots = 演奏键槽：key = 物理键（key.keyboard.z 风格，null=未绑定）、semitone = 相对乐器中心音的半音（-24~32，如 12=高1）、label = HUD 显示名（可省略）。鼠标左/右/中修饰键（降八度/升八度/升半音）对所有槽叠加。功能键 auto_key=自动演奏(G)、pedal_key=踏板(K，双击切延音模式)、latch_high_key/latch_low_key=高/低八度锁(I/U，锁定后按住鼠标修饰=高高/低低音)。");
            root.addProperty("_示例槽位", "钢琴半音阶：key.keyboard.z=0、key.keyboard.s=1、key.keyboard.x=2 …（第 9 槽起照此填）");
            JsonArray array = new JsonArray();
            for (Slot slot : slots) {
                JsonObject entry = new JsonObject();
                if (slot.key != null && !slot.key.isBlank()) {
                    entry.addProperty("key", slot.key);
                }
                entry.addProperty("semitone", slot.semitone);
                if (slot.label != null && !slot.label.isBlank()) {
                    entry.addProperty("label", slot.label);
                }
                array.add(entry);
            }
            root.add("slots", array);
            root.addProperty("auto_key", autoKey);
            root.addProperty("pedal_key", pedalKey);
            root.addProperty("latch_high_key", latchHighKey);
            root.addProperty("latch_low_key", latchLowKey);
            root.addProperty("midi_device", midiDevice);
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("保存 chord.json 失败: {}", e.toString());
        }
    }

    private static String defaultJson() {
        ChordConfig def = defaults();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_说明\": \"Chord 客户端配置。游戏内按「演奏配置」键（默认 J）可视化编辑，保存即生效。\",\n");
        sb.append("  \"slots\": [\n");
        for (int i = 0; i < def.slots.size(); i++) {
            Slot slot = def.slots.get(i);
            sb.append("    { ");
            if (slot.key != null) {
                sb.append("\"key\": \"").append(slot.key).append("\", ");
            }
            sb.append("\"semitone\": ").append(slot.semitone).append(" }");
            sb.append(i < def.slots.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"auto_key\": \"key.keyboard.g\",\n");
        sb.append("  \"pedal_key\": \"key.keyboard.k\",\n");
        sb.append("  \"latch_high_key\": \"key.keyboard.i\",\n");
        sb.append("  \"latch_low_key\": \"key.keyboard.u\",\n");
        sb.append("  \"midi_device\": \"\"\n}\n");
        return sb.toString();
    }

    private ChordConfig() {}
}
