import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 测试用：合成一个多声部 MIDI（低音 + 6 音和弦 + 旋律 + 一个超两个八度折叠才够得着的高音）。
 * 保存为 tools/poly-test.mid。
 */
public final class PolyMidGen {

    public static void main(String[] args) throws Exception {
        var track = new ByteArrayOutputStream();
        // 音符事件表：tick, pitch, lenTicks（division 480，120BPM → 480 tick = 500ms）
        int[][] notes = {
                // 低音声部（C2=36，出界需折叠）
                {0, 36, 1920}, {1920, 43, 1920}, {3840, 36, 1920},
                // 6 音和弦（60 62 64 67 69 72）→ 同时音 7（含低音）触发单音收敛
                {0, 60, 240}, {0, 62, 240}, {0, 64, 240}, {0, 67, 240}, {0, 69, 240}, {0, 72, 240},
                // 旋律（E5 F5 G5 A5 …）与和弦重叠
                {240, 76, 240}, {480, 77, 240}, {720, 79, 480},
                {1920, 76, 240}, {2160, 74, 240}, {2400, 72, 480},
                // 一个高得离谱的音（G7=91 → 相对 C4 = +31，向下折叠一个八度 = +19 入域）
                {3840, 91, 480},
                // 一个超过 2 个八度折叠范围也会先收敛掉的低音（这里放旋律位 A2=45? 不收敛则丢弃路径难造，先不做）
        };
        // 按 tick 排序写入（简单插入排序）
        java.util.Arrays.sort(notes, (a, b) -> Integer.compare(a[0], b[0]));
        int lastTick = 0;
        var events = new java.util.ArrayList<int[]>(); // tick, status, p1, p2
        for (int[] n : notes) {
            events.add(new int[]{n[0], 0x90, n[1], 80});
            events.add(new int[]{n[0] + n[2], 0x80, n[1], 0});
        }
        events.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int[] e : events) {
            writeVlq(track, e[0] - lastTick);
            lastTick = e[0];
            track.write(e[1]);
            track.write(e[2]);
            track.write(e[3]);
        }
        writeVlq(track, 0); // end of track（紧跟最后一个事件）
        track.write(0xFF);
        track.write(0x2F);
        track.write(0x00);

        var out = new ByteArrayOutputStream();
        var head = new DataOutputStream(out);
        head.writeBytes("MThd");
        head.writeInt(6);
        head.writeShort(0);   // format 0
        head.writeShort(1);   // 1 track
        head.writeShort(480); // division
        head.writeBytes("MTrk");
        head.writeInt(track.size());
        head.write(track.toByteArray());
        Path target = Path.of("tools/poly-test.mid");
        try (var fos = new FileOutputStream(target.toFile())) {
            fos.write(out.toByteArray());
        }
        System.out.println("已生成 " + target + " (" + out.size() + " B)");
    }

    private static void writeVlq(ByteArrayOutputStream out, int value) throws IOException {
        if (value < 0) {
            throw new IOException("负 tick");
        }
        int buffer = value & 0x7F;
        while ((value >>= 7) > 0) {
            buffer <<= 8;
            buffer |= ((value & 0x7F) | 0x80);
        }
        do {
            out.write(buffer & 0xFF);
            if ((buffer & 0x80) != 0) {
                buffer >>= 8;
            } else {
                break;
            }
        } while (true);
    }
}
