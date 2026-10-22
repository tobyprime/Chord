package chord;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

/**
 * allinone 的 mixin 配置过滤：两个 mixins 配置（chord.v26 / chord.v1211）都会被
 * Mixin 加载，按真实游戏版本只放行对应一套的 mixin——错版本 mixin 的注入目标
 * 方法不存在（如 pickBlock vs pickBlockOrEntity），不过滤会直接炸掉游戏。
 */
public final class AllInOneMixinGate implements IMixinConfigPlugin {

    /** 1.21.x = 混淆映射时代（intermediary 命名套件），26.x = 无混淆时代。 */
    static boolean isLegacyMc() {
        return AllInOne.mcVersion().startsWith("1.");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean legacy = isLegacyMc();
        if (mixinClassName.startsWith("chord.v1211.client.mixin")) {
            return legacy;
        }
        if (mixinClassName.startsWith("chord.v26.client.mixin")) {
            return !legacy;
        }
        return false; // 未知来源 mixin 一律不放行
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
