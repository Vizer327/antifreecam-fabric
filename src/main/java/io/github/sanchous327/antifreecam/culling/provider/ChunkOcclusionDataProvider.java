package io.github.sanchous327.antifreecam.culling.provider;


import io.github.sanchous327.antifreecam.culling.CullPlayer;
import io.github.sanchous327.antifreecam.culling.cache.DataProvider;
import io.github.sanchous327.antifreecam.culling.cache.OcclusionChunkCache;
import io.github.sanchous327.antifreecam.culling.cache.OcclusionWorldCache;
import io.github.sanchous327.antifreecam.culling.platform.world.PlatformWorld;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ChunkOcclusionDataProvider implements DataProvider {

    private static final int POS2CHUNK_SHIFT = 4 + 1;

    private final CullPlayer player;
    private PlatformWorld world;
    private OcclusionWorldCache cache;
    private OcclusionChunkCache chunk;

    public ChunkOcclusionDataProvider(CullPlayer player) {
        this.player = player;
    }

    @Override
    public final void world(PlatformWorld world) {
        if (!world.equals(this.world)) {
            this.world = world;
            this.cache = world.getOcclusionWorldCache();
        }
    }

    private final void prepareChunk(int chunkX, int chunkZ) {
        OcclusionChunkCache chunk = this.chunk;
        if (chunk == null || chunk.x != chunkX || chunk.z != chunkZ) {
            this.chunk = this.cache.chunk(chunkX, chunkZ);
        }
    }

    @Override
    public final boolean isOpaqueFullCube(int x, int y, int z) {
        this.prepareChunk(x >> POS2CHUNK_SHIFT, z >> POS2CHUNK_SHIFT);
        return this.chunk.isVoxelOccluded(x, y, z);
    }

    @Override
    public final int getPlayerViewDistance() {
        if (this.world != null) {
            return this.world.getTrackingDistance(this.player.getPlatformPlayer());
        }
        return -1;
    }
}
