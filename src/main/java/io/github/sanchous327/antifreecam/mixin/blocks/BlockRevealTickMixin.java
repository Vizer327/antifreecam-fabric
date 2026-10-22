package io.github.sanchous327.antifreecam.mixin.blocks;

import io.github.sanchous327.antifreecam.blocks.util.BlockReveal;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

// reveal hidden block-list blocks that players can see (server thread, once per tick)
@Mixin(MinecraftServer.class)
public abstract class BlockRevealTickMixin {

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void rtRevealTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        BlockReveal.tick((MinecraftServer) (Object) this);
    }
}
