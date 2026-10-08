package chord.plugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 聆听者级屏蔽：muter 不再听到 muted 的演奏。持久化到 mutes.yml。
 */
public final class MuteStore {

    private final JavaPlugin plugin;
    private final Map<UUID, Set<UUID>> mutes = new HashMap<>();

    public MuteStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        mutes.clear();
        File file = new File(plugin.getDataFolder(), "mutes.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("mutes");
        if (section == null) {
            return;
        }
        for (String muterRaw : section.getKeys(false)) {
            try {
                UUID muter = UUID.fromString(muterRaw);
                List<String> mutedRaw = section.getStringList(muterRaw);
                Set<UUID> muted = new HashSet<>();
                for (String raw : mutedRaw) {
                    try {
                        muted.add(UUID.fromString(raw));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                mutes.put(muter, muted);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : mutes.entrySet()) {
            yaml.set("mutes." + entry.getKey(), entry.getValue().stream().map(UUID::toString).toList());
        }
        try {
            yaml.save(new File(plugin.getDataFolder(), "mutes.yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("保存 mutes.yml 失败: " + e.getMessage());
        }
    }

    /** @return true = 切换后处于屏蔽状态 */
    public boolean toggle(UUID muter, UUID muted) {
        Set<UUID> set = mutes.computeIfAbsent(muter, k -> new HashSet<>());
        boolean nowMuted = set.add(muted);
        if (!nowMuted) {
            set.remove(muted);
            if (set.isEmpty()) {
                mutes.remove(muter);
            }
        }
        save();
        return nowMuted;
    }

    public boolean isMuted(UUID listener, UUID performer) {
        Set<UUID> set = mutes.get(listener);
        return set != null && set.contains(performer);
    }
}
