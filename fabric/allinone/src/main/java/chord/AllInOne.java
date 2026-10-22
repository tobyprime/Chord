package chord;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * allinone 入口：一个 jar 支持 1.21.11 / 26.2 / 26.3。
 *
 * <p>jar 内同时携带两套版本化字节码（chord.v1211.client.* = 1.21.x intermediary
 * 命名、chord.v26.client.* = 26.x 官方命名，互不重叠），这里探测真实游戏版本后
 * 只初始化对应的一套——通过 Class.forName 字符串引用，JVM 类懒加载保证另一套
 * 不被触碰（其引用的旧/新 API 缺失也无所谓）。
 */
public final class AllInOne implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        String impl = AllInOneMixinGate.isLegacyMc()
                ? "chord.v1211.client.ChordClient"
                : "chord.v26.client.ChordClient";
        try {
            ((ClientModInitializer) Class.forName(impl).getDeclaredConstructor().newInstance())
                    .onInitializeClient();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Chord: 无法加载版本实现 " + impl, e);
        }
    }

    /** minecraft 模组的版本串（"1.21.11" / "26.2" / "26.3"）。 */
    static String mcVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }
}
