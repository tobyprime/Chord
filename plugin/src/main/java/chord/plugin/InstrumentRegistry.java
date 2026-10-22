package chord.plugin;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import chord.protocol.InstrumentDef;
import chord.protocol.PolyPayload;
import chord.protocol.SyncPayload;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 instruments.yml 加载乐器表。乐器 id 按配置顺序分配（0 起，u8 上限 256 个）。
 */
public final class InstrumentRegistry {

    private final JavaPlugin plugin;
    private final Map<String, Instrument> byKey = new LinkedHashMap<>();
    private final List<Instrument> ordered = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    public InstrumentRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        byKey.clear();
        ordered.clear();
        problems.clear();

        File file = new File(plugin.getDataFolder(), "instruments.yml");
        if (!file.exists()) {
            plugin.saveResource("instruments.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        int id = 0;
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                problems.add(key + ": 不是配置节，已跳过");
                continue;
            }
            String displayName = section.getString("display-name", key);
            Instrument.Entry entry = section.getString("entry", "item").trim().equalsIgnoreCase("world")
                    ? Instrument.Entry.WORLD
                    : Instrument.Entry.ITEM;
            Material baseItem = Material.matchMaterial(section.getString("base-item", "GOAT_HORN"));
            List<Integer> range = section.getIntegerList("semitone-range");
            int min = range.size() == 2 ? range.get(0) : -12;
            int max = range.size() == 2 ? range.get(1) : 12;
            double volume = Math.clamp(section.getDouble("volume", 1.0), 0.1, 4.0);
            var category = Instrument.Voice.parseCategory(
                    section.getString("category"), org.bukkit.SoundCategory.RECORDS);
            List<Instrument.Voice> voices = parseVoices(section, "voices");
            List<Instrument.Voice> fallback = parseVoices(section, "fallback-voices");
            List<Instrument.ReleaseVoice> release = parseReleaseVoices(key, section);
            List<Instrument.VelocityLayer> layers = parseLayers(key, section);
            if (!layers.isEmpty() && layers.stream().flatMap(l -> l.voices().stream()).count() < layers.size()) {
                problems.add(key + ": 存在空的力度层，已忽略全部分层");
                layers = List.of();
            }
            List<String> bindValues = section.getStringList("world-bind.match-values");
            String bindRegex = section.getString("world-bind.match-regex", null);
            Instrument.ReleaseMode releaseMode = parseReleaseMode(section.getString("release-mode", "stop"), key);
            int maxPolyphony = Math.clamp(section.getInt("max-polyphony", 6), 1, 32);

            if (entry == Instrument.Entry.ITEM && baseItem == null) {
                problems.add(key + ": base-item 无法识别，已跳过");
                continue;
            }
            if (voices.isEmpty()) {
                problems.add(key + ": 没有可用 voices，已跳过");
                continue;
            }
            if (id > 255) {
                problems.add("乐器超过 255 个，" + key + " 已跳过");
                continue;
            }
            Instrument instrument = new Instrument(id++, key, displayName, baseItem, entry,
                    min, max, volume, category, voices, fallback, bindValues, bindRegex, releaseMode,
                    layers, maxPolyphony, release);
            byKey.put(key, instrument);
            ordered.add(instrument);
        }
        if (ordered.isEmpty()) {
            problems.add("instruments.yml 未加载到任何乐器");
        }
    }

    /** 力度层：[{min-velocity, max-velocity, voices: [...]}, ...]，按力度路由到不同采样层。 */
    private List<Instrument.VelocityLayer> parseLayers(String key, ConfigurationSection section) {
        List<Instrument.VelocityLayer> result = new ArrayList<>();
        for (Map<?, ?> rawLayer : section.getMapList("layers")) {
            Object minV = rawLayer.get("min-velocity");
            Object maxV = rawLayer.get("max-velocity");
            Object voicesO = rawLayer.get("voices");
            if (!(minV instanceof Number minVel) || !(maxV instanceof Number maxVel)
                    || !(voicesO instanceof List<?> rawVoices)) {
                problems.add(key + ".layers: 层缺少 min-velocity/max-velocity/voices，已跳过该层");
                continue;
            }
            result.add(new Instrument.VelocityLayer(minVel.intValue(), maxVel.intValue(),
                    parseVoiceMaps(key + ".layers", rawVoices)));
        }
        return result;
    }

    /**
     * release-voices：[{min, max, base-pitch, held-ms: [各档素材源位置(ms)], sounds: [...],
     * short-sound: 短保持专用 0.3s 短尾（可缺省）}]。
     * heldMs 与 sounds 一一对应，服务端按保持毫秒数就近选档；缺省回落三档阈值（旧配置）。
     */
    private List<Instrument.ReleaseVoice> parseReleaseVoices(String key, ConfigurationSection section) {
        List<Instrument.ReleaseVoice> result = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("release-voices")) {
            Object minO = raw.get("min");
            Object maxO = raw.get("max");
            Object soundsO = raw.get("sounds");
            if (!(minO instanceof Number min) || !(maxO instanceof Number max)
                    || !(soundsO instanceof List<?> rawSounds)) {
                problems.add(key + ".release-voices: 行缺少 min/max/sounds，已跳过该行");
                continue;
            }
            List<String> sounds = new ArrayList<>();
            for (Object s : rawSounds) {
                if (s instanceof String str && !str.isBlank()) {
                    sounds.add(str.trim());
                }
            }
            if (sounds.isEmpty()) {
                problems.add(key + ".release-voices: sounds 为空，已跳过该行");
                continue;
            }
            long[] gradeHeldMs = parseLongList(key, raw.get("held-ms"), sounds.size(), "held-ms");
            long[] levelMs = parseLongList(key, raw.get("level-ms"), -1, "level-ms");
            double[] levelDb = parseDoubleList(key, raw.get("level-db"), levelMs.length, "level-db");
            double basePitch = raw.get("base-pitch") instanceof Number n ? n.doubleValue() : 1.0;
            String shortSound = raw.get("short-sound") instanceof String s && !s.isBlank()
                    ? s.trim() : null;
            result.add(new Instrument.ReleaseVoice(min.intValue(), max.intValue(), basePitch,
                    sounds, gradeHeldMs, levelMs, levelDb, shortSound));
        }
        return result;
    }


    /** yml 整数列表解析；expect = -1 表示不限长度，否则不一致时告警并返回空。 */
    private long[] parseLongList(String key, Object raw, int expect, String field) {
        if (!(raw instanceof List<?> list)) {
            return new long[0];
        }
        List<Long> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                out.add(n.longValue());
            }
        }
        if (expect >= 0 && out.size() != expect) {
            problems.add(key + ".release-voices: " + field + " 数量(" + out.size()
                    + ") 与 sounds 数量(" + expect + ") 不一致，忽略 " + field);
            return new long[0];
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < out.size(); i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private double[] parseDoubleList(String key, Object raw, int expect, String field) {
        if (!(raw instanceof List<?> list)) {
            return new double[0];
        }
        List<Double> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                out.add(n.doubleValue());
            }
        }
        if (expect >= 0 && out.size() != expect) {
            problems.add(key + ".release-voices: " + field + " 数量(" + out.size()
                    + ") 与 level-ms 数量(" + expect + ") 不一致，忽略 " + field);
            return new double[0];
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < out.size(); i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /** release-mode 解析：stop / decay / sustain（未知值按 stop 并记问题）。 */
    private Instrument.ReleaseMode parseReleaseMode(String raw, String key) {
        String value = raw == null ? "stop" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "decay" -> Instrument.ReleaseMode.DECAY;
            case "sustain" -> Instrument.ReleaseMode.SUSTAIN;
            case "stop" -> Instrument.ReleaseMode.STOP;
            default -> {
                problems.add(key + ": 未知 release-mode \"" + raw + "\"，按 stop 处理（可用 stop|decay|sustain）");
                yield Instrument.ReleaseMode.STOP;
            }
        };
    }

    private List<Instrument.Voice> parseVoices(ConfigurationSection section, String node) {
        return parseVoiceMaps(section.getName() + "." + node, section.getMapList(node));
    }

    @SuppressWarnings("unchecked")
    private List<Instrument.Voice> parseVoiceMaps(String context, List<?> rawVoices) {
        List<Instrument.Voice> result = new ArrayList<>();
        for (Object rawO : rawVoices) {
            if (!(rawO instanceof Map<?, ?> raw)) {
                continue;
            }
            Object minO = raw.get("min");
            Object maxO = raw.get("max");
            Object soundO = raw.get("sound");
            if (!(minO instanceof Number min) || !(maxO instanceof Number max) || !(soundO instanceof String sound)) {
                problems.add(context + ": voice 缺少 min/max/sound，已跳过");
                continue;
            }
            double basePitch = raw.get("base-pitch") instanceof Number n ? n.doubleValue() : 1.0;
            boolean fixed = raw.get("fixed") instanceof Boolean b && b;
            String label = raw.get("label") instanceof String s && !s.isBlank() ? s.trim() : null;
            result.add(new Instrument.Voice(min.intValue(), max.intValue(), sound, basePitch, fixed, label));
        }
        return result;
    }

    public Instrument byKey(String key) {
        return byKey.get(key);
    }

    public Instrument byId(int id) {
        if (id < 0 || id >= ordered.size()) {
            return null;
        }
        return ordered.get(id);
    }

    public List<Instrument> all() {
        return List.copyOf(ordered);
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** 生成 chord:sync 下发载荷。 */
    public SyncPayload toSyncPayload(int protocolVersion) {
        List<InstrumentDef> defs = new ArrayList<>(ordered.size());
        for (Instrument instrument : ordered) {
            defs.add(new InstrumentDef(instrument.id, instrument.key, instrument.displayName,
                    instrument.minSemitone, instrument.maxSemitone,
                    instrument.releaseDecay(), instrument.sustainPedal(),
                    labelRules(instrument)));
        }
        return new SyncPayload(protocolVersion, defs);
    }

    /** 生成 chord:poly 复音容量表（sendSync 后紧跟下发；未注册该频道的旧客户端静默忽略）。 */
    public PolyPayload toPolyPayload() {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (Instrument instrument : ordered) {
            map.put(instrument.id, instrument.maxPolyphony);
        }
        return new PolyPayload(map);
    }

    /** voices 的 label 转成下发标签规则；相邻同名区间合并（鼓件周期布局可 36 行并成 12 规则）。 */
    private static List<InstrumentDef.LabelRule> labelRules(Instrument instrument) {
        List<InstrumentDef.LabelRule> rules = new ArrayList<>();
        for (Instrument.Voice voice : instrument.voices) {
            if (voice.label() == null) {
                continue;
            }
            InstrumentDef.LabelRule last = rules.isEmpty() ? null : rules.get(rules.size() - 1);
            if (last != null && last.max() + 1 == voice.min() && last.label().equals(voice.label())) {
                rules.set(rules.size() - 1, new InstrumentDef.LabelRule(last.min(), voice.max(), last.label()));
            } else {
                rules.add(new InstrumentDef.LabelRule(voice.min(), voice.max(), voice.label()));
            }
        }
        return rules;
    }
}
