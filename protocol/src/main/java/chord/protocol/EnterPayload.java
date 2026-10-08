package chord.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 世界乐器进入/退出事件（chord:enter 频道载荷，S2C）。
 *
 * <p>服务端在玩家右键带标记的交互实体（如钢琴）时下发；
 * 组件/标记的判定完全在服务端完成，客户端不需要感知实体数据（设计文档 §6）。
 *
 * <pre>
 * u8  action        0 = 进入演奏态，1 = 退出
 * u8  instrumentId  乐器 id（退出时忽略）
 * f64 x, y, z       乐器锚点坐标（进入时客户端用于距离自动退出；退出时忽略）
 * </pre>
 */
public final class EnterPayload {

    public static final int ACTION_ENTER = 0;
    public static final int ACTION_EXIT = 1;

    public final int action;
    public final int instrumentId;
    public final double x;
    public final double y;
    public final double z;

    public EnterPayload(int action, int instrumentId, double x, double y, double z) {
        if (action != ACTION_ENTER && action != ACTION_EXIT) {
            throw new IllegalArgumentException("未知 action: " + action);
        }
        this.action = action;
        this.instrumentId = instrumentId;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isExit() {
        return action == ACTION_EXIT;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(action);
        out.writeByte(instrumentId);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
    }

    public static EnterPayload read(DataInputStream in) throws IOException {
        int action = in.readUnsignedByte();
        int instrumentId = in.readUnsignedByte();
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        return new EnterPayload(action, instrumentId, x, y, z);
    }

    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(26);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            write(out);
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream 不会抛 IOException", e);
        }
        return bos.toByteArray();
    }

    public static EnterPayload fromBytes(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        EnterPayload payload = read(in);
        if (in.available() > 0) {
            throw new IOException("chord:enter 载荷有多余字节: " + in.available());
        }
        return payload;
    }

    @Override
    public String toString() {
        return "EnterPayload{action=" + action + ", instrument=" + instrumentId
                + ", anchor=(" + x + "," + y + "," + z + ")}";
    }
}
