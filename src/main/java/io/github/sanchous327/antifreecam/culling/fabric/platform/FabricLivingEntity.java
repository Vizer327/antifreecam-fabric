package io.github.sanchous327.antifreecam.culling.fabric.platform;

import io.github.sanchous327.antifreecam.culling.platform.entity.PlatformLivingEntity;
import io.github.sanchous327.antifreecam.culling.vector.Vec3i;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public class FabricLivingEntity<T extends LivingEntity> extends FabricEntity<T> implements PlatformLivingEntity {

    public FabricLivingEntity(FabricPlatform platform, T sender) {
        super(platform, sender);
    }

    @Override
    public Vec3i getTargetBlock(int maxDistance) {
        T delegate = this.getDelegate();
        Vec3 startVec = delegate.getEyePosition();
        Vec3 endVec = startVec.add(delegate.getLookAngle().multiply(maxDistance, maxDistance, maxDistance));

        BlockHitResult result = delegate.level()
                .clip(new ClipContext(startVec, endVec, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (result.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos blockPos = result.getBlockPos();

        return new Vec3i(blockPos.getX(), blockPos.getY(), blockPos.getZ());
    }
}
