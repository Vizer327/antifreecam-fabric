package io.github.sanchous327.antifreecam.culling.platform.entity;

import io.github.sanchous327.antifreecam.culling.vector.Vec3i;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface PlatformLivingEntity extends PlatformEntity {

    Vec3i getTargetBlock(int maxDistance);
}
