package io.github.sanchous327.antifreecam.mixin.blocks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.datafixers.util.Function4;
import io.github.sanchous327.antifreecam.blocks.interfaces.IChunkPacket;
import io.github.sanchous327.antifreecam.blocks.interfaces.IClientboundChunkBatchStartPacket;
import io.github.sanchous327.antifreecam.blocks.util.Arguments;
import io.github.sanchous327.antifreecam.blocks.util.ChunkPacketInfo;
import io.github.sanchous327.antifreecam.blocks.util.Util;
import io.github.sanchous327.antifreecam.blocks.util.controller.ChunkPacketBlockController;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ClientboundLevelChunkWithLightPacketMixin implements IChunkPacket {

    @Unique
    boolean antifreecam$ready = false;

    @Unique
    IClientboundChunkBatchStartPacket antifreecam$batchStartPacket;

    @WrapOperation(
        method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/level/chunk/LevelChunk;)Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;"
        )
    )
    private static ClientboundLevelChunkPacketData setChunkPacketInfoArgument(
        LevelChunk chunk, Operation<ClientboundLevelChunkPacketData> original,
        @Share("controller") LocalRef<ChunkPacketBlockController> controllerLocalRef,
        @Share("chunkPacketInfo") LocalRef<ChunkPacketInfo<BlockState>> chunkPacketInfoLocalRef,
        @Share("batchStartPacket") LocalRef<IClientboundChunkBatchStartPacket> batchStartPacketLocalRef
    ) {
        final ChunkPacketBlockController controller;
        if (Arguments.BATCH_START_PACKET.isBound() && Arguments.PACKET_LISTENER.isBound()) {
            batchStartPacketLocalRef.set(Arguments.BATCH_START_PACKET.get());
            var packetListener = Arguments.PACKET_LISTENER.get();
            controller = Util.getBlockController(packetListener.player);

        } else {
            // Chunk packets may not have the packet listener argument, if they are manually sent by other mods
            controller = Util.getBlockController(chunk.getLevel());
        }
        final ChunkPacketInfo<BlockState> packetInfo = controller.getChunkPacketInfo(chunk);
        // remember who this packet is for (block-list hiding needs the viewer's eye position)
        if (packetInfo != null && Arguments.PACKET_LISTENER.isBound()) {
            var viewer = Arguments.PACKET_LISTENER.get().player;
            packetInfo.rtSetViewer(viewer.getUUID(), viewer.getX(), viewer.getEyeY(), viewer.getZ());
        }

        controllerLocalRef.set(controller);
        chunkPacketInfoLocalRef.set(packetInfo);

        return ScopedValue.where(Arguments.PACKET_INFO, packetInfo).call(() -> original.call(chunk));
    }

    @Inject(
        method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
        at = @At("TAIL")
    )
    public void modifyBlocks(
        LevelChunk chunk, LevelLightEngine levelLightEngine, BitSet bitSet, BitSet bitSet2, CallbackInfo ci,
        @Share("controller") LocalRef<ChunkPacketBlockController> controllerLocalRef,
        @Share("chunkPacketInfo") LocalRef<ChunkPacketInfo<BlockState>> chunkPacketInfoLocalRef,
        @Share("batchStartPacket") LocalRef<IClientboundChunkBatchStartPacket> batchStartPacketLocalRef
    ) {
        this.antifreecam$batchStartPacket = batchStartPacketLocalRef.get();

        ChunkPacketInfo<BlockState> packetInfo = chunkPacketInfoLocalRef.get();
        ClientboundLevelChunkWithLightPacket packet = (ClientboundLevelChunkWithLightPacket) (Object) this;

        if (packetInfo != null) {
            packetInfo.setChunkPacket(packet);
        }
        controllerLocalRef.get().modifyBlocks(packet, packetInfo);
    }

    @ModifyArg(
        method = "<clinit>",
        index = 8,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/codec/StreamCodec;composite(Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lcom/mojang/datafixers/util/Function4;)Lnet/minecraft/network/codec/StreamCodec;"
        )
    )
    private static Function4<Integer, Integer, ClientboundLevelChunkPacketData, ClientboundLightUpdatePacketData, ClientboundLevelChunkWithLightPacket> markReady(
        Function4<Integer, Integer, ClientboundLevelChunkPacketData, ClientboundLightUpdatePacketData, ClientboundLevelChunkWithLightPacket> constructor
    ) {
        return (x, z, chunkData, lightData) -> {
            ClientboundLevelChunkWithLightPacket packet = constructor.apply(x, z, chunkData, lightData);
            IChunkPacket.antifreecam$setReady(packet, true);
            return packet;
        };
    }

    @Override
    public boolean isAntixray$ready() {
        return antifreecam$ready;
    }

    @Override
    public void antifreecam$setReady(boolean antifreecam$ready) {
        this.antifreecam$ready = antifreecam$ready;
        if (antifreecam$batchStartPacket != null) {
            // Chunk packets may not have a batch start packet, if they are manually sent by other mods
            antifreecam$batchStartPacket.antifreecam$notifyChunkReady();
        }
    }


}
