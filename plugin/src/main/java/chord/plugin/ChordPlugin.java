package chord.plugin;

import chord.protocol.ChordChannels;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Chord 插件主类（Folia 兼容：不使用任何全局调度器）。
 */
public final class ChordPlugin extends JavaPlugin {

    private InstrumentRegistry instruments;
    private MuteStore mutes;
    private PlayMessageListener playListener;
    private NamespacedKey instrumentKey;
    private NamespacedKey scoreKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        instruments = new InstrumentRegistry(this);
        instruments.load();
        for (String problem : instruments.problems()) {
            getLogger().warning("instruments.yml: " + problem);
        }

        mutes = new MuteStore(this);
        mutes.load();

        playListener = new PlayMessageListener(this);
        instrumentKey = new NamespacedKey(this, "instrument");
        scoreKey = new NamespacedKey(this, "score");

        getServer().getMessenger().registerIncomingPluginChannel(this, ChordChannels.PLAY, playListener);
        getServer().getMessenger().registerIncomingPluginChannel(this, ChordChannels.AUTO, playListener);
        getServer().getMessenger().registerIncomingPluginChannel(this, ChordChannels.PEDAL, playListener);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ChordChannels.SYNC);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ChordChannels.ENTER);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ChordChannels.POLY);

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // CraftEngine 软依赖：装了 CE 才注册家具事件接入（world-bind 匹配家具 id）
        if (getServer().getPluginManager().getPlugin("CraftEngine") != null) {
            getServer().getPluginManager().registerEvents(new CraftEngineListener(this), this);
            getLogger().info("检测到 CraftEngine：家具世界乐器接入已启用（world-bind 匹配）");
        }

        PluginCommand command = getCommand("chord");
        if (command != null) {
            ChordCommand executor = new ChordCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("Chord 启用：乐器 " + instruments.all().size()
                + " 个，频道 " + ChordChannels.PLAY + " / " + ChordChannels.SYNC);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    public void reload() {
        reloadConfig();
        instruments.load();
        for (String problem : instruments.problems()) {
            getLogger().warning("instruments.yml: " + problem);
        }
        playListener = new PlayMessageListener(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, ChordChannels.PLAY);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, ChordChannels.AUTO);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, ChordChannels.PEDAL);
        getServer().getMessenger().registerIncomingPluginChannel(this, ChordChannels.PLAY, playListener);
        getServer().getMessenger().registerIncomingPluginChannel(this, ChordChannels.AUTO, playListener);
        getServer().getMessenger().registerIncomingPluginChannel(this, ChordChannels.PEDAL, playListener);
        for (Player player : getServer().getOnlinePlayers()) {
            sendSync(player);
        }
    }

    /** 下发乐器表（仅对注册了频道的客户端实际生效，原版客户端自动忽略）。 */
    public void sendSync(Player player) {
        player.sendPluginMessage(this, ChordChannels.SYNC,
                instruments.toSyncPayload(chord.protocol.ChordChannels.PROTOCOL_VERSION).toBytes());
        // 复音容量表随乐器表一并下发；旧客户端（≤0.7.4）未注册 chord:poly，静默忽略
        player.sendPluginMessage(this, ChordChannels.POLY, instruments.toPolyPayload().toBytes());
    }

    public boolean holdingInstrument(Player player, Instrument instrument) {
        var item = player.getInventory().getItemInMainHand();
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        String key = item.getItemMeta().getPersistentDataContainer()
                .get(instrumentKey, org.bukkit.persistence.PersistentDataType.STRING);
        return instrument.key.equals(key);
    }

    public InstrumentRegistry instruments() {
        return instruments;
    }

    public MuteStore mutes() {
        return mutes;
    }

    public PlayMessageListener playListener() {
        return playListener;
    }

    public NamespacedKey instrumentKey() {
        return instrumentKey;
    }

    public NamespacedKey scoreKey() {
        return scoreKey;
    }

    // ---- 世界乐器识别（声明式：先看 chord 标记，再按 PDC 值/名称匹配 world-bind） ----

    /**
     * 解析交互实体对应的世界乐器（右键进入与 /chord piano inspect 共用）。
     *
     * <p>识别顺序：① 实体 PDC 的 chord:instrument 标记（spawn/bind 命令打的）；
     * ② 收集实体全部 PDC 字符串值与自定义名，逐一与各乐器的 world-bind
     * （match-values / match-regex）匹配——CraftEngine 等家具插件在命中箱上
     * 自带家具标识，命中即视为该乐器，无需任何逐台绑定。
     */
    public Instrument resolveWorldInstrument(Interaction interaction) {
        String tag = interaction.getPersistentDataContainer()
                .get(instrumentKey, PersistentDataType.STRING);
        if (tag != null) {
            Instrument byTag = instruments.byKey(tag);
            if (byTag != null && byTag.entry == Instrument.Entry.WORLD) {
                return byTag;
            }
        }
        for (String marker : worldBindMarkers(interaction)) {
            for (Instrument instrument : instruments.all()) {
                if (instrument.entry == Instrument.Entry.WORLD && instrument.matchesWorldBind(marker)) {
                    return instrument;
                }
            }
        }
        return null;
    }

    /**
     * 按 CraftEngine 家具 id 解析世界乐器（FurnitureInteractEvent 接入用）：
     * id（如 "example:player_piano"）命中 world-bind 的 match-values / match-regex 即该乐器。
     */
    public Instrument resolveWorldInstrumentById(String furnitureId) {
        for (Instrument instrument : instruments.all()) {
            if (instrument.entry == Instrument.Entry.WORLD && instrument.matchesWorldBind(furnitureId)) {
                return instrument;
            }
        }
        return null;
    }

    /** 收集实体上可供 world-bind 匹配的标识串：全部 PDC 字符串值 + 自定义名纯文本。 */
    public List<String> worldBindMarkers(Interaction interaction) {
        List<String> markers = new ArrayList<>();
        var pdc = interaction.getPersistentDataContainer();
        for (var key : pdc.getKeys()) {
            try {
                String value = pdc.get(key, PersistentDataType.STRING);
                if (value != null && !value.isEmpty()) {
                    markers.add(value);
                }
            } catch (Exception ignored) {
                // 非 STRING 类型，跳过
            }
        }
        var name = interaction.customName();
        if (name != null) {
            String plain = PlainTextComponentSerializer.plainText().serialize(name);
            if (!plain.isEmpty()) {
                markers.add(plain);
            }
        }
        return markers;
    }
}
