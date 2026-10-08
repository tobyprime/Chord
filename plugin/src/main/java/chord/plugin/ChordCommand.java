package chord.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * /chord 命令：give / piano / reload / mute / instruments。
 * （0.7.11 移除服务端 MIDI 导入成书与演奏录制；0.7.15 客户端乐谱书播放亦移除——
 * MIDI 播放全在客户端 .mid 现转现播，服务端无任何谱相关功能。）
 */
public final class ChordCommand implements CommandExecutor, TabCompleter {

    private final ChordPlugin plugin;

    public ChordCommand(ChordPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "instruments" -> {
                sender.sendMessage("§6Chord §7乐器表：");
                for (Instrument instrument : plugin.instruments().all()) {
                    String form = instrument.entry == Instrument.Entry.WORLD
                            ? "世界装置，右键交互实体" : instrument.baseItem + "，手持";
                    sender.sendMessage("§e" + instrument.key + "§7 = " + instrument.displayName
                            + " §8[" + (instrument.entry == Instrument.Entry.WORLD ? "world" : "item") + "]"
                            + "§7（" + form + "，半音 " + instrument.minSemitone
                            + "~" + instrument.maxSemitone + "）");
                }
                sender.sendMessage("§8自检：钢琴应为 半音 -39~48（A0~C8 全域，0.7.4 起）；显示 -24~32 或 -12~25 = 服务器 instruments.yml 是旧版需整文件替换；"
                        + "音域显示正常但两端八度（低低1(-24) 以下 / 高高8(+32) 以上）无声 = 客户端资源包是旧版（0.7.4 前的包只到 -24~+32），换发最新 chord.zip");
            }
            case "give" -> {
                if (!sender.hasPermission("chord.admin")) {
                    sender.sendMessage("§c需要 chord.admin 权限");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /chord give <乐器> [玩家]");
                    return true;
                }
                Instrument instrument = plugin.instruments().byKey(args[1].toLowerCase(java.util.Locale.ROOT));
                if (instrument == null) {
                    sender.sendMessage("§c未知乐器: " + args[1] + "（/chord instruments 查看）");
                    return true;
                }
                if (instrument.entry != Instrument.Entry.ITEM) {
                    sender.sendMessage("§c" + instrument.displayName + " 是世界装置（entry: world），用 /chord piano spawn " + instrument.key + " 放置");
                    return true;
                }
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : requirePlayer(sender);
                if (target == null) {
                    sender.sendMessage("§c找不到目标玩家");
                    return true;
                }
                giveInstrument(target, instrument);
                sender.sendMessage("§a已把 " + instrument.displayName + " 交给 " + target.getName());
            }
            case "piano" -> {
                if (args.length >= 2 && List.of("bind", "unbind", "inspect").contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    if (!sender.hasPermission("chord.piano")) {
                        sender.sendMessage("§c需要 chord.piano 权限");
                        return true;
                    }
                } else if (!sender.hasPermission("chord.admin")) {
                    sender.sendMessage("§c需要 chord.admin 权限");
                    return true;
                }
                handlePiano(sender, args);
            }
            case "reload" -> {
                if (!sender.hasPermission("chord.admin")) {
                    sender.sendMessage("§c需要 chord.admin 权限");
                    return true;
                }
                plugin.reload();
                String ver = plugin.getPluginMeta().getVersion();
                long tail8 = plugin.instruments().all().stream()
                        .anyMatch(i -> i.releaseVoices.stream().anyMatch(rv -> rv.sounds().size() > 3)) ? 1 : 0;
                int relGrades = plugin.instruments().all().stream()
                        .mapToInt(i -> i.releaseVoices.stream().mapToInt(rv -> rv.sounds().size()).sum()).sum();
                sender.sendMessage("§aChord 配置已重载 §7(jar " + ver
                        + (tail8 == 1 ? "§a，等dB尾音+电平补偿，rel " + relGrades + " 档§7"
                                      : "§e，旧尾音路由（jar/yml/zip 必须同期更换，错位=无声或重击）§7")
                        + "） §7（" + instrumentSummary() + "§7）");
                for (String problem : plugin.instruments().problems()) {
                    sender.sendMessage("§e⚠ " + problem);
                }
            }
            case "mute" -> {
                if (!sender.hasPermission("chord.mute")) {
                    sender.sendMessage("§c需要 chord.mute 权限");
                    return true;
                }
                if (!(sender instanceof Player listener)) {
                    sender.sendMessage("§c只有玩家能执行此命令");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /chord mute <玩家>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§c目标玩家不在线（V1 仅支持屏蔽在线玩家）");
                    return true;
                }
                boolean nowMuted = plugin.mutes().toggle(listener.getUniqueId(), target.getUniqueId());
                sender.sendMessage(nowMuted
                        ? "§a已屏蔽 " + target.getName() + " 的演奏"
                        : "§7已取消屏蔽 " + target.getName());
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    /** 已加载乐器一览（键 + entry 类型），给 reload/spawn 的自诊断输出。 */
    private String instrumentSummary() {
        StringBuilder sb = new StringBuilder();
        for (Instrument instrument : plugin.instruments().all()) {
            if (!sb.isEmpty()) {
                sb.append("§7, ");
            }
            sb.append("§e").append(instrument.key).append("§8[")
                    .append(instrument.entry == Instrument.Entry.WORLD ? "world" : "item").append("§8]");
        }
        return sb.isEmpty() ? "§c（一个都没加载，instruments.yml 有问题）" : sb.toString();
    }

    /** 玩家视线/身边最近的交互实体（bind 目标选择）。 */
    private Interaction nearestInteraction(Player player, double radius) {
        Interaction best = null;
        double bestSq = radius * radius;
        var eye = player.getEyeLocation();
        for (var entity : player.getWorld().getNearbyEntities(player.getLocation(), radius, radius, radius,
                e -> e instanceof Interaction)) {
            double dSq = entity.getLocation().distanceSquared(eye);
            if (dSq <= bestSq) {
                bestSq = dSq;
                best = (Interaction) entity;
            }
        }
        return best;
    }

    /** /chord piano spawn|bind|unbind|remove|list —— 世界乐器装置管理。 */
    private void handlePiano(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只有玩家能执行（以准星位置放置）");
                    return;
                }
                // /chord piano spawn [乐器]：多台世界乐器（钢琴/架子鼓…）时指定键；
                // 省略时若只有一台世界乐器直接用，多台则列出可选
                java.util.List<Instrument> worldInstruments = plugin.instruments().all().stream()
                        .filter(i -> i.entry == Instrument.Entry.WORLD).toList();
                Instrument target = null;
                if (args.length >= 3) {
                    target = plugin.instruments().byKey(args[2].toLowerCase(java.util.Locale.ROOT));
                } else if (worldInstruments.size() == 1) {
                    target = worldInstruments.get(0);
                }
                if (target == null || target.entry != Instrument.Entry.WORLD) {
                    if (args.length >= 3) {
                        sender.sendMessage("§c找不到 entry: world 的乐器: " + args[2]);
                    }
                    sender.sendMessage(worldInstruments.isEmpty()
                            ? "§cinstruments.yml 未加载到 entry: world 的乐器。当前已加载：" + instrumentSummary()
                            : "§c请指定乐器键: /chord piano spawn <" + worldInstruments.stream()
                                    .map(i -> i.key).reduce((a, b) -> a + "|" + b).orElse("") + ">");
                    if (worldInstruments.isEmpty()) {
                        sender.sendMessage("§c排查：① plugins/Chord/instruments.yml 是否含 entry: world 的段落"
                                + " ② 改完是否 /chord reload ③ 插件 jar 是否为最新版（旧版不认识 entry 键，"
                                + "钢琴会被当成手持乐器加载，spawn 就找不到）");
                    }
                    return;
                }
                var block = player.getTargetBlockExact(8);
                var loc = block != null
                        ? block.getLocation().add(0.5, 1.0, 0.5)
                        : player.getLocation().add(0, 1, 0);
                var world = player.getWorld();
                final Instrument placed = target;
                world.spawn(loc, Interaction.class, interaction -> {
                    float width = "drums".equals(placed.key) ? 2.2f : 1.6f;
                    interaction.setInteractionWidth(width);
                    interaction.setInteractionHeight(1.4f);
                    interaction.setPersistent(true);
                    interaction.getPersistentDataContainer().set(
                            plugin.instrumentKey(), org.bukkit.persistence.PersistentDataType.STRING, placed.key);
                });
                sender.sendMessage("§a已在 " + world.getName() + " " + loc.getBlockX() + "," + loc.getBlockY()
                        + "," + loc.getBlockZ() + " 放置 " + target.displayName
                        + "§a（对它右键进入演奏，实体不可见，可配装饰方块；CraftEngine 家具请改用 world-bind 声明式绑定）");
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只有玩家能执行（移除附近的乐器装置）");
                    return;
                }
                var nearby = player.getWorld().getNearbyEntities(player.getLocation(), 5, 5, 5,
                        e -> e instanceof Interaction i && i.getPersistentDataContainer()
                                .has(plugin.instrumentKey(), org.bukkit.persistence.PersistentDataType.STRING));
                if (nearby.isEmpty()) {
                    sender.sendMessage("§c5 格内没有 Chord 世界乐器");
                    return;
                }
                int removed = 0;
                for (var entity : nearby) {
                    entity.remove();
                    removed++;
                }
                sender.sendMessage("§a已移除 " + removed + " 台世界乐器§7（仅限 /chord piano spawn 生成的实体；CraftEngine 家具请用 unbind）");
            }
            case "list" -> {
                var sessions = plugin.playListener().worldSessions();
                sender.sendMessage("§6世界乐器演奏会话 §7共 " + sessions.size() + " 个：");
                sessions.forEach((playerId, session) -> {
                    var performer = Bukkit.getPlayer(playerId);
                    Instrument inst = plugin.instruments().byId(session.instrumentId());
                    String where;
                    var anchorEntity = session.entity() != null ? Bukkit.getEntity(session.entity()) : null;
                    if (anchorEntity != null) {
                        var loc = anchorEntity.getLocation();
                        where = "§8" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + "（实体）";
                    } else if (session.anchor() != null) {
                        var loc = session.anchor();
                        where = "§8" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + "（家具）";
                    } else {
                        where = "§8锚点已失效";
                    }
                    sender.sendMessage("§7- " + (performer != null ? performer.getName() : playerId)
                            + " §7→ " + (inst != null ? inst.displayName : "#" + session.instrumentId()) + " " + where);
                });
            }
            case "inspect" -> {
                // 调试/配置入口：打印身边最近交互实体的标识（PDC 值/名称）与 Chord 解析结果，
                // 用于把 CraftEngine 等家具插件的实际标识填入 instruments.yml 的 world-bind
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只有玩家能执行");
                    return;
                }
                Interaction hit = nearestInteraction(player, 5.0);
                if (hit == null) {
                    sender.sendMessage("§c5 格内没有交互实体");
                    return;
                }
                sender.sendMessage("§6== 交互实体 ==");
                sender.sendMessage("§7UUID: §f" + hit.getUniqueId());
                var name = hit.customName();
                sender.sendMessage("§7名称: §f" + (name == null ? "无"
                        : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText().serialize(name)));
                var pdc = hit.getPersistentDataContainer();
                var keys = pdc.getKeys();
                sender.sendMessage("§7PDC 条目（" + keys.size() + "）:");
                for (var key : keys) {
                    String value;
                    try {
                        value = pdc.get(key, org.bukkit.persistence.PersistentDataType.STRING);
                    } catch (Exception e) {
                        value = null;
                    }
                    sender.sendMessage("§8  " + key + " §7= §f" + (value != null ? value : "<非字符串>"));
                }
                Instrument resolved = plugin.resolveWorldInstrument(hit);
                sender.sendMessage(resolved != null
                        ? "§aChord 解析: → " + resolved.key
                        : "§7Chord 解析: 无匹配 §8（把上面的某个值填入 instruments.yml 的 world-bind.match-values，/chord reload 生效）");
            }
            case "bind" -> {
                // 兜底：把准星附近最近的交互实体手动打上 chord 标记（无声明式标识的实体用）
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只有玩家能执行（以身边最近的交互实体为目标）");
                    return;
                }
                Instrument target = args.length >= 3
                        ? plugin.instruments().byKey(args[2].toLowerCase(java.util.Locale.ROOT))
                        : plugin.instruments().all().stream()
                                .filter(i -> i.entry == Instrument.Entry.WORLD).findFirst().orElse(null);
                if (target == null || target.entry != Instrument.Entry.WORLD) {
                    sender.sendMessage("§c找不到 entry: world 的乐器" + (args.length >= 3 ? ": " + args[2] : ""));
                    return;
                }
                Interaction hit = nearestInteraction(player, 3.0);
                if (hit == null) {
                    sender.sendMessage("§c3 格内没有交互实体（对准家具命中箱再执行）");
                    return;
                }
                var pdc = hit.getPersistentDataContainer();
                String existing = pdc.get(plugin.instrumentKey(), org.bukkit.persistence.PersistentDataType.STRING);
                pdc.set(plugin.instrumentKey(), org.bukkit.persistence.PersistentDataType.STRING, target.key);
                sender.sendMessage(target.key.equals(existing)
                        ? "§7该实体已绑定 " + target.displayName + "§7，无变化"
                        : "§a已把身边的交互实体绑定为 " + target.displayName + "§a，右键即可演奏");
            }
            case "unbind" -> {
                // 兜底：去掉 chord 标记（保留实体本身——实体可能归 CraftEngine 等插件所有）
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c只有玩家能执行");
                    return;
                }
                var nearby = player.getWorld().getNearbyEntities(player.getLocation(), 5, 5, 5,
                        e -> e instanceof Interaction i && i.getPersistentDataContainer()
                                .has(plugin.instrumentKey(), org.bukkit.persistence.PersistentDataType.STRING));
                if (nearby.isEmpty()) {
                    sender.sendMessage("§c5 格内没有手动绑定（chord 标记）的世界乐器");
                    return;
                }
                for (var entity : nearby) {
                    entity.getPersistentDataContainer().remove(plugin.instrumentKey());
                }
                sender.sendMessage("§a已解除 " + nearby.size() + " 台世界乐器的手动绑定（实体保留，家具不受影响）");
            }
            default -> sender.sendMessage("§c用法: /chord piano <spawn|bind|unbind|inspect|list>");
        }
    }

    private void giveInstrument(Player target, Instrument instrument) {
        ItemStack item = new ItemStack(instrument.baseItem);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§r" + instrument.displayName);
        meta.setLore(List.of("§7Chord · " + instrument.key, "§8右键举起演奏"));
        meta.getPersistentDataContainer().set(
                plugin.instrumentKey(), org.bukkit.persistence.PersistentDataType.STRING, instrument.key);
        item.setItemMeta(meta);
        // Folia：目标玩家可能不在当前区域线程，经其实体调度器入包
        target.getScheduler().run(plugin, task -> {
            var leftover = target.getInventory().addItem(item);
            leftover.values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
            target.sendMessage("§a你获得了 " + instrument.displayName + "§a，手持并右键举起演奏（需安装 Chord 模组）");
        }, null);
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("§c控制台必须指定玩家: /chord give <乐器> <玩家>");
        return null;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6Chord §7- /chord instruments | give <乐器> [玩家] | piano <spawn [乐器]|remove|list> | reload | mute <玩家>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("instruments", "give", "piano", "reload", "mute")) {
                if (sub.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("piano")) {
            for (String sub : List.of("spawn", "bind", "unbind", "inspect", "remove", "list")) {
                if (sub.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(sub);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("piano")
                && (args[1].equalsIgnoreCase("bind") || args[1].equalsIgnoreCase("spawn"))) {
            for (Instrument instrument : plugin.instruments().all()) {
                if (instrument.entry == Instrument.Entry.WORLD
                        && instrument.key.startsWith(args[2].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(instrument.key);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Instrument instrument : plugin.instruments().all()) {
                if (instrument.key.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(instrument.key);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mute")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(java.util.Locale.ROOT).startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(player.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(java.util.Locale.ROOT).startsWith(args[2].toLowerCase(java.util.Locale.ROOT))) {
                    result.add(player.getName());
                }
            }
        }
        return result;
    }
}
