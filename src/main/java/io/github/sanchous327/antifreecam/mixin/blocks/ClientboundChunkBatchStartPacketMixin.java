package io.github.sanchous327.antifreecam.mixin.blocks;

import io.netty.buffer.ByteBuf;
import io.github.sanchous327.antifreecam.blocks.interfaces.IClientboundChunkBatchStartPacket;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientboundChunkBatchStartPacket.class)
public abstract class ClientboundChunkBatchStartPacketMixin implements IClientboundChunkBatchStartPacket {
    @Redirect(
        method = "<clinit>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/codec/StreamCodec;unit(Ljava/lang/Object;)Lnet/minecraft/network/codec/StreamCodec;"
        )
    )
    private static StreamCodec<ByteBuf, ClientboundChunkBatchStartPacket> allowNonSingleton(Object instance) {
        return new StreamCodec<>() {
            @Override
            public ClientboundChunkBatchStartPacket decode(ByteBuf object) {
                return ClientboundChunkBatchStartPacket.INSTANCE;
            }

            @Override
            public void encode(ByteBuf object, ClientboundChunkBatchStartPacket object2) {
                // Codec.unit throws an error if the encoded object is not the supplied singleton instance
            }
        };
    }

    @Unique
    private int antifreecam$batchSize;

    @Unique
    private int antifreecam$readyPackets = 0;

    @Override
    public void antifreecam$setBatchSize(int batchSize) {
        this.antifreecam$batchSize = batchSize;
    }

    @Override
    public synchronized void antifreecam$notifyChunkReady() {
        antifreecam$readyPackets++;
    }

    /**
     * We need to delay the chunk batch start packet until all of its packets are ready.
     * This is to ensure that all packets between batch start and end can be sent to the client immediately, allowing 
     * the client to correctly calculate its desired batch size.
     */
    @Override
    public boolean isAntixray$ready() {
        return antifreecam$readyPackets >= antifreecam$batchSize;
    }
}
