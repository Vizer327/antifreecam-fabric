package io.github.sanchous327.antifreecam.culling.fabric.platform;

import io.github.sanchous327.antifreecam.culling.config.CullingConfig;
import io.github.sanchous327.antifreecam.culling.vector.AABB;
import io.github.sanchous327.antifreecam.AntiFreecam;
import io.github.sanchous327.antifreecam.culling.cache.OcclusionChunkCache;
import io.github.sanchous327.antifreecam.culling.platform.world.CulledEntity;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import io.github.sanchous327.antifreecam.culling.platform.entity.PlatformPlayer;
import io.github.sanchous327.antifreecam.culling.platform.world.PlatformChunkAccess;
import io.github.sanchous327.antifreecam.culling.platform.world.PlatformWorld;
import io.github.sanchous327.antifreecam.culling.vector.Vec3d;
import io.github.sanchous327.antifreecam.culling.vector.Vec3i;
import io.github.sanchous327.antifreecam.culling.fabric.common.ILevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@NullMarked
public class FabricWorld extends PlatformWorld {


    private final ServerLevel world;
    private final FabricPlatform platform;
    private volatile List<CulledEntity> culledEntities = List.of();

    public FabricWorld(ServerLevel world, FabricPlatform platform) {
        super(platform);
        this.world = world;
        this.platform = platform;
    }

    @Override
    public @Nullable PlatformChunkAccess getChunkAccess(int x, int z) {
        long chunkKey = ChunkPos.pack(x, z);
        ChunkHolder chunkHolder = this.world.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunkKey);
        ChunkAccess chunk = chunkHolder != null ? chunkHolder.getChunkIfPresent(ChunkStatus.FEATURES) : null;
        return chunk != null ? new FabricChunkAccess(this.platform, chunk) : null;
    }

    @Override
    public String getName() {
        return this.world.serverLevelData.getLevelName();
    }

    @Override
    public String getKey() {
        return this.world.dimension().identifier().toString();
    }

    @Override
    public int getMinY() {
        return this.world.getMinY();
    }

    @Override
    public int getMaxY() {
        return this.world.getMaxY() + 1; // Make inclusive
    }

    @Override
    public int getTrackingDistance() {
        return this.world.getChunkSource().chunkMap.serverViewDistance << 4; // * 16
    }

    @Override
    public int getTrackingDistance(PlatformPlayer player) {
        return this.world.getChunkSource().chunkMap.getPlayerViewDistance(((FabricPlayer) player).getDelegate()) << 4; // * 16
    }

    @Override
    protected List<PlatformPlayer> getPlayers0() {
        List<PlatformPlayer> players = new ArrayList<>();
        for (FabricPlayer platformPlayer : this.platform.getPlayers()) {
            ServerPlayer fabricPlayer = platformPlayer.getDelegate();
            if (fabricPlayer.connection.isAcceptingMessages() && fabricPlayer.level() == this.world
                    && !fabricPlayer.isSpectator() && !platformPlayer.shouldPreventCulling()) {
                players.add(platformPlayer);
            }
        }
        return players;
    }

    @Override
    @Nullable
    public Vec3d rayTraceBlocks(Vec3d start, Vec3d dir, double maxDistance) {
        Vec3 startVec = new Vec3(start.getX(), start.getY(), start.getZ());
        Vec3 endVec = new Vec3(
                start.getX() + dir.getX() * maxDistance,
                start.getY() + dir.getY() * maxDistance,
                start.getZ() + dir.getZ() * maxDistance);

        BlockHitResult result = this.world.clip(new ClipContext(startVec, endVec,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (result.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        Vec3 pos = result.getLocation();
        return new Vec3d(pos.x(), pos.y(), pos.z());
    }



    @Override
    public List<CulledEntity> getCulledEntities() {
        return this.culledEntities;
    }

    // every 2 ticks, on the server thread - entities near players (up to entityCullDistance) that the culling
    // threads may hide. One pass over all loaded entities: cheaper than a huge box query per player.
    private void snapshotCulledEntities() {
        List<ServerPlayer> players = this.world.players();
        CullingConfig.Culling culling = AntiFreecam.getInstance().getCullShip().getConfig().getDelegate().culling;
        boolean whitelist = "whitelist".equalsIgnoreCase(culling.entityMode);
        boolean blacklist = "blacklist".equalsIgnoreCase(culling.entityMode);
        if (players.isEmpty() || !whitelist && !blacklist) {
            this.culledEntities = List.of();
            return;
        }
        double maxDistSq = culling.entityCullDistance * culling.entityCullDistance;
        List<CulledEntity> list = new ArrayList<>();
        for (Entity entity : this.world.getAllEntities()) {
            if (!isCullable(entity, whitelist, culling)) {
                continue;
            }
            boolean near = false;
            for (ServerPlayer player : players) {
                if (player.distanceToSqr(entity) <= maxDistSq) {
                    near = true;
                    break;
                }
            }
            if (near) {
                net.minecraft.world.phys.AABB box = entity.getBoundingBox();
                list.add(new CulledEntity(entity.getUUID(), entity.getId(),
                        new AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)));
            }
        }
        this.culledEntities = List.copyOf(list);
    }

    // what may be hidden - things that give a base away to freecam/ESP
    private static boolean isCullable(Entity entity, boolean whitelist, CullingConfig.Culling culling) {
        if (entity instanceof Player || !entity.isAlive()) {
            return false;
        }
        boolean type;
        if (whitelist) {
            // never hidden: displays/markers/interactions (datapack visuals and helpers), leash knots (a hidden knot breaks the leash rendering),
            // lightning, tridents (thrown and returning weapons)
            type = !(entity instanceof net.minecraft.world.entity.Display
                    || entity instanceof net.minecraft.world.entity.Marker
                    || entity instanceof net.minecraft.world.entity.Interaction
                    || entity instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity
                    || entity instanceof net.minecraft.world.entity.LightningBolt
                    || entity instanceof ThrownTrident);
        } else {
            type = entity instanceof Enemy || entity instanceof AbstractVillager || entity instanceof Animal
                    || entity instanceof AbstractGolem || entity instanceof Allay
                    || entity instanceof ItemFrame || entity instanceof ArmorStand || entity instanceof Painting
                    || entity instanceof AbstractMinecart || entity instanceof AbstractBoat
                    || entity instanceof ThrownEnderpearl
                    || entity instanceof net.minecraft.world.entity.item.ItemEntity
                    || entity instanceof AbstractArrow && !(entity instanceof ThrownTrident);
        }
        if (!type) {
            return false;
        }
        // never-hide-tagged: a scoreboard tag means a datapack entity (boss, scene, custom mechanic)
        if (culling.neverHideTagged) {
            for (String tag : entity.entityTags()) {
                if (!culling.ignoredTags.contains(tag)) {
                    return false;
                }
            }
        }
        if (entity.isCurrentlyGlowing() || entity.isVehicle() || entity.isPassenger()) {
            return false;
        }
        return !(entity instanceof Leashable leashable) || !leashable.isLeashed();
    }

    public void tick() {
        if (this.world.getGameTime() % 2L == 0L) {
            this.snapshotCulledEntities();
        }
        ILevel level = (ILevel) this.world;
        for (BlockPos pos : level.getChangedBlocks()) {
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;

            if (!this.cache.hasChunk(cx, cz)) {
                continue;
            }
            OcclusionChunkCache chunk = this.cache.chunk(cx, cz);
            chunk.recalculateBlock(pos.getX(), pos.getY(), pos.getZ());
        }

        level.getChangedBlocks().clear();
    }

    public ServerLevel getWorld() {
        return this.world;
    }
}
