package io.github.sanchous327.antifreecam.mixin.blocks;

import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientboundChunkBatchStartPacket.class)
public interface ClientboundChunkBatchStartPacketAccessor {
    @Invoker("<init>")
    static ClientboundChunkBatchStartPacket init() {
        throw new AssertionError();
    }
}
