package io.github.sanchous327.antifreecam.mixin.blocks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.sanchous327.antifreecam.blocks.interfaces.IClientboundChunkBatchStartPacket;
import io.github.sanchous327.antifreecam.blocks.util.Arguments;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {

    // the client forgets this chunk - forget its hidden block-list positions too
    @org.spongepowered.asm.mixin.injection.Inject(method = "dropChunk", at = @At("HEAD"))
    private void rtDropChunk(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.level.ChunkPos pos,
                             org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        io.github.sanchous327.antifreecam.blocks.util.BlockReveal.dropChunk(player.getUUID(),
            net.minecraft.world.level.ChunkPos.pack(pos.x(), pos.z()));
    }

    @Redirect(
        method = "sendNextChunks",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/network/protocol/game/ClientboundChunkBatchStartPacket;INSTANCE:Lnet/minecraft/network/protocol/game/ClientboundChunkBatchStartPacket;",
            opcode = Opcodes.GETSTATIC
        )
    )
    private ClientboundChunkBatchStartPacket addBatchSizeArgument(
        @Local(name = "chunksToSend") List<LevelChunk> list, @Share("startPacket") LocalRef<IClientboundChunkBatchStartPacket> ipacketRef
    ) {
        ClientboundChunkBatchStartPacket startPacket = ClientboundChunkBatchStartPacketAccessor.init();
        IClientboundChunkBatchStartPacket iPacket = (IClientboundChunkBatchStartPacket) startPacket;
        iPacket.antifreecam$setBatchSize(list.size());
        ipacketRef.set(iPacket);
        return startPacket;
    }

    @WrapOperation(
        method = "sendNextChunks",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/PlayerChunkSender;sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V"
        )
    )
    private void setBatchStartPacketArgument(
        ServerGamePacketListenerImpl serverGamePacketListenerImpl, ServerLevel serverLevel, LevelChunk levelChunk,
        Operation<Void> original, @Share("startPacket") LocalRef<IClientboundChunkBatchStartPacket> ipacketRef
    ) {
        // Pass the batch start packet to the chunk packets
        ScopedValue.where(Arguments.BATCH_START_PACKET, ipacketRef.get())
            .where(Arguments.PACKET_LISTENER, serverGamePacketListenerImpl)
            .run(() -> original.call(serverGamePacketListenerImpl, serverLevel, levelChunk));
    }

}
