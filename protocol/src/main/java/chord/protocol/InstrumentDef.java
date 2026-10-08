package chord.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 乐器定义（chord:sync 下发表的条目）。
 *
 * <p>客户端只需要知道「乐器 id、显示名、音域边界、释放行为」——音色、音量、voices 路由
 * 全部留在服务端配置（设计文档 §7「客户端哑终端」原则）。
 */
public final class InstrumentDef {

    /** def flags 位 0：decay 释放模式（松键不立刻止音，采样自然衰减；如钢琴）。 */
    public static final int FLAG_RELEASE_DECAY = 0x01;

    /**
     * def flags 位 1：sustain 释放模式（如钢琴）——默认松键即制音（制音器语义），
     * K 键是「按住才生效」的延音踏板而非瞬时闷音。客户端据此决定 K 键行为与
     * note-off 是否携带 FLAG_SUSTAIN。
     */
    public static final int FLAG_SUSTAIN_PEDAL = 0x02;

    /** 半音区间的显示名规则（如架子鼓：0~1 → 「大鼓」），仅影响客户端 HUD/配置屏文案。 */
    public record LabelRule(int min, int max, String label) {

        public boolean contains(int semitone) {
            return semitone >= min && semitone <= max;
        }
    }

    public final int id;
    /** 配置键，如 "harmonica"；与服务端 instruments.yml 的键、物品 PDC 标记值一致。 */
    public final String key;
    public final String displayName;
    /** 相对中心音的最小/最大半音偏移（含边界）。 */
    public final int minSemitone;
    public final int maxSemitone;
    /**
     * true = 松键有余音语义：decay 乐器（吉他/鼓）自然衰减；sustain 乐器（钢琴）
     * 默认短缓冲后制音、踏板期间进余音。客户端据此维护「可能仍在响」的止音集合。
     */
    public final boolean releaseDecay;
    /** true = sustain 释放模式（钢琴）：K 键 = 按住的延音踏板。 */
    public final boolean sustainPedal;
    /** 可选：半音区间 → 显示名；空 = 客户端回退到音级名（低1/高2♯…）。 */
    public final List<LabelRule> labels;

    public InstrumentDef(int id, String key, String displayName, int minSemitone, int maxSemitone) {
        this(id, key, displayName, minSemitone, maxSemitone, false);
    }

    public InstrumentDef(int id, String key, String displayName, int minSemitone, int maxSemitone,
                         boolean releaseDecay) {
        this(id, key, displayName, minSemitone, maxSemitone, releaseDecay, List.of());
    }

    public InstrumentDef(int id, String key, String displayName, int minSemitone, int maxSemitone,
                         boolean releaseDecay, List<LabelRule> labels) {
        this(id, key, displayName, minSemitone, maxSemitone, releaseDecay, false, labels);
    }

    public InstrumentDef(int id, String key, String displayName, int minSemitone, int maxSemitone,
                         boolean releaseDecay, boolean sustainPedal, List<LabelRule> labels) {
        this.id = id;
        this.key = Objects.requireNonNull(key, "key");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.minSemitone = minSemitone;
        this.maxSemitone = maxSemitone;
        this.releaseDecay = releaseDecay;
        this.sustainPedal = sustainPedal;
        this.labels = List.copyOf(labels);
    }

    public boolean inRange(int semitone) {
        return semitone >= minSemitone && semitone <= maxSemitone;
    }

    /** 该半音的显示名；无规则命中返回 null（客户端回退到音级名）。 */
    public String labelFor(int semitone) {
        for (LabelRule rule : labels) {
            if (rule.contains(semitone)) {
                return rule.label();
            }
        }
        return null;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(id);
        out.writeUTF(key);
        out.writeUTF(displayName);
        out.writeByte(minSemitone);
        out.writeByte(maxSemitone);
        out.writeByte((releaseDecay ? FLAG_RELEASE_DECAY : 0) | (sustainPedal ? FLAG_SUSTAIN_PEDAL : 0));
        out.writeByte(labels.size());
        for (LabelRule rule : labels) {
            out.writeByte(rule.min());
            out.writeByte(rule.max());
            out.writeUTF(rule.label());
        }
    }

    public static InstrumentDef read(DataInputStream in) throws IOException {
        int id = in.readUnsignedByte();
        String key = in.readUTF();
        String displayName = in.readUTF();
        int min = in.readByte();
        int max = in.readByte();
        int flags = in.readUnsignedByte();
        int labelCount = in.readUnsignedByte();
        List<LabelRule> labels = new ArrayList<>(labelCount);
        for (int i = 0; i < labelCount; i++) {
            int ruleMin = in.readByte();
            int ruleMax = in.readByte();
            labels.add(new LabelRule(ruleMin, ruleMax, in.readUTF()));
        }
        return new InstrumentDef(id, key, displayName, min, max,
                (flags & FLAG_RELEASE_DECAY) != 0, (flags & FLAG_SUSTAIN_PEDAL) != 0,
                Collections.unmodifiableList(labels));
    }

    @Override
    public String toString() {
        return "InstrumentDef{id=" + id + ", key=" + key + ", name=" + displayName
                + ", range=[" + minSemitone + "," + maxSemitone + "]"
                + (releaseDecay ? (sustainPedal ? ", sustain" : ", decay") : "")
                + (labels.isEmpty() ? "" : ", labels=" + labels.size()) + "}";
    }
}
