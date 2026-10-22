package io.github.sanchous327.antifreecam.mixin.blocks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.sanchous327.antifreecam.blocks.util.Arguments;
import io.github.sanchous327.antifreecam.blocks.util.Util;
import io.github.sanchous327.antifreecam.blocks.util.controller.ChunkPacketBlockController;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin {
    @WrapOperation(
        method = "<init>(Lnet/minecraft/world/level/chunk/PalettedContainerFactory;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainerFactory;createForBlockStates()Lnet/minecraft/world/level/chunk/PalettedContainer;"
        )
    )
    public PalettedContainer<BlockState> setPresetValuesArgument(PalettedContainerFactory instance, Operation<PalettedContainer<BlockState>> original) {
        BlockState[] presetValues = null;
        if (Arguments.CHUNK_ACCESS.isBound() && Arguments.CHUNK_SECTION_INDEX.isBound()) {
            ChunkAccess chunkAccess = Arguments.CHUNK_ACCESS.get();
            Integer chunkSectionIndex = Arguments.CHUNK_SECTION_INDEX.get();
            Level level = Util.getLevel(chunkAccess.levelHeightAccessor);
            ChunkPacketBlockController controller = Util.getBlockController(level);
            if (controller != null) {
                presetValues = controller.getPresetBlockStates(level, chunkSectionIndex << 4);
            }
        }
        return ScopedValue.where(Arguments.PRESET_VALUES, presetValues).call(() -> original.call(instance));
    }

    @WrapOperation(
        method = "<init>(Lnet/minecraft/world/level/chunk/PalettedContainerFactory;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainerFactory;createForBiomes()Lnet/minecraft/world/level/chunk/PalettedContainer;"
        )
    )
    public PalettedContainer<Holder<Biome>> setPacketInfoArgumentNull(PalettedContainerFactory instance, Operation<PalettedContainer<Holder<Biome>>> original) {
        return ScopedValue.where(Arguments.PACKET_INFO, null).where(Arguments.PRESET_VALUES, null).call(() -> original.call(instance));
    }

    @WrapOperation(
        method = "write",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainerRO;write(Lnet/minecraft/network/FriendlyByteBuf;)V"
        )
    )
    public void setPacketInfoArgumentNull(PalettedContainerRO<Holder<Biome>> instance, FriendlyByteBuf buf, Operation<Void> original) {
        ScopedValue.where(Arguments.PACKET_INFO, null).run(() -> original.call(instance, buf));
    }
}
