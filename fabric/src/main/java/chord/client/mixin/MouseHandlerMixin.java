package chord.client.mixin;

import chord.client.PlayState;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 演奏态内的滚轮 = 力度调节（上调强、下调弱），并拦截原版滚轮切快捷栏
 * （否则切栏会触发退出演奏态）。
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void chord$velocityScroll(long window, double xoffset, double yoffset, CallbackInfo ci) {
        if (PlayState.handleScroll(yoffset)) {
            ci.cancel();
        }
    }
}
