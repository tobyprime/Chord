package chord.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 乐器定义表（chord:sync 频道载荷）。
 *
 * <pre>
 * u16 protocolVersion       协议版本，不匹配时客户端禁用演奏
 * u8  count
 * repeat count: InstrumentDef
 * </pre>
 */
public final class SyncPayload {

    public final int protocolVersion;
    public final List<InstrumentDef> instruments;

    public SyncPayload(int protocolVersion, List<InstrumentDef> instruments) {
        this.protocolVersion = protocolVersion;
        this.instruments = Collections.unmodifiableList(new ArrayList<>(instruments));
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeShort(protocolVersion);
        out.writeByte(instruments.size());
        for (InstrumentDef def : instruments) {
            def.write(out);
        }
    }

    public static SyncPayload read(DataInputStream in) throws IOException {
        int protocolVersion = in.readUnsignedShort();
        int count = in.readUnsignedByte();
        List<InstrumentDef> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(InstrumentDef.read(in));
        }
        return new SyncPayload(protocolVersion, list);
    }

    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            write(out);
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream 不会抛 IOException", e);
        }
        return bos.toByteArray();
    }

    public static SyncPayload fromBytes(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        SyncPayload payload = read(in);
        if (in.available() > 0) {
            throw new IOException("chord:sync 载荷有多余字节: " + in.available());
        }
        return payload;
    }

    public InstrumentDef byId(int id) {
        for (InstrumentDef def : instruments) {
            if (def.id == id) return def;
        }
        return null;
    }

    public InstrumentDef byKey(String key) {
        for (InstrumentDef def : instruments) {
            if (def.key.equals(key)) return def;
        }
        return null;
    }

    @Override
    public String toString() {
        return "SyncPayload{v=" + protocolVersion + ", instruments=" + instruments + "}";
    }
}
