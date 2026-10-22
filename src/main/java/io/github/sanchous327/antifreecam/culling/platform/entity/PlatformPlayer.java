package io.github.sanchous327.antifreecam.culling.platform.entity;

import io.github.sanchous327.antifreecam.culling.vector.Vec3d;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface PlatformPlayer extends PlatformLivingEntity {

    /**
     * Can block culling for this player. For example, if the player is dead
     */
    boolean shouldPreventCulling();

    /**
     * This viewer sees everyone (scoreboard tag from the config, e.g. during a cutscene). Unlike
     * {@link #shouldPreventCulling()} it does not remove the player from other viewers' checks.
     */
    default boolean isCullingExempt() {
        return false;
    }

    boolean isSpectator();

    int getTrackingDistance();

    Vec3d getDirection();

    Vec3d getEyePosition();

    double getYaw();

    double getPitch();

    /**
     * @return the blindness ticks, or -1 if the player is not blind
     */
    long getBlindnessTicks();

    boolean isGlowing();

    /**
     * @return the darkness ticks, or -1 if the player is not dark
     */
    long getDarknessTicks();

    boolean canSeeNameTag(PlatformPlayer targetPlayer);

    boolean isSneaking();

    boolean isOnline();


    void addDirectPairing(PlatformPlayer... players);

    /**
     * Re-run tracking for these entity ids (they just became visible), on the server thread
     */
    default void addDirectEntityPairing(int[] entityIds) {
    }
}
