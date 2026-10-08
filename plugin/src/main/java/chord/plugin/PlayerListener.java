package chord.plugin;

import chord.protocol.ChordChannels;
import chord.protocol.EnterPayload;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 玩家事件：握手时机（入服 + 客户端注册频道）、离线清理、
 * 以及非演奏者手持 Chord 乐器时的原生交互兜底取消（防山羊角原生吹奏）。
 */
public final class PlayerListener implements Listener {

    private final ChordPlugin plugin;

    public PlayerListener(ChordPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // 兜底路径：多数情况下此刻客户端还没上报 REGISTER，本次发送会被静默忽略；
        // 真正生效的是 onRegisterChannel（客户端上报可接收频道时）
        plugin.sendSync(event.getPlayer());
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        // 装了 Mod 的客户端进入 PLAY 阶段会上报它可接收的 S2C 频道（chord:sync / chord:enter）
        // ——此刻下发乐器表才是可靠时机（比 PlayerJoinEvent 晚一个 RTT，但频道已就绪）
        String channel = event.getChannel();
        if (ChordChannels.SYNC.equals(channel) || ChordChannels.ENTER.equals(channel)) {
            plugin.sendSync(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.playListener().forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String key = pdc.get(plugin.instrumentKey(), PersistentDataType.STRING);
        if (key != null) {
            event.setCancelled(true);
        }
    }

    /**
     * 世界乐器入口：右键交互实体（如钢琴）。
     * 识别在服务端（ChordPlugin.resolveWorldInstrument：chord 标记优先，声明式 world-bind 匹配
     * CraftEngine 等家具插件自带的命中箱标识），客户端不需要感知组件——
     * 装了 Mod 的玩家收到 chord:enter 进入演奏态，原版玩家收到提示。
     */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction)) {
            return;
        }
        Instrument instrument = plugin.resolveWorldInstrument(interaction);
        if (instrument == null) {
            return;
        }
        Player player = event.getPlayer();
        // 判定客户端装没装 Chord 模组：看它 REGISTER 上报的「可接收」(S2C) 频道。
        // 不能查 chord:play——那是 C2S 频道，永远不会出现在监听列表里
        //（与 onRegisterChannel 的握手时机同理：上报的是 sync/enter）。
        var listening = player.getListeningPluginChannels();
        if (listening.contains(ChordChannels.SYNC) || listening.contains(ChordChannels.ENTER)) {
            // 装了 Chord 模组的客户端：开演奏会话 + 下发锚点进入演奏态。
            // 演奏态内按住右键（八度修饰键）时原版对 Interaction 实体每 4 tick 重发交互：
            // 同一实体 1 秒内只发一次 ENTER（客户端对重复 ENTER 也做了幂等忽略）
            var loc = interaction.getLocation();
            if (!plugin.playListener().enterRecentlySent(player, instrument.id, loc)) {
                plugin.playListener().openWorldSession(player.getUniqueId(),
                        interaction.getUniqueId(), instrument.id);
                player.sendPluginMessage(plugin, ChordChannels.ENTER, new EnterPayload(
                        EnterPayload.ACTION_ENTER, instrument.id,
                        loc.getX(), loc.getY(), loc.getZ()).toBytes());
            }
        } else {
            player.sendMessage("§7这是一架 " + instrument.displayName
                    + "§7，演奏需要安装 §eChord §7模组");
        }
    }
}
