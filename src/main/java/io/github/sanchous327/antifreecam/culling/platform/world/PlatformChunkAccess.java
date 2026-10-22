package io.github.sanchous327.antifreecam.culling.platform.world;

import org.jspecify.annotations.NullMarked;

@NullMarked
public interface PlatformChunkAccess {

    int getBlockId(int x, int y, int z);

    boolean isOpaque(int x, int y, int z, int voxelIndex);
}
