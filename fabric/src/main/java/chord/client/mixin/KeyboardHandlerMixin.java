package chord.client.mixin;

import chord.client.PlayState;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 演奏态内，音级键的键盘事件在分发源头被消费：
 * 不进入原版 KeyMapping 处理，也不会触发其他 Mod 注册在同一物理键上的绑定
 * （问题二：避免我们的快捷键触发别的 Mod）。
 */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void chord$interceptNoteKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (PlayState.handleKeyEvent(action, event)) {
            ci.cancel();
        }
    }
}
