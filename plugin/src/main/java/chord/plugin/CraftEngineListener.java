package chord.plugin;

import chord.protocol.ChordChannels;
import chord.protocol.EnterPayload;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * CraftEngine 家具接入（软依赖，检测到 CE 才注册）。
 *
 * <p>关键背景：CE 的 {@code type: interaction} 命中箱是**包级虚拟实体**——服务端没有
 * 对应 Bukkit 实体（无 PDC、PlayerInteractEntityEvent 不触发），右键由 CE 的包监听器
 * 截获并先触发 {@link FurnitureInteractEvent}。因此接入方式是监听该事件：
 * 家具 id（如 "example:player_piano"）匹配 instruments.yml 的 world-bind → 开演奏会话
 * （固定位置锚点）→ 下发 chord:enter；取消事件以接管右键（CE 配置里的占位行为不再跑）。
 *
 * <p>装了 Chord 模组的客户端进入演奏态；原版客户端收到安装提示。
 * 未匹配 world-bind 的家具不受任何影响。
 */
public final class CraftEngineListener implements Listener {

    private final ChordPlugin plugin;

    public CraftEngineListener(ChordPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onFurnitureInteract(FurnitureInteractEvent event) {
        if (event.hand() != InteractionHand.MAIN_HAND) {
            return; // 主手交互为准（避免一次右键触发两次）
        }
        String furnitureId = event.furniture().id().toString();
        Instrument instrument = plugin.resolveWorldInstrumentById(furnitureId);
        if (instrument == null) {
            return; // 不是 Chord 世界乐器：不碰 CE 的正常行为
        }
        event.setCancelled(true); // Chord 接管该家具右键（CE 的 right_click 动作跳过）

        Player player = event.player();
        var loc = event.location();
        // 判定客户端装没装 Chord 模组：看 REGISTER 上报的 S2C 频道（sync/enter）
        var listening = player.getListeningPluginChannels();
        if (listening.contains(ChordChannels.SYNC) || listening.contains(ChordChannels.ENTER)) {
            // 演奏态内按住右键（八度修饰键）时原版会每 4 tick 重发家具交互：
            // 同一台琴 1 秒内只发一次 ENTER（客户端对重复 ENTER 也做了幂等忽略）
            if (!plugin.playListener().enterRecentlySent(player, instrument.id, loc)) {
                plugin.playListener().openWorldAnchorSession(
                        player.getUniqueId(), loc, instrument.id);
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
