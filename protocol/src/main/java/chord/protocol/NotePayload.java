package chord.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * 单个音符事件（chord:play 频道载荷）。
 *
 * <pre>
 * u8 instrumentId   乐器 id（由 chord:sync 下发的乐器表分配）
 * i8 semitone       相对乐器中心音的半音偏移（有符号）
 * u8 velocity       力度 1~127（100 = 基准音量；服务端按乐器力度层路由 + 音量缩放）。
 *                   note-off 时无意义（填 0）
 * u8 flags          见 FLAG_* 常量
 * </pre>
 *
 * <p>力度是 v6 表现力管线的基础：普通键盘默认恒 100（可用客户端滚轮微调），
 * 磁轴键盘等模拟输入源（V2 适配器）将来按行程速度给出真实每键力度。
 * 和弦 = 同一时刻多个 NotePayload 包，不做合包。
 * 编解码只依赖 java.io，Fabric 侧通过 ByteBuf 流桥接复用本类，保证两侧线格式一致。
 */
public final class NotePayload {

    /** flags 位 0：音符结束（松键）。服务端对活跃音符发送 stopSound，实现「按住持续、松开即停」。 */
    public static final int FLAG_NOTE_OFF = 0x01;

    /** flags 位 1：自动演奏（自动演奏引擎发的音，非现场按键）。服务端据此做「非现场弹奏」提示与特效区分。 */
    public static final int FLAG_AUTO = 0x02;

    /**
     * flags 位 2：硬止音（与 FLAG_NOTE_OFF 同用）。普通 note-off 对 decay 释放模式的乐器
     * （钢琴等）只是释放琴键、让采样自然衰减；本位表示「掐掉余音」——止音踏板键、
     * 退出演奏态时客户端带上，服务端对活跃/余音表逐一听众 stopSound。
     */
    public static final int FLAG_FORCE_STOP = 0x04;

    /**
     * flags 位 3：延音踏板踩下期间的松键（与 FLAG_NOTE_OFF 同用，仅 sustain 释放模式乐器）。
     * 服务端把该音转入余音表而不是制音——对应真钢琴「踏板踩着时抬键，弦继续振动」。
     */
    public static final int FLAG_SUSTAIN = 0x08;

    /** 默认力度：服务端以此为单位音量（×1.0），低于则更轻、高于（至 127）为重音。 */
    public static final int DEFAULT_VELOCITY = 100;

    public final int instrumentId;
    public final int semitone;
    public final int velocity;
    public final int flags;

    public NotePayload(int instrumentId, int semitone, int flags) {
        this(instrumentId, semitone, 0, flags);
    }

    public NotePayload(int instrumentId, int semitone, int velocity, int flags) {
        if (instrumentId < 0 || instrumentId > 255) {
            throw new IllegalArgumentException("instrumentId out of u8 range: " + instrumentId);
        }
        if (velocity < 0 || velocity > 127) {
            throw new IllegalArgumentException("velocity out of range: " + velocity);
        }
        this.instrumentId = instrumentId;
        this.semitone = semitone;
        this.velocity = velocity;
        this.flags = flags;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(instrumentId);
        out.writeByte(semitone);
        out.writeByte(velocity);
        out.writeByte(flags);
    }

    public static NotePayload read(DataInputStream in) throws IOException {
        int instrumentId = in.readUnsignedByte();
        int semitone = in.readByte(); // 有符号
        int velocity = in.readUnsignedByte();
        int flags = in.readUnsignedByte();
        return new NotePayload(instrumentId, semitone, velocity, flags);
    }

    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            write(out);
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream 不会抛 IOException", e);
        }
        return bos.toByteArray();
    }

    public static NotePayload fromBytes(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        NotePayload payload = read(in);
        if (in.available() > 0) {
            throw new IOException("chord:play 载荷有多余字节: " + in.available());
        }
        return payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NotePayload that)) return false;
        return instrumentId == that.instrumentId && semitone == that.semitone
                && velocity == that.velocity && flags == that.flags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(instrumentId, semitone, velocity, flags);
    }

    @Override
    public String toString() {
        return "NotePayload{instrument=" + instrumentId + ", semitone=" + semitone
                + ", velocity=" + velocity + ", flags=" + flags + "}";
    }
}
