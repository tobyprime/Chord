package chord.protocol.score;

/**
 * 半音 → 简谱名（十二半音全覆盖，含八度前缀）。
 *
 * <p>客户端 HUD 显示与转换器提示共用（0.7.11 前服务端乐谱书页也用同一份名字）。
 */
public final class NoteNames {

    private static final String[] SOLFEGE = {"1", "1♯", "2", "2♯", "3", "4", "4♯", "5", "5♯", "6", "6♯", "7"};

    private NoteNames() {}

    /** 如 0→"1"、1→"1♯"、13→"高1♯"、-11→"低1♯"、-24→"低低1"。 */
    public static String solfege(int semitone) {
        int octave = Math.floorDiv(semitone, 12);
        int within = Math.floorMod(semitone, 12);
        String prefix = switch (octave) {
            case -1 -> "低";
            case 0 -> "";
            case 1 -> "高";
            default -> octave > 0 ? "高" + "高".repeat(octave - 1) : "低" + "低".repeat(-octave - 1);
        };
        return prefix + SOLFEGE[within];
    }
}
