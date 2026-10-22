package io.github.sanchous327.antifreecam.mixin.blocks;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.sanchous327.antifreecam.blocks.config.WorldConfig;
import io.github.sanchous327.antifreecam.blocks.interfaces.ILevel;
import io.github.sanchous327.antifreecam.blocks.util.controller.ChunkPacketBlockController;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(Level.class)
public abstract class LevelMixin implements ILevel, LevelAccessor {

    @Unique
    public ChunkPacketBlockController chunkPacketBlockController;

    @Shadow
    @Final
    private ResourceKey<Level> dimension;

    @Shadow
    public abstract BlockState getBlockState(BlockPos blockPos);

    @Override
    public void initValues() {
        if ((Object) this instanceof ServerLevel serverLevel) {
            WorldConfig worldConfig = new WorldConfig(this.dimension.identifier());
            this.chunkPacketBlockController = worldConfig.createChunkPacketBlockController(serverLevel);
        } else {
            throw new IllegalStateException();
        }
    }

    @Override
    public ChunkPacketBlockController getChunkPacketBlockController() {
        return this.chunkPacketBlockController;
    }

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    public void onBlockChange(final BlockPos blockPos, final BlockState blockState, final int flags, final int maxUpdateDepth, final CallbackInfoReturnable<Boolean> cir, @Local final LevelChunk levelChunk) {
        if ((Object) this instanceof ServerLevel serverLevel) {
            final BlockState oldState = levelChunk.getBlockState(blockPos);
            this.chunkPacketBlockController.onBlockChange(serverLevel, blockPos, blockState, oldState, flags, maxUpdateDepth);
        }
    }
}
