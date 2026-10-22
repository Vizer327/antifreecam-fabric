package io.github.sanchous327.antifreecam.culling.fabric.common;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.NullMarked;

import java.util.Set;

@NullMarked
public interface ILevel {

    Set<BlockPos> getChangedBlocks();
}
