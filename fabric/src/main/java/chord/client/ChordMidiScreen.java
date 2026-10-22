package chord.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <p>0.8.1 起列表三件套：搜索框（文件名包含匹配、实时过滤）、明确的滚动条
 * （滚轮 + 点击跳转 + 拖拽跟随，拇指按可视比例定位）、「打开文件夹」按钮
 * （系统资源管理器直达 midi 目录，放完文件点「刷新」）。列表滚动自 0.7.15
 * 起行数随窗口高度自适应，不再截断文件列表。
 */
public final class ChordMidiScreen extends Screen {

    /** 滚轮每档滚动的曲目行数。 */
    private static final int ROWS_PER_NOTCH = 3;
    private static final int SCROLLBAR_WIDTH = 4;

    static final Path MIDI_DIR = FabricLoader.getInstance().getConfigDir().resolve("chord").resolve("midi");

    private List<Path> files = new ArrayList<>();
    /** 当前过滤结果（init 按 {@link #filter} 重算）。 */
    private List<Path> shown = List.of();

    /** 搜索词（跨 rebuild 保留；rebuild 由搜索回调触发）。 */
    private String filter = "";
    /** rebuild 后把焦点还给搜索框（连续打字）。 */
    private boolean refocusSearch;
    private EditBox searchBox;

    /** 列表区几何（init 按窗口尺寸计算，渲染/滚动条/拖拽共用）。 */
    private int buttonWidth;
    private int buttonX;
    private int listTop;
    private int visibleRows;
    private int scrollRow;
    private boolean draggingScrollbar;

    ChordMidiScreen() {
        super(Component.literal("Chord MIDI"));
    }

