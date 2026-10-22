package chord.client.mixin;

import chord.client.PlayState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 演奏态内压制原版左键攻击与中键选取方块：
 * 三个鼠标键在演奏态内全部被 Chord 用作修饰键（左=降八度/右=升八度/中=升半位）；
 * 右键的使用由 UseItemCallback 拦截，不在此处理。
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void chord$suppressAttack(CallbackInfoReturnable<Boolean> cir) {
        if (PlayState.isPlaying()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
    private void chord$suppressPickBlock(CallbackInfo ci) {
        if (PlayState.isPlaying()) {
            ci.cancel();
        }
    }
}
