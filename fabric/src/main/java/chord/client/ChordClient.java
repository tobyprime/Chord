package chord.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Chord 客户端入口。
 *
 * <p>0.7.0 起演奏输入完全自管：物理键、半音、显示名、功能键（自动演奏/踏板/高低八度锁）
 * 全部在「演奏配置」页（默认 J 打开）配置，存 chord.json，按 GLFW 键码在演奏态内
 * 直接匹配——不再注册原版按键设置条目（唯一保留的是打开配置页的 J 本身）。
 * 演奏态内按键在分发源头被拦截消费，天然不与其他 Mod 的绑定冲突。
 */
public final class ChordClient implements ClientModInitializer {

    /** 当前配置（演奏配置页可改写并热应用）。 */
    static ChordConfig config;

    /** 演奏配置页入口键（默认 J；唯一保留的原版按键设置条目）。 */
    public static KeyMapping configKey;

    public static final KeyMapping.Category CHORD_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("chord", "instrument"));

    @Override
    public void onInitializeClient() {
        ChordPayloads.init();

        config = ChordConfig.load();
        applyInputConfig();
        MidiInput.init(); // 实时 MIDI：按 chord.json 连接 + 周期重扫（插拔自动回连）

        // 演奏配置页入口（默认 J，任意时刻可开；正在演奏时打开会照常松开按住的音）
        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chord.config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                CHORD_CATEGORY));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    while (configKey.consumeClick()) {
                        client.setScreenAndShow(new ChordConfigScreen(config));
                    }
                });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(PlayState::tick);

        // 右键举起：手持 Chord 乐器时接管使用键（阻止山羊角原生吹奏）；
        // 演奏态内（含钢琴等世界乐器）右键是「升八度」修饰键，一律阻止使用。
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (PlayState.isPlaying()) {
                return InteractionResult.FAIL;
            }
            if (!PlayState.isInstrument(player.getItemInHand(hand))) {
                return InteractionResult.PASS;
            }
            boolean entered = PlayState.tryEnter(Minecraft.getInstance());
            return entered ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        });

        ChordHud.init();
    }

    /** 把 chord.json 的输入配置应用到演奏态（启动与配置页保存共用）。 */
    static void applyInputConfig() {
        PlayState.initSlots(config.slots);
        PlayState.setAutoKey(keyCodeOf(config.autoKey, GLFW.GLFW_KEY_G));
        PlayState.setPedalKey(keyCodeOf(config.pedalKey, GLFW.GLFW_KEY_K));
        PlayState.setLatchKeys(keyCodeOf(config.latchHighKey, GLFW.GLFW_KEY_I),
                keyCodeOf(config.latchLowKey, GLFW.GLFW_KEY_U));
    }

    /** 演奏配置页保存：写入 chord.json 并立即生效（正在演奏先退出演奏态）。 */
    static void applyConfig(List<ChordConfig.Slot> slots, String autoKey, String pedalKey,
                            String latchHighKey, String latchLowKey) {
        config.slots = slots;
        config.autoKey = autoKey;
        config.pedalKey = pedalKey;
        config.latchHighKey = latchHighKey;
        config.latchLowKey = latchLowKey;
        config.save();
        if (PlayState.isPlaying()) {
            PlayState.exit();
        }
        applyInputConfig();
    }

    /** 键名（"key.keyboard.z"）→ GLFW 键码；空/非法回落默认值。 */
    static int keyCodeOf(String keyName, int fallback) {
        if (keyName == null || keyName.isBlank()) {
            return fallback;
        }
        try {
            int value = InputConstants.getKey(keyName).getValue();
            return value == InputConstants.UNKNOWN.getValue() ? fallback : value;
        } catch (Exception e) {
            return fallback;
        }
    }
}
