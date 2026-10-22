package io.github.sanchous327.antifreecam.culling.platform.world;

import io.github.sanchous327.antifreecam.culling.cache.OcclusionWorldCache;
import io.github.sanchous327.antifreecam.culling.platform.IPlatform;
import io.github.sanchous327.antifreecam.culling.platform.entity.PlatformPlayer;
import io.github.sanchous327.antifreecam.culling.util.TickRefreshSupplier;
import io.github.sanchous327.antifreecam.culling.vector.Vec3d;
import io.github.sanchous327.antifreecam.culling.vector.Vec3i;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

@NullMarked
public abstract class PlatformWorld {

    protected final OcclusionWorldCache cache = new OcclusionWorldCache(this);
    protected final TickRefreshSupplier<List<PlatformPlayer>> playersInWorld;

    public PlatformWorld(IPlatform platform) {
        this.playersInWorld = new TickRefreshSupplier<>(platform, this::getPlayers0);
    }

    public OcclusionWorldCache getOcclusionWorldCache() {
        return this.cache;
    }

    @Nullable
    public abstract PlatformChunkAccess getChunkAccess(int x, int z);

    public abstract String getName();

    public abstract String getKey();

    // Exclusive
    public abstract int getMinY();

    // Exclusive
    public abstract int getMaxY();

    public abstract int getTrackingDistance();

    public abstract int getTrackingDistance(PlatformPlayer player);

    public List<PlatformPlayer> getPlayers() {
        return this.playersInWorld.get();
    }

    public int getPlayerCount() {
        return this.playersInWorld.get().size();
    }

    protected abstract List<PlatformPlayer> getPlayers0();

    /**
     * Non-player entities near players that may be hidden, snapshot from the server thread
     */
    public List<CulledEntity> getCulledEntities() {
        return List.of();
    }

    @Nullable
    public abstract Vec3d rayTraceBlocks(Vec3d start, Vec3d dir, double maxDistance);


}
