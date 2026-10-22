package io.github.sanchous327.antifreecam.culling.fabric.platform;

import io.github.sanchous327.antifreecam.AntiFreecam;
import io.github.sanchous327.antifreecam.culling.CullPlayer;
import io.github.sanchous327.antifreecam.culling.CullShip;
import io.github.sanchous327.antifreecam.culling.fabric.common.IServerLevel;
import io.github.sanchous327.antifreecam.culling.fabric.common.IServerPlayer;
import io.github.sanchous327.antifreecam.culling.fabric.util.SimpleScheduler;
import io.github.sanchous327.antifreecam.culling.platform.IPlatform;
import io.github.sanchous327.antifreecam.culling.platform.entity.PlatformPlayer;
import io.github.sanchous327.antifreecam.culling.platform.world.PlatformWorld;
import io.github.sanchous327.antifreecam.culling.util.OcclusionMappings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@NullMarked
public class FabricPlatform implements IPlatform {

    private final AntiFreecam mod;
    private final SimpleScheduler scheduler = new SimpleScheduler();
    private final OcclusionMappings occlusionMappings = new OcclusionMappings(Block.BLOCK_STATE_REGISTRY.size() * 8); // 8 voxels per block
    private MinecraftServer server;
    private long currentTick;

    public FabricPlatform(AntiFreecam mod) {
        this.mod = mod;
    }

    @Override
    @Unmodifiable
    public Collection<PlatformWorld> getWorlds() {
        List<PlatformWorld> worlds = new ArrayList<>();
        for (ServerLevel level : this.getServer().getAllLevels()) {
            FabricWorld world = ((IServerLevel) level).getCullWorld();
            if (world != null) {
                worlds.add(world);
            }
        }
        return Collections.unmodifiableList(worlds);
    }

    @Override
    public PlatformPlayer getPlayer(UUID playerId) {
        ServerPlayer player = this.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) {
            throw new IllegalArgumentException("Can't find player with uuid " + playerId);
        }
        return this.providePlayer(player);
    }

    @Override
    public long getCurrentTick() {
        return this.currentTick;
    }

    @Override
    public void tick() {
        this.currentTick++;
    }

    @Override
    public @Unmodifiable Set<FabricPlayer> getPlayers() {
        Set<FabricPlayer> players = new HashSet<>();
        for (ServerPlayer player : this.getServer().getPlayerList().getPlayers()) {
            players.add(this.providePlayer(player));
        }
        return Collections.unmodifiableSet(players);
    }

    @Override
    public int runTaskLaterAsync(Runnable runnable, long delay) {
        return this.scheduler.scheduleDelayed(runnable, delay);
    }

    @Override
    public int runTaskRepeatingAsync(Runnable runnable, long delay, long period) {
        return this.scheduler.scheduleRepeating(runnable, delay, period);
    }

    @Override
    public void cancelTask(int taskId) {
        this.scheduler.cancel(taskId);
    }

    public void shutdownScheduler() {
        try {
            this.scheduler.shutdown();
        } catch (InterruptedException ignored) {
        }
    }

    public FabricPlayer providePlayer(ServerPlayer player) {
        return ((IServerPlayer) player).getCullPlayerOrCreate();
    }

    @SuppressWarnings("unchecked")
    public <I extends Entity, T extends FabricEntity<I>> T provideEntity(I entity) {
        return (T) switch (entity) {
            case ServerPlayer player -> this.providePlayer(player);
            case LivingEntity livingEntity -> new FabricLivingEntity<>(this, livingEntity);
            default -> new FabricEntity<>(this, entity);
        };
    }

    public void invalidatePlayer(ServerPlayer player) {
        CullShip ship = this.mod.getCullShip();
        ship.removePlayer(player.getUUID());
        // queue invalidation from hidden set
        for (CullPlayer cullPlayer : ship.getPlayers()) {
            cullPlayer.invalidateOther(player.getUUID());
        }
    }

    @SuppressWarnings("deprecation")
    public MinecraftServer getServer() {
        if (this.server == null) {
            this.server = (MinecraftServer) FabricLoader.getInstance().getGameInstance();
        }
        return this.server;
    }

    public OcclusionMappings getOcclusionMappings() {
        return this.occlusionMappings;
    }

    public AntiFreecam getMod() {
        return this.mod;
    }
}
