package io.github.sanchous327.antifreecam.culling.platform.entity;

import io.github.sanchous327.antifreecam.culling.vector.AABB;
import io.github.sanchous327.antifreecam.culling.platform.command.PlatformCommandSender;
import io.github.sanchous327.antifreecam.culling.platform.world.PlatformWorld;
import io.github.sanchous327.antifreecam.culling.vector.Vec3d;
import org.jspecify.annotations.NullMarked;

import java.util.UUID;

@NullMarked
public interface PlatformEntity extends PlatformCommandSender {

    UUID getUniqueId();

    String getName();

    PlatformWorld getWorld();

    Vec3d getPosition();

    AABB getBoundingBox();

    void teleport(PlatformWorld world, double x, double y, double z);
}
