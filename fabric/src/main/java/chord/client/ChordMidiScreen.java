package chord.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 客户端 MIDI 中心（演奏态内按自动演奏键打开，默认 G；0.7.15 起唯一入口）。
 *
 * <p>上方是实时 MIDI 输入设备选择（数字钢琴/MIDI 键盘即插即用：真实力度与延音踏板
 * 直通演奏管线，点击循环切换设备，选择记入 chord.json）；下方列出
 * {@code .minecraft/config/chord/midi/} 下的 .mid/.MIDI，点击即在客户端
 * 转谱（MidiConverter，自动移调适配当前乐器音域 + 超声部收敛单旋律）并按
 * 自动演奏引擎播放——音符带 AUTO 标记、起止发 chord:auto 会话，
 * **服务端完全按「自动演奏」状态展示**（金色粒子/爆发特效）。
 *
 * <p>列表滚动（0.7.15）：曲目数超出可见行数时滚轮翻行——旧版硬性只显示前 11 个，
 * 多出的文件无法从 UI 选到；行数随窗口高度自适应。
 */
public final class ChordMidiScreen extends Screen {

    /** 滚轮每档滚动的曲目行数。 */
    private static final int ROWS_PER_NOTCH = 3;

    static final Path MIDI_DIR = FabricLoader.getInstance().getConfigDir().resolve("chord").resolve("midi");

    private List<Path> files = new ArrayList<>();

    /** 可见行数 / 滚动偏移（init 按窗口高度计算；0.7.15 起不再截断文件列表）。 */
    private int visibleRows;
    private int scrollRow;

    ChordMidiScreen() {
        super(Component.literal("Chord MIDI"));
    }

    @Override
    protected void init() {
        listFiles();
        int buttonWidth = Math.min(240, this.width - 16);
        int buttonX = this.width / 2 - buttonWidth / 2;
        visibleRows = Math.max(1, (this.height - 36 - 64) / 20);
        scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow));

        // 实时 MIDI 输入设备：点击循环（设备1 → 设备2 → … → 关闭 → 设备1）
        addRenderableWidget(Button.builder(Component.literal(widthTrim(midiDeviceLabel(), buttonWidth - 8)),
                button -> {
                    MidiInput.cycle();
                    button.setMessage(Component.literal(widthTrim(midiDeviceLabel(), buttonWidth - 8)));
                }).pos(buttonX, 40).size(buttonWidth, 18).build());
        int y = 64;
        for (int r = 0; r < visibleRows; r++) {
            int index = scrollRow + r;
            if (index >= files.size()) {
                break;
            }
            final Path file = files.get(index);
            String name = "▶ " + widthTrim(file.getFileName().toString(), buttonWidth - 26);
            addRenderableWidget(Button.builder(Component.literal(name), button -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    onClose();
                    PlayState.playMidiFile(mc, file);
                }
            }).pos(buttonX, y).size(buttonWidth, 18).build());
            y += 20;
        }
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> rebuildWidgets())
                .pos(this.width / 2 - 120, this.height - 28).size(78, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .pos(this.width / 2 + 42, this.height - 28).size(78, 20).build());
    }

    private int maxScroll() {
        return Math.max(0, files.size() - visibleRows);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0) {
            int step = scrollY > 0 ? ROWS_PER_NOTCH : -ROWS_PER_NOTCH;
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - step));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 设备选择按钮文案：已连接显示设备名（未连接显示 MIDI 输入设备数）。 */
    private static String midiDeviceLabel() {
        String name = MidiInput.connectedDeviceName();
        int count = MidiInput.inputDevices().size();
        String hint = "点击" + (name != null ? "切换" : "连接") + " · 本机检出 " + count + " 个输入设备";
        return "MIDI 输入：" + (name != null ? name : "未连接") + "（" + hint + "）";
    }

    /** 按显示宽度截断（尾部省略号）：中文文件名/设备名按像素截，不再按字符数溢出按钮。 */
    private String widthTrim(String s, int maxWidth) {
        if (this.font.width(s) <= maxWidth) {
            return s;
        }
        int keep = 0;
        while (keep < s.length() && this.font.width(s.substring(0, keep + 1)) + this.font.width("…") <= maxWidth) {
            keep++;
        }
        return s.substring(0, keep) + "…";
    }

    private void listFiles() {
        files = new ArrayList<>();
        try {
            Files.createDirectories(MIDI_DIR);
            try (Stream<Path> stream = Files.list(MIDI_DIR)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return n.endsWith(".mid") || n.endsWith(".midi");
                }).sorted().forEach(files::add);
            }
        } catch (Exception ignored) {
            // 目录读取失败按空处理
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
        gui.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFE082);
        gui.centeredText(this.font,
                widthTrim("点击文件 = 自动演奏（金色粒子，任意手动键接管）· 连接 MIDI 键盘 = 实时演奏（真实力度+踏板直通）",
                        this.width - 8),
                this.width / 2, 28, 0xFF9E9E9E);
        if (files.isEmpty()) {
            gui.centeredText(this.font, "目录里没有 MIDI：把 .mid 放进下方目录后点「刷新」",
                    this.width / 2, this.height / 2 - 20, 0xFFFF8A65);
            gui.centeredText(this.font, widthTrim(MIDI_DIR.toString(), this.width - 16),
                    this.width / 2, this.height / 2 - 4, 0xFF707070);
        } else if (maxScroll() > 0) {
            gui.centeredText(this.font,
                    "▲ 曲目 " + (scrollRow + 1) + "–" + Math.min(files.size(), scrollRow + visibleRows)
                            + " / 共 " + files.size() + " 个（滚轮翻行）",
                    this.width / 2, this.height - 44, 0xFF707070);
        }
    }
}
