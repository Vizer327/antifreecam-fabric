package io.github.sanchous327.antifreecam.mixin.blocks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// position of a block entity entry in a chunk packet (the record class is package-private)
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo")
public interface BlockEntityInfoAccessor {

    @Accessor("packedXZ")
    byte antifreecam$packedXZ();

    @Accessor("y")
    short antifreecam$y();
}
