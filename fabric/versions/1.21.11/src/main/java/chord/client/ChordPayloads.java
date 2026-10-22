package chord.client;

import chord.protocol.AutoPayload;
import chord.protocol.EnterPayload;
import chord.protocol.NotePayload;
import chord.protocol.PedalPayload;
import chord.protocol.PolyPayload;
import chord.protocol.SyncPayload;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 自定义 payload 定义与注册（26.x Fabric 网络栈：原版 CustomPacketPayload + StreamCodec 体系）。
 *
 * <p>线格式完全复用 chord-protocol 模块的 DataStream 编解码（经 ByteBuf 流桥接），
 * 保证与服务端 Bukkit Messenger 读到的字节一致。payload 的频道名即
 * chord:play / chord:sync，Paper 侧 Messenger 按同名频道收发。
 */
public final class ChordPayloads {

    /** C2S：演奏一个音符（velocity = 力度 1~127，note-off 时无意义填 0）。 */
    public record ChordNotePayload(int instrumentId, int semitone, int velocity, int flags) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ChordNotePayload> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("chord", "play"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ChordNotePayload> CODEC =
                StreamCodec.of(ChordNotePayload::write, ChordNotePayload::read);

        static void write(RegistryFriendlyByteBuf buf, ChordNotePayload value) {
            try (DataOutputStream out = new DataOutputStream(new ByteBufOutputStream(buf))) {
                new NotePayload(value.instrumentId, value.semitone, value.velocity, value.flags).write(out);
            } catch (IOException e) {
                throw new RuntimeException("编码 NotePayload 失败", e);
            }
        }

        static ChordNotePayload read(RegistryFriendlyByteBuf buf) {
            try (DataInputStream in = new DataInputStream(new ByteBufInputStream(buf))) {
                NotePayload np = NotePayload.read(in);
                return new ChordNotePayload(np.instrumentId, np.semitone, np.velocity, np.flags);
            } catch (IOException e) {
                throw new RuntimeException("解码 NotePayload 失败", e);
            }
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：自动演奏开始/结束通告（客户端 MIDI 自动演奏引擎）。 */
    public record ChordAutoPayload(int action, String title) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ChordAutoPayload> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("chord", "auto"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ChordAutoPayload> CODEC =
                StreamCodec.of(ChordAutoPayload::write, ChordAutoPayload::read);

        static void write(RegistryFriendlyByteBuf buf, ChordAutoPayload value) {
            try (DataOutputStream out = new DataOutputStream(new ByteBufOutputStream(buf))) {
                new AutoPayload(value.action, value.title).write(out);
            } catch (IOException e) {
                throw new RuntimeException("编码 AutoPayload 失败", e);
            }
        }

        static ChordAutoPayload read(RegistryFriendlyByteBuf buf) {
            try (DataInputStream in = new DataInputStream(new ByteBufInputStream(buf))) {
                AutoPayload ap = AutoPayload.read(in);
                return new ChordAutoPayload(ap.action, ap.title);
            } catch (IOException e) {
                throw new RuntimeException("解码 AutoPayload 失败", e);
            }
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：延音踏板踩下/松开（仅 sustain 释放模式乐器，如钢琴）。 */
    public record ChordPedalPayload(int action) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ChordPedalPayload> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("chord", "pedal"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ChordPedalPayload> CODEC =
                StreamCodec.of(ChordPedalPayload::write, ChordPedalPayload::read);

        static void write(RegistryFriendlyByteBuf buf, ChordPedalPayload value) {
            try (DataOutputStream out = new DataOutputStream(new ByteBufOutputStream(buf))) {
                new PedalPayload(value.action).write(out);
            } catch (IOException e) {
                throw new RuntimeException("编码 PedalPayload 失败", e);
            }
        }

        static ChordPedalPayload read(RegistryFriendlyByteBuf buf) {
            try (DataInputStream in = new DataInputStream(new ByteBufInputStream(buf))) {
                return new ChordPedalPayload(PedalPayload.read(in).action);
            } catch (IOException e) {
                throw new RuntimeException("解码 PedalPayload 失败", e);
            }
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：服务器下发乐器定义表。 */
    public record ChordSyncPayload(SyncPayload data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ChordSyncPayload> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("chord", "sync"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ChordSyncPayload> CODEC =
                StreamCodec.of(ChordSyncPayload::write, ChordSyncPayload::read);

        static void write(RegistryFriendlyByteBuf buf, ChordSyncPayload value) {
            try (DataOutputStream out = new DataOutputStream(new ByteBufOutputStream(buf))) {
                value.data.write(out);
            } catch (IOException e) {
                throw new RuntimeException("编码 SyncPayload 失败", e);
            }
        }

        static ChordSyncPayload read(RegistryFriendlyByteBuf buf) {
            try (DataInputStream in = new DataInputStream(new ByteBufInputStream(buf))) {
                return new ChordSyncPayload(SyncPayload.read(in));
            } catch (IOException e) {
                throw new RuntimeException("解码 SyncPayload 失败", e);
            }
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：服务器下发乐器复音容量表（v6 兼容扩展频道，旧客户端不注册、静默忽略）。 */
    public record ChordPolyPayload(PolyPayload data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ChordPolyPayload> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("chord", "poly"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ChordPolyPayload> CODEC =
                StreamCodec.of(ChordPolyPayload::write, ChordPolyPayload::read);

        static void write(RegistryFriendlyByteBuf buf, ChordPolyPayload value) {
            try (DataOutputStream out = new DataOutputStream(new ByteBufOutputStream(buf))) {
                value.data.write(out);
            } catch (IOException e) {
                throw new RuntimeException("编码 PolyPayload 失败", e);
            }
        }

        static ChordPolyPayload read(RegistryFriendlyByteBuf buf) {
            try (DataInputStream in = new DataInputStream(new ByteBufInputStream(buf))) {
                return new ChordPolyPayload(PolyPayload.read(in));
            } catch (IOException e) {
                throw new RuntimeException("解码 PolyPayload 失败", e);
            }
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：服务端通知进入/退出世界乐器（如钢琴交互实体）。 */
    public record ChordEnterPayload(EnterPayload data) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ChordEnterPayload> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("chord", "enter"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ChordEnterPayload> CODEC =
                StreamCodec.of(ChordEnterPayload::write, ChordEnterPayload::read);

        static void write(RegistryFriendlyByteBuf buf, ChordEnterPayload value) {
            try (DataOutputStream out = new DataOutputStream(new ByteBufOutputStream(buf))) {
                value.data.write(out);
            } catch (IOException e) {
                throw new RuntimeException("编码 EnterPayload 失败", e);
            }
        }

        static ChordEnterPayload read(RegistryFriendlyByteBuf buf) {
            try (DataInputStream in = new DataInputStream(new ByteBufInputStream(buf))) {
                return new ChordEnterPayload(EnterPayload.read(in));
            } catch (IOException e) {
                throw new RuntimeException("解码 EnterPayload 失败", e);
            }
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(ChordNotePayload.TYPE, ChordNotePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ChordAutoPayload.TYPE, ChordAutoPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ChordPedalPayload.TYPE, ChordPedalPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChordSyncPayload.TYPE, ChordSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChordPolyPayload.TYPE, ChordPolyPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChordEnterPayload.TYPE, ChordEnterPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(ChordSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() -> PlayState.onSync(payload.data())));
        ClientPlayNetworking.registerGlobalReceiver(ChordPolyPayload.TYPE, (payload, context) ->
                context.client().execute(() -> PlayState.onPoly(payload.data())));
        ClientPlayNetworking.registerGlobalReceiver(ChordEnterPayload.TYPE, (payload, context) ->
                context.client().execute(() -> PlayState.onEnterRemote(payload.data())));

        // 换服/断线时清空握手状态：sync 由新服务器在其上报注册频道后重新下发，
        // 避免上一个服务器的乐器表残留造成「已启用」误判
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> PlayState.resetConnection());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PlayState.resetConnection());
    }

    public static void sendNote(int instrumentId, int semitone, int flags) {
        // 旧签名：默认力度（自动演奏引擎用——谱面暂不带力度，走基准音量）
        sendNote(instrumentId, semitone, chord.protocol.NotePayload.DEFAULT_VELOCITY, flags);
    }

    public static void sendNote(int instrumentId, int semitone, int velocity, int flags) {
        ClientPlayNetworking.send(new ChordNotePayload(instrumentId, semitone, velocity, flags));
    }

    /** 发送自动演奏通告；断线瞬间可能未连接，静默吞掉。 */
    public static void sendAuto(int action, String title) {
        try {
            ClientPlayNetworking.send(new ChordAutoPayload(action, title));
        } catch (Exception ignored) {
            // 未连接到服务器
        }
    }

    /** 发送延音踏板事件；断线瞬间可能未连接，静默吞掉。 */
    public static void sendPedal(boolean down) {
        try {
            ClientPlayNetworking.send(new ChordPedalPayload(
                    down ? chord.protocol.PedalPayload.ACTION_DOWN : chord.protocol.PedalPayload.ACTION_UP));
        } catch (Exception ignored) {
            // 未连接到服务器
        }
    }

    private ChordPayloads() {}
}
