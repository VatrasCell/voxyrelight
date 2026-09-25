package de.vatrascell.voxyrelight.mixin;

import de.vatrascell.voxyrelight.RelightManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    // Voxy shuts down at the end (TAIL) of the same method; a running job must have ended before that.
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void voxyrelight$cancelOnDisconnect(CallbackInfo ci) {
        RelightManager.cancelAndWait();
    }
}
