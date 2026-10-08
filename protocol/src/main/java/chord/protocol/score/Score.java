package chord.protocol.score;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chord 乐谱（内存谱）：客户端 MIDI 转谱（{@link MidiConverter}）的产物，
 * 供自动演奏引擎按毫秒时刻调度 note-on/off。
 *
 * <p>谱不再有任何持久化载体：0.7.11 移除服务端导入/录制成书，0.7.15 移除客户端
 * 乐谱书播放（旧 CHSC 二进制 / 成书 Base64 编码随之删除）——每次播放都从
 * {@code .mid} 文件现转现用。
 *
 * <p>字段语义：
 * <ul>
 * <li>bpmMilli / 拍号：仅展示用（HUD 的小节进度划分），实际时值已烘焙为毫秒；
 * <li>transpose：整谱移调（半音），播放时加在每个音上（转谱时适配乐器音域的结果）；
 * <li>notes：按 startMs 升序的音符表，音高相对中央 C（C4）。
 * </ul>
 */
public final class Score {

    public static final int VERSION = 2;
    /** 基准力度：×1.0 音量，常规采样层；无力度来源的缺省值。 */
    public static final int DEFAULT_VELOCITY = 100;

    /** pitch（相对 C4 半音）上下限：MIDI 0~127。 */
    public static final int MIN_PITCH = -60;
    public static final int MAX_PITCH = 67;
    public static final int MIN_TRANSPOSE = -24;
    public static final int MAX_TRANSPOSE = 24;
    public static final int MAX_TITLE_CHARS = 64;

    /**
     * 一个音符：开始毫秒（首个音符恒为 0）、相对 C4 半音、持续毫秒、力度 1~127。
     * 三参构造 = 无力度来源（默认 {@link #DEFAULT_VELOCITY}）。
     */
    public record Note(int startMs, int pitch, int durationMs, int velocity) {

        public Note {
            if (durationMs < 1) {
                throw new IllegalArgumentException("durationMs 必须 ≥ 1: " + durationMs);
            }
            if (pitch < MIN_PITCH || pitch > MAX_PITCH) {
                throw new IllegalArgumentException("pitch 超出 [" + MIN_PITCH + "," + MAX_PITCH + "]: " + pitch);
            }
            if (startMs < 0) {
                throw new IllegalArgumentException("startMs 不能为负: " + startMs);
            }
            if (velocity < 1 || velocity > 127) {
                throw new IllegalArgumentException("velocity 超出 [1,127]: " + velocity);
            }
        }

        public Note(int startMs, int pitch, int durationMs) {
            this(startMs, pitch, durationMs, DEFAULT_VELOCITY);
        }
    }

    public final String title;
    /** BPM × 1000（毫BPM），如 120.5 BPM = 120500。 */
    public final int bpmMilli;
    public final int timeSigNum;
    public final int timeSigDen;
    public final int transpose;
    public final List<Note> notes; // 已按 startMs 升序

    public Score(String title, int bpmMilli, int timeSigNum, int timeSigDen,
                 int transpose, List<Note> notes) {
        if (title == null || title.isEmpty()) {
            throw new IllegalArgumentException("曲名不能为空");
        }
        if (title.length() > MAX_TITLE_CHARS) {
            throw new IllegalArgumentException("曲名超过 " + MAX_TITLE_CHARS + " 字符: " + title.length());
        }
        if (bpmMilli <= 0) {
            throw new IllegalArgumentException("bpmMilli 必须 > 0: " + bpmMilli);
        }
        if (timeSigNum < 1 || timeSigNum > 32 || timeSigDen < 1 || timeSigDen > 32) {
            throw new IllegalArgumentException("非法拍号: " + timeSigNum + "/" + timeSigDen);
        }
        if (transpose < MIN_TRANSPOSE || transpose > MAX_TRANSPOSE) {
            throw new IllegalArgumentException("移调超出 [" + MIN_TRANSPOSE + "," + MAX_TRANSPOSE + "]: " + transpose);
        }
        if (notes == null || notes.isEmpty()) {
            throw new IllegalArgumentException("乐谱至少要有一个音符");
        }
        // 音符数与整谱时长不设上限（0.7.13 前有 5000 音 / 15 分钟上限；
        // 超长曲如 Rush E 全曲 11178 音）
        List<Note> sorted = new ArrayList<>(notes);
        sorted.sort(Comparator.comparingInt(Note::startMs));
        this.title = title;
        this.bpmMilli = bpmMilli;
        this.timeSigNum = timeSigNum;
        this.timeSigDen = timeSigDen;
        this.transpose = transpose;
        this.notes = List.copyOf(sorted);
    }

    /** 播放用的最终半音（相对乐器中心音）：谱面 pitch + 整谱移调。 */
    public int semitoneOf(Note note) {
        return note.pitch + transpose;
    }

    /** 整谱时长（毫秒，含最后一个音的持续）。 */
    public long durationMs() {
        Note last = notes.get(notes.size() - 1);
        return last.startMs + (long) last.durationMs;
    }

    public double bpm() {
        return bpmMilli / 1000.0;
    }

    @Override
    public String toString() {
        return "Score{\"" + title + "\", bpm=" + bpm() + ", " + timeSigNum + "/" + timeSigDen
                + ", transpose=" + transpose + ", notes=" + notes.size()
                + ", duration=" + durationMs() / 1000 + "s}";
    }
}
