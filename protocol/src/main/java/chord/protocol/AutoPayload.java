package chord.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 自动演奏会话通告（chord:auto 频道载荷，C2S）。
 *
 * <pre>
 * u8  action   1 = 开始（START）/ 0 = 结束（STOP）
 * UTF title    曲名（≤ 64 字符；STOP 时可传空）
 * </pre>
 *
 * <p>服务端据此向周围玩家广播「这是自动演奏，不是现场弹奏」，
 * 并在期间周期性提示。音符包本身用 {@link NotePayload#FLAG_AUTO} 标记，
 * 双方任一丢失都能自洽（无 START 的 AUTO 音符照常播，只是少了会话统计）。
 */
public final class AutoPayload {

    public static final int ACTION_STOP = 0;
    public static final int ACTION_START = 1;
    public static final int MAX_TITLE_CHARS = 64;

    public final int action;
    public final String title;

    public AutoPayload(int action, String title) {
        if (action != ACTION_STOP && action != ACTION_START) {
            throw new IllegalArgumentException("未知 action: " + action);
        }
        if (title == null) {
            title = "";
        }
        if (title.length() > MAX_TITLE_CHARS) {
            throw new IllegalArgumentException("曲名超过 " + MAX_TITLE_CHARS + " 字符");
        }
        this.action = action;
        this.title = title;
    }

    public boolean isStart() {
        return action == ACTION_START;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeByte(action);
        out.writeUTF(title);
    }

    public static AutoPayload read(DataInputStream in) throws IOException {
        int action = in.readUnsignedByte();
        String title = in.readUTF();
        if (title.length() > MAX_TITLE_CHARS) {
            throw new IOException("曲名过长: " + title.length());
        }
        return new AutoPayload(action, title);
    }

    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8 + title.length());
        try (DataOutputStream out = new DataOutputStream(bos)) {
            write(out);
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream 不会抛 IOException", e);
        }
        return bos.toByteArray();
    }

    public static AutoPayload fromBytes(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        AutoPayload payload = read(in);
        if (in.available() > 0) {
            throw new IOException("chord:auto 载荷有多余字节: " + in.available());
        }
        return payload;
    }

    @Override
    public String toString() {
        return "AutoPayload{action=" + action + ", title=\"" + title + "\"}";
    }
}
