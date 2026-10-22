package chord.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 演奏配置页（按「演奏配置」键打开，默认 J）——演奏输入的唯一配置入口。
 *
 * <p>两列 × 12 行 = 24 个演奏键槽，每行：物理键（点击后按任意键捕获，Delete 清除）、
 * 半音 −/+ 步进（按住 Shift ±12）、显示名。底部功能键区：自动演奏 / 踏板 /
 * 高八度锁 / 低八度锁（同样页内捕获）。保存写入 chord.json 并**立即生效**
 * （正在演奏则先退出演奏态）。不再走原版「控件设置」。
 *
 * <p>布局自适应（0.7.15）：窄窗口降为单列；高度不足时槽位区滚轮翻行——
 * 1080p 自动缩放 4（GUI 高 270）下旧版会功能键区与槽位行重叠、末行溢出屏幕。
 * 关页防丢改动：有未保存改动时 Esc 不直接关，先进入确认行（放弃 / 继续编辑 / 保存）。
 */
public final class ChordConfigScreen extends Screen {

    /** 与最大乐器音域一致（钢琴 0.7.4 起 A0~C8 = [-39, 48]）。 */
    private static final int MIN_SEMITONE = -39;
    private static final int MAX_SEMITONE = 48;
    private static final int ROW_HEIGHT = 20;
    private static final int TOP = 54;
    private static final int VALUE_COLOR = 0xFFE0E0E0;

    private final List<ChordConfig.Slot> slots;
    /** 键捕获状态：-1 = 无；0~23 = 演奏槽；24 = 自动演奏；25 = 踏板；26 = 高八度锁；27 = 低八度锁。 */
    private int capturing = -1;

    private String autoKey;
    private String pedalKey;
    private String latchHighKey;
    private String latchLowKey;

    /** 输入框实时内容（rebuild 后 EditBox 重建，文字住这里不丢——含滚轮翻行/确认切换）。 */
    private final String[] labels = new String[ChordConfig.MAX_SLOTS];
    /** 有未保存改动（Esc 关页前确认）。 */
    private boolean dirty;
    /** 处于「未保存确认」状态（底部换成 放弃/继续编辑/保存）。 */
    private boolean confirming;

    /** 布局（init 按窗口尺寸计算）：列数 / 每列行数 / 列宽 / 可见行数 / 滚动偏移（行）。 */
    private int columns = 2;
    private int rowsPerColumn = 12;
    private int columnWidth = 258;
    private int visibleRows = 12;
    private int scrollRow;

    private final Button[] keyButtons = new Button[ChordConfig.MAX_SLOTS];
    private final EditBox[] labelBoxes = new EditBox[ChordConfig.MAX_SLOTS];
    private Button autoKeyButton;
    private Button pedalKeyButton;
    private Button latchHighButton;
    private Button latchLowButton;

    ChordConfigScreen(ChordConfig config) {
        super(Component.literal("Chord 演奏配置"));
        this.slots = new ArrayList<>();
        for (ChordConfig.Slot slot : config.slots) {
            this.slots.add(slot.copy());
        }
        while (this.slots.size() < ChordConfig.MAX_SLOTS) {
            this.slots.add(new ChordConfig.Slot());
        }
        this.autoKey = config.autoKey;
        this.pedalKey = config.pedalKey;
        this.latchHighKey = config.latchHighKey;
        this.latchLowKey = config.latchLowKey;
        for (int i = 0; i < ChordConfig.MAX_SLOTS; i++) {
            ChordConfig.Slot slot = this.slots.get(i);
            labels[i] = slot.label != null ? slot.label : "";
        }
    }