    @Override
    protected void init() {
        listFiles();
        shown = shownFiles();
        buttonWidth = Math.min(240, this.width - 32);
        buttonX = this.width / 2 - buttonWidth / 2;
        listTop = 70;
        visibleRows = Math.max(1, (this.height - listTop - 72) / 20);
        scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow));

        // 搜索框：文件名包含匹配、实时过滤。setValue 同样会触发响应回调，
        // 以「值未变化直接返回」防 init 期间递归 rebuild
        searchBox = new EditBox(this.font, buttonX, 32, buttonWidth, 14, Component.literal("搜索"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("搜索曲目名（文件名包含匹配）"));
        searchBox.setValue(filter);
        searchBox.setResponder(value -> {
            if (value.equals(filter)) {
                return;
            }
            filter = value;
            refocusSearch = true;
            scrollRow = 0;
            rebuildWidgets();
        });
        addRenderableWidget(searchBox);
        if (refocusSearch) {
            setFocused(searchBox);
            searchBox.setCursorPosition(searchBox.getValue().length());
            refocusSearch = false;
        }

        // 实时 MIDI 输入设备：点击循环（设备1 → 设备2 → … → 关闭 → 设备1）
        addRenderableWidget(Button.builder(Component.literal(widthTrim(midiDeviceLabel(), buttonWidth - 8)),
                button -> {
                    MidiInput.cycle();
                    button.setMessage(Component.literal(widthTrim(midiDeviceLabel(), buttonWidth - 8)));
                }).pos(buttonX, 50).size(buttonWidth, 16).build());

        // 曲目列表（当前过滤结果）
        int y = listTop;
        for (int r = 0; r < visibleRows; r++) {
            int index = scrollRow + r;
            if (index >= shown.size()) {
                break;
            }
            final Path file = shown.get(index);
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

        // 底部：刷新 / 打开文件夹 / 关闭
        int bw = Math.min(88, (this.width - 20) / 3);
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> rebuildWidgets())
                .pos(this.width / 2 - bw * 3 / 2 - 6, this.height - 24).size(bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("打开文件夹"), button -> openFolder())
                .pos(this.width / 2 - bw / 2, this.height - 24).size(bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .pos(this.width / 2 + bw / 2 + 6, this.height - 24).size(bw, 18).build());
    }

    /**
     * 在系统资源管理器打开 MIDI 目录（放完文件回游戏点「刷新」即可见）。
     * 用 java.awt.Desktop 而非 MC 的 Util.OS——26.3 已移除其 openFile，Desktop 三版通用。
     */
    private void openFolder() {
        try {
            Files.createDirectories(MIDI_DIR);
            java.awt.Desktop.getDesktop().open(MIDI_DIR.toFile());
        } catch (Exception ignored) {
            // 打开失败静默：目录路径始终显示在空目录提示里
        }
    }

    /** 搜索过滤：文件名包含匹配（大小写不敏感；空串 = 全部）。 */
    private List<Path> shownFiles() {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        if (f.isEmpty()) {
            return files;
        }
        List<Path> out = new ArrayList<>();
        for (Path p : files) {
            if (p.getFileName().toString().toLowerCase(Locale.ROOT).contains(f)) {
                out.add(p);
            }
        }
        return out;
    }

    private int maxScroll() {
        return Math.max(0, shown.size() - visibleRows);
    }

    // ---- 滚动条几何 ----

    private int trackX() {
        return buttonX + buttonWidth + 3;
    }

    private int trackTop() {
        return listTop;
    }

    private int trackBottom() {
        return listTop + visibleRows * 20 - 2;
    }

    private int thumbHeight() {
        int track = trackBottom() - trackTop();
        return Math.max(10, track * visibleRows / Math.max(1, shown.size()));
    }

    private int thumbY() {
        int track = trackBottom() - trackTop();
        int movable = shown.size() - visibleRows;
        if (movable <= 0) {
            return trackTop();
        }
        return trackTop() + (track - thumbHeight()) * scrollRow / movable;
    }

    /** 按鼠标 y 换算滚动偏移（点击轨道即跳转，拖拽跟随）。 */
    private void scrollToMouse(double mouseY) {
        int track = trackBottom() - trackTop();
        int thumb = thumbHeight();
        int movable = shown.size() - visibleRows;
        if (movable <= 0 || track <= thumb) {
            return;
        }
        double ratio = (mouseY - trackTop() - thumb / 2.0) / (track - thumb);
        scrollRow = Math.max(0, Math.min(movable, (int) Math.round(ratio * movable)));
        rebuildWidgets();
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (maxScroll() > 0
                && event.x() >= trackX() - 2 && event.x() <= trackX() + SCROLLBAR_WIDTH + 2
                && event.y() >= trackTop() && event.y() <= trackBottom()) {
            draggingScrollbar = true;
            scrollToMouse(event.y());
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollToMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
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
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
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
                this.width / 2, 21, 0xFF9E9E9E);

        int listCenterY = listTop + visibleRows * 10;
        if (files.isEmpty()) {
            gui.centeredText(this.font, "目录里没有 MIDI：点「打开文件夹」放入 .mid 后点「刷新」",
                    this.width / 2, listCenterY - 8, 0xFFFF8A65);
            gui.centeredText(this.font, widthTrim(MIDI_DIR.toString(), this.width - 16),
                    this.width / 2, listCenterY + 8, 0xFF707070);
        } else if (shown.isEmpty()) {
            gui.centeredText(this.font,
                    widthTrim("没有匹配「" + filter.trim() + "」的曲目", this.width - 16),
                    this.width / 2, listCenterY, 0xFFFF8A65);
        }
        if (maxScroll() > 0) {
            // 明确的滚动条：深色轨道 + 亮色拇指（按可视比例定位）
            gui.fill(trackX(), trackTop(), trackX() + SCROLLBAR_WIDTH, trackBottom(), 0xFF3A3A3A);
            gui.fill(trackX(), thumbY(), trackX() + SCROLLBAR_WIDTH, thumbY() + thumbHeight(), 0xFF9A9A9A);
            gui.centeredText(this.font,
                    "曲目 " + (scrollRow + 1) + "–" + Math.min(shown.size(), scrollRow + visibleRows)
                            + " / 共 " + shown.size() + " 个（滚轮 / 拖动滚动条）",
                    this.width / 2, this.height - 40, 0xFF707070);
        }
    }
}
