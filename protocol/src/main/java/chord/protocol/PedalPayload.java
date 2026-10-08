package chord.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 延音踏板事件（chord:pedal 频道载荷，仅 sustain 释放模式乐器如钢琴）。
 *
 * <pre>
 * u8 action   1 = 踩下（期间松键的音进余音表；正在制音缓冲中的音被「接住」转余音）
 *             0 = 松开（所有「键已松开」的余音被制音；按住中的键不受影响）
 * </pre>
 *
 * <p>对应真钢琴右踏板：默认制音器压弦（松键即止），踩下踏板抬起所有制音器。
 */
public final class PedalPayload {

    public static final int ACTION_DOWN = 1;
    public static final int ACTION_UP = 0;

    public final int action;

    public PedalPayload(int action) {
        if (action != ACTION_DOWN && action != ACTION_UP) {
            throw new IllegalArgumentException("未知踏板动作: " + action);
        }
        this.action = action;
    }

    public boolean isDown() {
        return action == ACTION_DOWN;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(action);
    }

    public static PedalPayload read(DataInputStream in) throws IOException {
        return new PedalPayload(in.readUnsignedByte());
    }

    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            write(out);
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream 不会抛 IOException", e);
        }
        return bos.toByteArray();
    }

    public static PedalPayload fromBytes(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        PedalPayload payload = read(in);
        if (in.available() > 0) {
            throw new IOException("chord:pedal 载荷有多余字节: " + in.available());
        }
        return payload;
    }

    @Override
    public String toString() {
        return "PedalPayload{action=" + (isDown() ? "DOWN" : "UP") + "}";
    }
}
