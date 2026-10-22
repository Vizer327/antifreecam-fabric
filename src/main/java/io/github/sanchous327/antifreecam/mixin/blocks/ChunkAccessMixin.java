package io.github.sanchous327.antifreecam.mixin.blocks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.sanchous327.antifreecam.blocks.util.Arguments;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {

    @WrapOperation(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;replaceMissingSections(Lnet/minecraft/world/level/chunk/PalettedContainerFactory;[Lnet/minecraft/world/level/chunk/LevelChunkSection;)V"
        )
    )
    public void setChunkAccessInstanceArgument(PalettedContainerFactory palettedContainerFactory, LevelChunkSection[] chunkSections, Operation<Void> original) {
        ScopedValue.where(Arguments.CHUNK_ACCESS, (ChunkAccess) (Object) this).run(() -> original.call(palettedContainerFactory, chunkSections));
    }

    @WrapOperation(
        method = "replaceMissingSections",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/level/chunk/PalettedContainerFactory;)Lnet/minecraft/world/level/chunk/LevelChunkSection;"
        )
    )
    private static LevelChunkSection setChunkSectionIndexArgument(PalettedContainerFactory palettedContainerFactory, Operation<LevelChunkSection> original, @Local int i) {
        ChunkAccess thisChunkAccess = Arguments.CHUNK_ACCESS.get(); // simulate instance method
        return ScopedValue.where(Arguments.CHUNK_SECTION_INDEX, thisChunkAccess.levelHeightAccessor.getSectionYFromSectionIndex(i)).call(() -> original.call(palettedContainerFactory));
    }

}
