package io.github.sanchous327.antifreecam.culling.fabric;

import io.github.sanchous327.antifreecam.AntiFreecam;
import io.github.sanchous327.antifreecam.culling.CullPlayer;
import io.github.sanchous327.antifreecam.culling.platform.entity.PlatformPlayer;
import io.github.sanchous327.antifreecam.culling.platform.world.CulledEntity;
import io.github.sanchous327.antifreecam.culling.fabric.common.IServerLevel;
import io.github.sanchous327.antifreecam.culling.fabric.platform.FabricWorld;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.List;

public class CullingListener implements
        ServerPlayConnectionEvents.Join,
        ServerPlayConnectionEvents.Disconnect,
        ServerPlayerEvents.AfterRespawn,
        ServerTickEvents.EndLevelTick,
        ServerLifecycleEvents.ServerStopped {

    private final AntiFreecam mod;

    public CullingListener(AntiFreecam mod) {
        this.mod = mod;
    }

    public void register() {
        ServerPlayConnectionEvents.JOIN.register(this);
        ServerPlayConnectionEvents.DISCONNECT.register(this);
        ServerPlayerEvents.AFTER_RESPAWN.register(this);
        ServerTickEvents.END_LEVEL_TICK.register(this);
        ServerLifecycleEvents.SERVER_STOPPED.register(this);
    }

    @Override
    public void onPlayReady(ServerGamePacketListenerImpl serverGamePacketListener, PacketSender packetSender, MinecraftServer minecraftServer) {
        PlatformPlayer platformPlayer = this.mod.getPlatform().providePlayer(serverGamePacketListener.player);
        this.mod.getCullShip().addPlayer(new CullPlayer(this.mod.getCullShip(), platformPlayer));
    }

    @Override
    public void onPlayDisconnect(ServerGamePacketListenerImpl serverGamePacketListener, MinecraftServer minecraftServer) {
        this.mod.getPlatform().invalidatePlayer(serverGamePacketListener.player);
    }

    @Override
    public void afterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        CullPlayer player = this.mod.getCullShip().getPlayer(newPlayer.getUUID());
        if (player == null) {
            return; // cull player is null, ignore
        }
        player.setSpectating(false);
    }

    @Override
    public void onEndTick(ServerLevel serverLevel) {
        if (serverLevel.dimension() == ServerLevel.OVERWORLD) {
            // only tick platform once
            this.mod.getPlatform().tick();
        }
        // tick wrapped world (if it exists)
        FabricWorld world = ((IServerLevel) serverLevel).getCullWorld();
        if (world != null) {
            world.tick();
        }
        // once a second re-run vanilla tracking for every pair of players in this level.
        // Vanilla only re-pairs on section changes, and culling can lose a pairing (death, respawn,
        // spectator mode, teleports, hidden set cleared without re-pairing) - then a player stays
        // invisible until he moves. updatePlayer() goes through our culled TrackedEntity, so hidden
        // players stay hidden and vanilla rules (distance, spectators) still apply.
        if (serverLevel.getGameTime() % 20L == 0L) {
            resyncTracking(serverLevel);
        }
    }

    private static void resyncTracking(ServerLevel level) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        List<ServerPlayer> players = level.players();
        for (ServerPlayer target : players) {
            ChunkMap.TrackedEntity tracked = chunkMap.entityMap.get(target.getId());
            if (tracked == null) {
                continue;
            }
            for (ServerPlayer viewer : players) {
                if (viewer != target) {
                    tracked.updatePlayer(viewer);
                }
            }
        }
        // the same for culled non-player entities near players (hides newly occluded ones, shows lost ones)
        FabricWorld cullWorld = ((IServerLevel) level).getCullWorld();
        if (cullWorld == null) {
            return;
        }
        for (CulledEntity entity : cullWorld.getCulledEntities()) {
            ChunkMap.TrackedEntity tracked = chunkMap.entityMap.get(entity.id());
            if (tracked == null) {
                continue;
            }
            for (ServerPlayer viewer : players) {
                tracked.updatePlayer(viewer);
            }
        }
    }

    @Override
    public void onServerStopped(MinecraftServer server) {
        AntiFreecam.getInstance().getPlatform().shutdownScheduler();
    }

}
