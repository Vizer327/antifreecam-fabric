package io.github.sanchous327.antifreecam.culling.fabric.platform;

import io.github.sanchous327.antifreecam.culling.platform.entity.PlatformPlayer;
import io.github.sanchous327.antifreecam.culling.vector.Vec3d;
import io.github.sanchous327.antifreecam.AntiFreecam;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class FabricPlayer extends FabricLivingEntity<ServerPlayer> implements PlatformPlayer {

    public FabricPlayer(FabricPlatform platform, ServerPlayer player) {
        super(platform, player);
    }

    public void replacePlayer(ServerPlayer player) {
        this.entity = player;
    }

    @Override
    public boolean shouldPreventCulling() {
        return !this.getDelegate().isAlive();
    }

    @Override
    public boolean isCullingExempt() {
        String tag = AntiFreecam.getInstance().getCullShip().getConfig().getDelegate().culling.exemptTag;
        return !tag.isEmpty() && this.getDelegate().entityTags().contains(tag);
    }

    @Override
    public boolean isSpectator() {
        return this.getDelegate().isSpectator();
    }

    @Override
    public int getTrackingDistance() {
        return this.getDelegate().requestedViewDistance();
    }

    @Override
    public Vec3d getDirection() {
        Vec3 dir = this.getDelegate().getForward();
        return new Vec3d(dir.x(), dir.y(), dir.z());
    }

    @Override
    public Vec3d getEyePosition() {
        Vec3 loc = this.getDelegate().getEyePosition();
        return new Vec3d(loc.x(), loc.y(), loc.z());
    }

    @Override
    public double getYaw() {
        return this.getDelegate().getYRot();
    }

    @Override
    public double getPitch() {
        return this.getDelegate().getXRot();
    }

    @Override
    public long getBlindnessTicks() {
        MobEffectInstance effect = this.getDelegate().getEffect(MobEffects.BLINDNESS);
        if (effect != null) {
            return effect.getDuration();
        }
        return -1;
    }

    @Override
    public boolean isGlowing() {
        return this.getDelegate().hasEffect(MobEffects.GLOWING);
    }

    @Override
    public long getDarknessTicks() {
        MobEffectInstance effect = this.getDelegate().getEffect(MobEffects.DARKNESS);
        if (effect != null) {
            return effect.getDuration();
        }
        return -1;
    }

    @Override
    public boolean canSeeNameTag(PlatformPlayer targetPlayer) {
        PlayerTeam targetTeam = this.getDelegate().level().getScoreboard().getPlayersTeam(targetPlayer.getName());
        if (targetTeam == null) {
            return true; // Always visible
        }
        return switch (targetTeam.getNameTagVisibility()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case HIDE_FOR_OTHER_TEAMS -> targetTeam.getPlayers().contains(this.getName());
            case HIDE_FOR_OWN_TEAM -> !targetTeam.getPlayers().contains(this.getName());
        };
    }

    @Override
    public boolean isSneaking() {
        return this.getDelegate().getLastClientInput().shift();
    }

    @Override
    public boolean isOnline() {
        return !this.getDelegate().hasDisconnected();
    }


    @Override
    public void addDirectEntityPairing(int[] entityIds) {
        // the entities just became visible - let vanilla tracking (through the culled TrackedEntity) pair them now
        ServerPlayer handle = this.getDelegate();
        handle.level().getServer().executeIfPossible(() -> {
            ChunkMap chunkMap = handle.level().getChunkSource().chunkMap;
            for (int id : entityIds) {
                ChunkMap.TrackedEntity tracked = chunkMap.entityMap.get(id);
                if (tracked != null) {
                    tracked.updatePlayer(handle);
                }
            }
        });
    }

    @Override
    public void addDirectPairing(PlatformPlayer... players) {
        ServerPlayer handle = this.getDelegate();
        handle.level().getServer().executeIfPossible(() -> {
            for (PlatformPlayer target : players) {
                ServerPlayer mcTarget = ((FabricPlayer) target).getDelegate();
                ChunkMap.TrackedEntity trackedTarget = mcTarget.level().getChunkSource().chunkMap.entityMap.get(mcTarget.getId());
                if (trackedTarget != null && trackedTarget.seenBy.add(handle.connection)) {
                    trackedTarget.serverEntity.addPairing(handle);
                }
            }
        });
    }
}
