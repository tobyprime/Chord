package chord.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 乐器复音容量表（chord:poly 频道载荷）。
 *
 * <pre>
 * u8  count
 * repeat count: u8 instrumentId, u8 maxPolyphony
 * </pre>
 *
 * <p>复音容量 = 该乐器「按住中」的最大同时音符数，作为 MIDI 转换的自动单音
 * 收敛阈值：原始最大复音超过该值才收敛为单旋律。钢琴是十指乐器给 10，
 * 旋律乐器（口琴）给 6。仅影响编谱决策——服务端演奏本身没有和弦数上限。
 *
 * <p>为什么不放进 chord:sync 的 InstrumentDef：旧客户端（≤0.7.4）的
 * {@link SyncPayload#fromBytes} 对载荷多余字节严格报错，追加字段会让所有
 * 已发布客户端整张乐器表解析失败。独立频道对未注册它的旧客户端是静默丢弃，
 * 新旧客户端/服务端四个组合方向全部兼容；未收到本表（旧服务端）时客户端
 * 按释放模式启发式兜底。
 */
public final class PolyPayload {

    /** 乐器 id → 复音容量（1~32）。 */
    public final Map<Integer, Integer> maxPolyphonyByInstrument;

    public PolyPayload(Map<Integer, Integer> maxPolyphonyByInstrument) {
        this.maxPolyphonyByInstrument = new LinkedHashMap<>(maxPolyphonyByInstrument);
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(maxPolyphonyByInstrument.size());
        for (Map.Entry<Integer, Integer> entry : maxPolyphonyByInstrument.entrySet()) {
            out.writeByte(entry.getKey());
            out.writeByte(entry.getValue());
        }
    }

    public static PolyPayload read(DataInputStream in) throws IOException {
        int count = in.readUnsignedByte();
        Map<Integer, Integer> map = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            map.put(in.readUnsignedByte(), in.readUnsignedByte());
        }
        return new PolyPayload(map);
    }

    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            write(out);
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream 不会抛 IOException", e);
        }
        return bos.toByteArray();
    }

    public static PolyPayload fromBytes(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        PolyPayload payload = read(in);
        if (in.available() > 0) {
            throw new IOException("chord:poly 载荷有多余字节: " + in.available());
        }
        return payload;
    }

    @Override
    public String toString() {
        return "PolyPayload{" + maxPolyphonyByInstrument + "}";
    }
}