    @Override
    protected void init() {
        Arrays.fill(keyButtons, null);
        Arrays.fill(labelBoxes, null);
        autoKeyButton = pedalKeyButton = latchHighButton = latchLowButton = null;

        // 布局自适应：宽 ≥424 双列，否则单列；高度不足时槽位区滚动
        columns = this.width >= 424 ? 2 : 1;
        rowsPerColumn = ChordConfig.MAX_SLOTS / columns;
        columnWidth = Math.min(258, (this.width - 8 * (columns + 1)) / columns);
        int functionY = this.height - 72;
        visibleRows = Math.max(3, Math.min(rowsPerColumn, (functionY - 6 - TOP) / ROW_HEIGHT));
        scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow));

        for (int i = 0; i < ChordConfig.MAX_SLOTS; i++) {
            int column = i / rowsPerColumn;
            int row = i % rowsPerColumn;
            if (row < scrollRow || row >= scrollRow + visibleRows) {
                continue; // 滚动窗口外的行不创建控件
            }
            int x0 = columnX(column);
            int y = TOP + (row - scrollRow) * ROW_HEIGHT;
            int index = i;

            keyButtons[index] = addRenderableWidget(Button.builder(keyButtonLabel(index), button -> startCapture(index))
                    .pos(x0, y).size(58, 18).build());
            addRenderableWidget(Button.builder(Component.literal("−"),
                            button -> bump(index, -step()))
                    .pos(x0 + 62, y).size(18, 18).build());
            addRenderableWidget(Button.builder(Component.literal("+"),
                            button -> bump(index, step()))
                    .pos(x0 + columnWidth - 88, y).size(18, 18).build());

            EditBox box = new EditBox(this.font, x0 + columnWidth - 66, y, 62, 18, Component.literal("显示名"));
            box.setMaxLength(12);
            box.setHint(Component.literal("名"));
            box.setValue(labels[index]);
            box.setResponder(text -> {
                labels[index] = text;
                dirty = true;
            });
            labelBoxes[index] = box;
            addRenderableWidget(box);
        }

        if (confirming) {
            // 未保存确认行：[✘ 放弃改动] [✔ 继续编辑] [保存并生效]
            int start = this.width / 2 - 171;
            addRenderableWidget(Button.builder(Component.literal("✘ 放弃改动"), button -> {
                dirty = false;
                onClose();
            }).pos(start, this.height - 30).size(120, 20).build());
            addRenderableWidget(Button.builder(Component.literal("✔ 继续编辑"), button -> {
                confirming = false;
                rebuild();
            }).pos(start + 126, this.height - 30).size(110, 20).build());
            addRenderableWidget(Button.builder(Component.literal("保存并生效"), button -> saveAndApply())
                    .pos(start + 242, this.height - 30).size(100, 20).build());
            return;
        }

        // 功能键区（底部）
        autoKeyButton = addRenderableWidget(Button.builder(fnKeyLabel("自动", autoKey),
                b -> startCapture(24)).pos(this.width / 2 - 124, functionY).size(120, 18).build());
        pedalKeyButton = addRenderableWidget(Button.builder(fnKeyLabel("踏板", pedalKey),
                b -> startCapture(25)).pos(this.width / 2 + 4, functionY).size(120, 18).build());
        latchHighButton = addRenderableWidget(Button.builder(fnKeyLabel("高八度锁", latchHighKey),
                b -> startCapture(26)).pos(this.width / 2 - 124, functionY + 20).size(120, 18).build());
        latchLowButton = addRenderableWidget(Button.builder(fnKeyLabel("低八度锁", latchLowKey),
                b -> startCapture(27)).pos(this.width / 2 + 4, functionY + 20).size(120, 18).build());

        addRenderableWidget(Button.builder(Component.literal("恢复默认"), button -> {
            ChordConfig def = ChordConfig.defaults();
            for (int i = 0; i < ChordConfig.MAX_SLOTS; i++) {
                slots.get(i).key = i < def.slots.size() ? def.slots.get(i).key : null;
                slots.get(i).semitone = i < def.slots.size() ? def.slots.get(i).semitone : 0;
            }
            autoKey = def.autoKey;
            pedalKey = def.pedalKey;
            latchHighKey = def.latchHighKey;
            latchLowKey = def.latchLowKey;
            dirty = true;
            refreshKeyButtons();
        }).pos(this.width / 2 - 152, this.height - 30).size(100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("全部清空键位"), button -> {
            for (int i = 0; i < ChordConfig.MAX_SLOTS; i++) {
                slots.get(i).key = null;
            }
            dirty = true;
            refreshKeyButtons();
        }).pos(this.width / 2 - 48, this.height - 30).size(96, 20).build());

        addRenderableWidget(Button.builder(Component.literal("保存并生效"), button -> saveAndApply())
                .pos(this.width / 2 + 52, this.height - 30).size(100, 20).build());
    }

    private int maxScroll() {
        return Math.max(0, rowsPerColumn - visibleRows);
    }

    /** 列 c 的 x 起点（整体居中，列间 8px）。 */
    private int columnX(int column) {
        int total = columns * columnWidth + (columns - 1) * 8;
        return (this.width - total) / 2 + column * (columnWidth + 8);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0) {
            int target = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY)));
            if (target != scrollRow) {
                scrollRow = target;
                capturing = -1;
                rebuild();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 开始捕获物理键：下一个按键写入该槽（Esc 取消、Delete 清除绑定）。 */
    private void startCapture(int target) {
        capturing = target;
        refreshKeyButtons();
    }

    /** 最近一次输入事件的 Shift 状态（半音步进 ±12 用；事件自带修饰位，不碰窗口句柄）。 */
    private boolean shiftHeld;

    @Override
    public boolean keyPressed(KeyEvent event) {
        shiftHeld = event.hasShiftDown();
        if (capturing >= 0) {
            if (event.isEscape()) {
                capturing = -1;
            } else if (event.key() == InputConstants.KEY_DELETE) {
                if (capturing < ChordConfig.MAX_SLOTS) {
                    slots.get(capturing).key = null;
                } else if (capturing == 24) autoKey = null;
                else if (capturing == 25) pedalKey = null;
                else if (capturing == 26) latchHighKey = null;
                else if (capturing == 27) latchLowKey = null;
                capturing = -1;
                dirty = true;
            } else {
                String name = InputConstants.getKey(event).getName();
                // 同键冲突：先清掉其他槽上的相同绑定（后绑优先）
                for (ChordConfig.Slot slot : slots) {
                    if (name.equals(slot.key)) {
                        slot.key = null;
                    }
                }
                if (autoKey != null && autoKey.equals(name)) autoKey = null;
                if (pedalKey != null && pedalKey.equals(name)) pedalKey = null;
                if (latchHighKey != null && latchHighKey.equals(name)) latchHighKey = null;
                if (latchLowKey != null && latchLowKey.equals(name)) latchLowKey = null;
                if (capturing < ChordConfig.MAX_SLOTS) {
                    slots.get(capturing).key = name;
                } else if (capturing == 24) autoKey = name;
                else if (capturing == 25) pedalKey = name;
                else if (capturing == 26) latchHighKey = name;
                else if (capturing == 27) latchLowKey = name;
                capturing = -1;
                dirty = true;
            }
            refreshKeyButtons();
            return true;
        }
        if (confirming && event.isEscape()) {
            confirming = false; // 确认态里 Esc = 返回编辑（退出必须显点「放弃改动」）
            rebuild();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        shiftHeld = event.hasShiftDown();
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        shiftHeld = event.hasShiftDown();
        return super.mouseClicked(event, doubled);
    }

    /** 有未保存改动时拦下关页（Esc / 其他关闭路径），先进确认行。 */
    @Override
    public void onClose() {
        if (dirty && !confirming) {
            confirming = true;
            rebuild();
            return;
        }
        super.onClose();
    }

    /** 半音步进：Shift 按住 ±12（八度），否则 ±1。 */
    private void bump(int index, int delta) {
        ChordConfig.Slot slot = slots.get(index);
        slot.semitone = Math.max(MIN_SEMITONE, Math.min(MAX_SEMITONE, slot.semitone + delta));
        dirty = true;
    }

    private int step() {
        return shiftHeld ? 12 : 1;
    }

    private void rebuild() {
        rebuildWidgets();
    }

    private void refreshKeyButtons() {
        for (int i = 0; i < ChordConfig.MAX_SLOTS; i++) {
            if (keyButtons[i] != null) {
                keyButtons[i].setMessage(keyButtonLabel(i));
            }
        }
        if (autoKeyButton != null) autoKeyButton.setMessage(fnKeyLabel("自动", autoKey));
        if (pedalKeyButton != null) pedalKeyButton.setMessage(fnKeyLabel("踏板", pedalKey));
        if (latchHighButton != null) latchHighButton.setMessage(fnKeyLabel("高八度锁", latchHighKey));
        if (latchLowButton != null) latchLowButton.setMessage(fnKeyLabel("低八度锁", latchLowKey));
    }

    private Component keyButtonLabel(int index) {
        if (capturing == index) {
            return Component.literal("按键…");
        }
        String key = slots.get(index).key;
        return Component.literal(keyDisplayName(key));
    }

    private Component fnKeyLabel(String name, String key) {
        return Component.literal(name + "：" + keyDisplayName(key));
    }

    /** 键名 → 短显示（key.keyboard.z → Z；未绑定 → 「—」）。 */
    static String keyDisplayName(String key) {
        if (key == null || key.isBlank()) {
            return "—";
        }
        if (key.startsWith("key.keyboard.")) {
            return key.substring("key.keyboard.".length()).toUpperCase(java.util.Locale.ROOT);
        }
        if (key.startsWith("key.mouse.")) {
            return "鼠标" + key.substring("key.mouse.".length()).toUpperCase(java.util.Locale.ROOT);
        }
        return key;
    }

    private void saveAndApply() {
        for (int i = 0; i < ChordConfig.MAX_SLOTS; i++) {
            String label = labels[i] == null ? "" : labels[i].trim();
            slots.get(i).label = label.isEmpty() ? null : label;
        }
        ChordClient.applyConfig(slots, autoKey, pedalKey, latchHighKey, latchLowKey);
        dirty = false;
        onClose();
    }

    /** 按显示宽度截断（尾部省略号），窄屏下提示行/长文本不溢出。 */
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

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFE082);
        gui.drawCenteredString(this.font,
                widthTrim("点键位→按任意键（Delete 清除）· 半音 −/+（Shift = ±12）· 鼠标修饰键自动叠加 · 踏板双击 = 切延音模式",
                        this.width - 8),
                this.width / 2, 32, 0xFF9E9E9E);
        if (maxScroll() > 0) {
            gui.drawCenteredString(this.font,
                    "▲ 第 " + (scrollRow + 1) + "–" + (scrollRow + visibleRows) + " 行 / 共 " + rowsPerColumn
                            + " 行（滚轮翻行）",
                    this.width / 2, 43, 0xFF707070);
        }
        if (confirming) {
            gui.drawCenteredString(this.font, "⚠ 有未保存的键位改动——放弃退出将丢失", this.width / 2, this.height - 44,
                    0xFFFF8A65);
        }
        for (int i = 0; i < ChordConfig.MAX_SLOTS; i++) {
            int column = i / rowsPerColumn;
            int row = i % rowsPerColumn;
            if (row < scrollRow || row >= scrollRow + visibleRows) {
                continue;
            }
            int x0 = columnX(column);
            int y = TOP + (row - scrollRow) * ROW_HEIGHT;
            ChordConfig.Slot slot = slots.get(i);
            String value = slot.key != null && !slot.key.isBlank()
                    ? (slot.semitone > 0 ? "+" : "") + slot.semitone + "·" + PlayState.semitoneName(slot.semitone)
                    : "—";
            int valueX = x0 + (columnWidth - 8) / 2;
            gui.drawCenteredString(this.font, widthTrim(value, Math.max(24, columnWidth - 106)), valueX, y + 5,
                    value.equals("—") ? 0xFF707070 : VALUE_COLOR);
        }
    }
}
