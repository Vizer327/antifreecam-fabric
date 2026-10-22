package io.github.sanchous327.antifreecam.mixin.blocks;

import io.netty.channel.ChannelFutureListener;
import io.github.sanchous327.antifreecam.blocks.util.BlockReveal;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// live block changes (updates, block entity data, chest lids, cracks, break particles, block sounds) are filtered
// per player, so a hidden block doesn't leak when someone opens, breaks or places it
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class BlockPacketFilterMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void rtFilter(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && BlockReveal.filter(game, packet, listener)) {
            ci.cancel();
        }
    }
}
