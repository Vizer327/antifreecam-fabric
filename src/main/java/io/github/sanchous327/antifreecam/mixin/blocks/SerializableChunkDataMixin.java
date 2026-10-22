package io.github.sanchous327.antifreecam.mixin.blocks;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.sanchous327.antifreecam.blocks.util.Arguments;
import io.github.sanchous327.antifreecam.blocks.util.Util;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.function.Function;

@Mixin(value = SerializableChunkData.class, priority = 1500)
public abstract class SerializableChunkDataMixin {

    @Definition(id = "getCompound", method = "Lnet/minecraft/nbt/CompoundTag;getCompound(Ljava/lang/String;)Ljava/util/Optional;")
    @Definition(id = "map", method = "Ljava/util/Optional;map(Ljava/util/function/Function;)Ljava/util/Optional;")
    @Expression("?.getCompound('block_states').map(?)")
    @WrapOperation(
        method = "parse",
        at = @At("MIXINEXTRAS:EXPRESSION")
    )
    private static Optional<PalettedContainer<BlockState>> addPresetArgument(
        Optional<PalettedContainer<BlockState>> instance, Function<? super CompoundTag, ? extends PalettedContainer<BlockState>> mapper,
        Operation<Optional<PalettedContainer<BlockState>>> original, @Local(argsOnly = true) LevelHeightAccessor levelHeight,
        // chunkSectionIndex = compoundTag3.getByteOr("Y", (byte)0);
        @Local(name = "y") int chunkSectionIndex
    ) {
        Level level = Util.getLevel(levelHeight);
        BlockState[] presetValues = Util.getBlockController(level).getPresetBlockStates(level, chunkSectionIndex << 4);

        return ScopedValue.where(Arguments.PRESET_VALUES, presetValues)
            .call(() -> original.call(instance, mapper));
    }

    @Definition(id = "getCompound", method = "Lnet/minecraft/nbt/CompoundTag;getCompound(Ljava/lang/String;)Ljava/util/Optional;")
    @Definition(id = "map", method = "Ljava/util/Optional;map(Ljava/util/function/Function;)Ljava/util/Optional;")
    @Expression("?.getCompound('biomes').map(?)")
    @WrapOperation(
        method = "parse",
        at = @At("MIXINEXTRAS:EXPRESSION")
    )
    private static Optional<PalettedContainerRO<Holder<Biome>>> addNullPresetArgument(
        Optional<PalettedContainerRO<Holder<Biome>>> instance, Function<? super CompoundTag, ? extends PalettedContainer<Holder<Biome>>> mapper,
        Operation<Optional<PalettedContainerRO<Holder<Biome>>>> original
    ) {
        return ScopedValue.where(Arguments.PRESET_VALUES, null)
            .call(() -> original.call(instance, mapper));
    }
}
