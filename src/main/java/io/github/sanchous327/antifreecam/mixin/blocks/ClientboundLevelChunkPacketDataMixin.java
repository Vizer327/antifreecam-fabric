package io.github.sanchous327.antifreecam.mixin.blocks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import io.github.sanchous327.antifreecam.blocks.util.Arguments;
import io.github.sanchous327.antifreecam.blocks.util.ChunkPacketInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ClientboundLevelChunkPacketDataMixin {
    @Shadow
    @Final
    private byte[] buffer;

    @Shadow
    @Final
    private java.util.List<Object> blockEntitiesData;

    // block entity data (sign text, chest type, ...) of hidden blocks must not reach the client either
    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("TAIL"))
    private void rtStripHiddenBlockEntities(LevelChunk chunk, CallbackInfo ci) {
        if (!Arguments.PACKET_INFO.isBound()) {
            return;
        }
        if (!(Arguments.PACKET_INFO.get() instanceof io.github.sanchous327.antifreecam.blocks.util.ChunkPacketInfoAntiXray info) || info.rtViewer() == null) {
            return;
        }
        var controller = info.getController();
        if (!controller.rtEnabled()) {
            return;
        }
        int baseX = chunk.getPos().x() << 4;
        int baseZ = chunk.getPos().z() << 4;
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        this.blockEntitiesData.removeIf(entry -> {
            BlockEntityInfoAccessor accessor = (BlockEntityInfoAccessor) entry;
            int packed = accessor.antifreecam$packedXZ() & 0xFF;
            int x = baseX + (packed >> 4);
            int z = baseZ + (packed & 15);
            int y = accessor.antifreecam$y();
            return controller.rtIsFar(info, x, y, z) && controller.rtShouldHideAt(chunk, chunk.getBlockState(pos.set(x, y, z)), x, y, z);
        });
    }

    @Inject(
        method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;extractChunkData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/world/level/chunk/LevelChunk;)V"
        )
    )
    private void setChunkPacketInfoBuffer(LevelChunk levelChunk, CallbackInfo ci) {
        if (Arguments.PACKET_INFO.isBound()) {
            ChunkPacketInfo<BlockState> chunkPacketInfo = Arguments.PACKET_INFO.get();
            if (chunkPacketInfo != null) {
                chunkPacketInfo.setBuffer(this.buffer);
            }
        }
    }

    @Inject(method = "extractChunkData", at = @At("HEAD"))
    private static void initializeChunkSectionIndex(FriendlyByteBuf friendlyByteBuf, LevelChunk levelChunk, CallbackInfo ci, @Share("chunkSectionIndex") LocalIntRef chunkSectionIndexRef) {
        chunkSectionIndexRef.set(0);
    }

    @WrapOperation(
        method = "extractChunkData",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;write(Lnet/minecraft/network/FriendlyByteBuf;)V"
        )
    )
    private static void setChunkSectionIndexArgument(LevelChunkSection instance, FriendlyByteBuf friendlyByteBuf, Operation<Void> original, @Share("chunkSectionIndex") LocalIntRef chunkSectionIndexRef) {
        int chunkSectionIndex = chunkSectionIndexRef.get();
        // the section starts with its non-air block count - block hiding fixes it after replacing blocks
        if (Arguments.PACKET_INFO.isBound() && Arguments.PACKET_INFO.get() != null) {
            Arguments.PACKET_INFO.get().setCountIndex(chunkSectionIndex, friendlyByteBuf.writerIndex());
        }

        ScopedValue.where(Arguments.CHUNK_SECTION_INDEX, chunkSectionIndex).run(() -> original.call(instance, friendlyByteBuf));

        chunkSectionIndexRef.set(chunkSectionIndex + 1);
    }
}
