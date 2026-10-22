package io.github.sanchous327.antifreecam.culling.fabric.platform;

import io.github.sanchous327.antifreecam.culling.vector.AABB;
import io.github.sanchous327.antifreecam.culling.platform.IPlatform;
import io.github.sanchous327.antifreecam.culling.platform.entity.PlatformEntity;
import io.github.sanchous327.antifreecam.culling.platform.world.PlatformWorld;
import io.github.sanchous327.antifreecam.culling.util.TickRefreshSupplier;
import io.github.sanchous327.antifreecam.culling.vector.Vec3d;
import io.github.sanchous327.antifreecam.culling.fabric.common.IServerLevel;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.NullMarked;

import java.util.Set;
import java.util.UUID;

// Can't build on top CommandSource
@NullMarked
public class FabricEntity<T extends Entity> implements PlatformEntity {

    protected final FabricPlatform platform;
    protected final TickRefreshSupplier<Vec3d> position;
    protected final TickRefreshSupplier<AABB> aabb;
    protected T entity;

    public FabricEntity(FabricPlatform platform, T sender) {
        this.platform = platform;
        this.entity = sender;
        // read the CURRENT delegate, not the constructor argument. On respawn the ServerPlayer object is
        // replaced (FabricPlayer.replacePlayer); capturing `sender` kept the dead player's position forever,
        // so culling raycast to the death spot (player invisible / flickering after the first death).
        this.position = new TickRefreshSupplier<>(platform, () -> {
            T current = this.entity;
            return new Vec3d(current.getX(), current.getY(), current.getZ());
        });
        this.aabb = new TickRefreshSupplier<>(platform, () -> {
            net.minecraft.world.phys.AABB boundingBox = this.entity.getBoundingBox();
            return new AABB(boundingBox.minX, boundingBox.minY, boundingBox.minZ, boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ);
        });
    }

    public final T getDelegate() {
        return this.entity;
    }

    @Override
    public IPlatform getPlatform() {
        return this.platform;
    }


    @Override
    public boolean hasPermission(String permission, boolean defaultValue) {
        return Permissions.check(this.entity, permission, defaultValue);
    }

    @Override
    public UUID getUniqueId() {
        return this.getDelegate().getUUID();
    }

    @Override
    public String getName() {
        return this.getDelegate().getScoreboardName();
    }

    @Override
    public PlatformWorld getWorld() {
        return ((IServerLevel) this.getDelegate().level()).getCullWorldOrCreate();
    }

    @Override
    public Vec3d getPosition() {
        return this.position.get();
    }

    @Override
    public AABB getBoundingBox() {
        return this.aabb.get();
    }

    @Override
    public void teleport(PlatformWorld world, double x, double y, double z) {
        this.getDelegate().teleportTo(((FabricWorld) world).getWorld(), x, y, z, Set.of(),
                this.getDelegate().getYRot(), this.getDelegate().getXRot(), true);
    }
}
