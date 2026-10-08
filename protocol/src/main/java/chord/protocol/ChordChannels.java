package chord.protocol;

/**
 * Chord 通信频道与协议版本常量。
 *
 * <p>频道名必须带命名空间（Paper Messenger 硬性要求）。
 * 客户端（Fabric 自定义 payload）与服务端（Bukkit Messenger）两侧以相同字符串对上。
 */
public final class ChordChannels {

    /** C2S：客户端 -> 服务端，演奏一个音符。 */
    public static final String PLAY = "chord:play";

    /** C2S：客户端 -> 服务端，自动演奏开始/结束通告（客户端 MIDI 播放 + 自动演奏键）。 */
    public static final String AUTO = "chord:auto";

    /** C2S：客户端 -> 服务端，延音踏板踩下/松开（仅 sustain 释放模式乐器，如钢琴）。 */
    public static final String PEDAL = "chord:pedal";

    /** S2C：服务端 -> 客户端，下发乐器定义表（握手）。 */
    public static final String SYNC = "chord:sync";

    /**
     * S2C：服务端 -> 客户端，下发乐器复音容量表（随 sync 一并发出）。
     * v6 之上的兼容扩展，不改动 sync 线格式——旧客户端未注册本频道，静默忽略。
     */
    public static final String POLY = "chord:poly";

    /** S2C：服务端 -> 客户端，进入/退出世界乐器演奏态（如钢琴交互实体）。 */
    public static final String ENTER = "chord:enter";

    /**
     * 协议版本；不一致时客户端应禁用演奏并提示升级。
     * v2：chord:auto 与乐谱自动演奏；v3：释放模式（钢琴衰减）+ 硬止音；
     * v4：乐器标签规则（鼓件名）；v5：延音踏板（chord:pedal + FLAG_SUSTAIN + sustain 释放模式）；
     * v6：NotePayload 加力度字节（velocity 1~127，100=基准）+ 服务端力度层路由；
     * v6 兼容扩展：chord:poly 复音容量表（独立频道，sync 线格式不动，版本号不变）。
     */
    public static final int PROTOCOL_VERSION = 6;

    private ChordChannels() {}
}
