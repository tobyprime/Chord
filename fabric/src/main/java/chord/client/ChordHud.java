package chord.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * 演奏 HUD（26.x HudElement 渲染态提取模型）：
 * 演奏态显示乐器名、最近按下的音名（淡出）与退出提示；
 * 手持乐器但服务器未启用 Chord 时给出提示。
 */
public final class ChordHud {

    private ChordHud() {}

    public static void init() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("chord", "play_overlay"),
                ChordHud::render);
    }

    private static void render(GuiGraphicsExtractor gui, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        boolean holding = PlayState.isInstrument(mc.player.getMainHandItem());

        if (PlayState.isPlaying()) {
            var instrument = PlayState.currentInstrument();
            String title = instrument == null ? "Chord" : instrument.displayName + " · 演奏中";
            gui.centeredText(mc.font, title, gui.guiWidth() / 2, gui.guiHeight() - 70, 0xFFFFE082);
            String held = PlayState.heldNotesLabel();
            if (!held.isEmpty()) {
                gui.centeredText(mc.font, held, gui.guiWidth() / 2, gui.guiHeight() - 82, 0xFFA5D6A7);
            }
            // 自动演奏进度 / MIDI 中心入口提示
            if (PlayState.isAutoPlaying()) {
                gui.centeredText(mc.font, PlayState.autoProgressLabel(), gui.guiWidth() / 2, gui.guiHeight() - 94, 0xFFB39DDB);
            } else {
                gui.centeredText(mc.font, PlayState.midiCenterHintLabel(), gui.guiWidth() / 2, gui.guiHeight() - 94, 0xFF9E9E9E);
            }
            drawFlashes(gui, mc);
            String hint = PlayState.isWorldMode() ? "Shift / 走远 退出" : "Shift / 切换快捷栏 退出";
            String pedalName = PlayState.pedalKeyName();
            if (PlayState.sustainPedalMode()) {
                // 钢琴：踏板键是延音踏板；双击切延音模式（松键默认延音、按住临时止音）
                hint += PlayState.pedalHeld() ? " · ♪ 延音中"
                        : (PlayState.pedalLatched() ? " · ♪♪ 延音模式" : " · " + pedalName + " 延音");
            } else if (PlayState.decayRelease()) {
                hint += " · " + pedalName + " 闷音";
            }
            if (PlayState.octaveLatch() != 0) {
                hint += PlayState.octaveLatch() > 0 ? " · ♯ 高八度锁" : " · ♭ 低八度锁";
            }
            if (PlayState.velocityLevel() != 100) {
                hint += " · 力度 " + PlayState.velocityLevel() + " " + PlayState.dynLabel(PlayState.velocityLevel());
            }
            String midi = PlayState.midiDeviceName();
            if (midi != null) {
                hint += " · MIDI " + (midi.length() > 14 ? midi.substring(0, 13) + "…" : midi);
            }
            boolean active = PlayState.pedalHeld() || PlayState.octaveLatch() != 0 || midi != null;
            gui.centeredText(mc.font, hint, gui.guiWidth() / 2, gui.guiHeight() - 58,
                    active ? 0xFFA5D6A7 : 0xFF9E9E9E);
        } else if (holding && !PlayState.serverSupported()) {
            gui.centeredText(mc.font, "服务器未启用 Chord，无法演奏", gui.guiWidth() / 2, gui.guiHeight() - 70, 0xFFFF8A65);
        }
    }

    private static void drawFlashes(GuiGraphicsExtractor gui, Minecraft mc) {
        PlayState.pruneFlashes();
        int i = 0;
        var it = PlayState.flashes();
        while (it.hasNext()) {
            PlayState.Flash flash = it.next();
            long age = System.currentTimeMillis() - flash.at;
            int alpha = (int) (255 * (1.0 - (double) age / PlayState.FLASH_MS));
            if (alpha <= 0) {
                continue;
            }
            int base = flash.warn ? 0xFFEF5350 : 0xFFFFFFFF;
            int color = (alpha << 24) | (base & 0xFFFFFF);
            gui.centeredText(mc.font, flash.text, gui.guiWidth() / 2, gui.guiHeight() - 106 - i * 12, color);
            i++;
        }
    }
}
