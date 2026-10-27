package io.github.sanchous327.antifreecam.blocks.util;

import io.netty.channel.ChannelFutureListener;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import io.github.sanchous327.antifreecam.blocks.util.controller.ChunkPacketBlockControllerAntiXray;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Positions of blocks that were replaced in chunk packets, per player, and their reveal.
 * Every tick (server thread, limited number of rays) blocks the player can actually see from his eyes are sent for real.
 * A revealed block stays revealed until its chunk is sent again.
 * Live changes (placed/changed blocks, block entity data, chest lids, cracks, break particles, block sounds) go through
 * {@link #filter} for every player separately, so they don't leak a hidden block either.
 */
public final class BlockReveal {

    private static final int RAY_BUDGET_PER_TICK = 3000;
    // rays for live packets (all players together); when used up, a hidden-type block is just treated as not visible
    private static final int PACKET_RAY_BUDGET_PER_TICK = 600;
    private static final int MAX_RAY_STEPS = 256;

    private static final class PlayerHidden {
        volatile ResourceKey<Level> dimension;
        // chunk -> sorted block positions that this player's client has as a replacement
        final Map<Long, long[]> chunks = new ConcurrentHashMap<>();
        // chunk -> sorted positions of them that are filled air (ultra) - the client has a solid block there
        final Map<Long, long[]> filled = new ConcurrentHashMap<>();
        // reveal scheduling (server thread), per pass (see PASSES): where to continue in a chunk's list, and the tick
        // its whole list was last checked
        final Map<Long, Integer>[] cursor = newMaps();
        final Map<Long, Integer>[] checked = newMaps();

        @SuppressWarnings("unchecked")
        private static Map<Long, Integer>[] newMaps() {
            Map<Long, Integer>[] maps = new Map[PASSES];
            for (int i = 0; i < PASSES; i++) maps[i] = new ConcurrentHashMap<>();
            return maps;
        }
        double lastX, lastY, lastZ;
        float lastYaw, lastPitch;
        // fill-structures: structures this player has seen (structureKey of their box) - their chunks come without the fill
        final java.util.Set<Long> unlocked = ConcurrentHashMap.newKeySet();
        // /antifreecam trace: what happened to each position of this player (position -> tick << 8 | event), null = off
        volatile Map<Long, long[]> trace;
        final Map<Long, long[]> traceChunks = new ConcurrentHashMap<>();
        // chunk -> {chunk packets listed but not yet sent to the client, tick of the last one}: their blocks wait
        final Map<Long, int[]> unsent = new ConcurrentHashMap<>();
        final Map<Long, Integer> traceDrops = new ConcurrentHashMap<>();
        volatile int traceClear = -1;
        // next cell of the view-ray grid (server thread only)
        int viewCursor;
        // blocks revealed this tick, sent together at the end of the player's scan (server thread only)
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet pendingReveal = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        // set by clear() (any thread): the pending reveals belong to the old world - emptied on the server thread
        volatile boolean resendReset;

        void clear() {
            if (trace != null) traceClear = tick();
            resendReset = true;
            unsent.clear();
            chunks.clear();
            filled.clear();
            unlocked.clear();
            forget();
            dropCursors();
        }

        // moved or turned: everything may look different now - check all lists again, but continue from where the
        // scan stopped (restarting at the first positions on every step spent the rays on the same nearby walls over
        // and over, and the far part of a cave was never reached while the player moved)
        void forget() {
            for (int i = 0; i < PASSES; i++) {
                checked[i].clear();
            }
        }

        void dropCursors() {
            for (int i = 0; i < PASSES; i++) {
                cursor[i].clear();
            }
        }

        // the chunk's list changed - check it again
        void recheck(long chunk) {
            for (int i = 0; i < PASSES; i++) {
                checked[i].remove(chunk);
            }
        }
    }

    private static final Map<UUID, PlayerHidden> HIDDEN = new ConcurrentHashMap<>();
    private static int playerOffset = 0;
    private static int packetBudget = 0;
    private static int packetBudgetTick = -1;
    // set while this class sends packets itself (reveal, split section updates) - they must not be filtered again
    private static boolean bypass = false;
    private static volatile MinecraftServer server;

    private BlockReveal() {
    }

    // Called from the obfuscation thread when a chunk packet for this player is ready.
    // Positions hidden by live updates meanwhile are kept (union): a stale one only costs a ray and a real resend later.
    public static void setHidden(UUID viewer, ResourceKey<Level> dimension, long chunk, long[] positions, long[] filledPositions) {
        PlayerHidden state = HIDDEN.computeIfAbsent(viewer, k -> new PlayerHidden());
        if (!dimension.equals(state.dimension)) {
            // a chunk of the old dimension finished after a teleport: dropping the new dimension's list for it would
            // leave the player's fake blocks there unrevealed (stuck in stone only he sees)
            MinecraftServer current = server;
            ServerPlayer player = current == null ? null : current.getPlayerList().getPlayer(viewer);
            if (player != null && !player.level().dimension().equals(dimension)) {
                return;
            }
            state.clear();
            state.dimension = dimension;
        }
        state.recheck(chunk);
        int now = tick();
        state.unsent.compute(chunk, (k, v) -> v == null ? new int[]{1, now} : new int[]{v[0] + 1, now});
        if (positions.length == 0) {
            traceChunk(state, chunk, 30, 0);
            return;
        }
        long[] sorted = positions.clone();
        Arrays.sort(sorted);
        state.chunks.merge(chunk, sorted, BlockReveal::union);
        if (state.trace != null) {
            traceChunk(state, chunk, 30, sorted.length);
            for (long pos : sorted) {
                trace(state, pos, 1);
            }
        }
        if (filledPositions.length > 0) {
            long[] sortedFilled = filledPositions.clone();
            Arrays.sort(sortedFilled);
            state.filled.merge(chunk, sortedFilled, BlockReveal::union);
        }
    }

    private static long[] union(long[] a, long[] b) {
        if (a.length == 0) return b;
        if (b.length == 0) return a;
        long[] result = new long[a.length + b.length];
        int i = 0, j = 0, n = 0;
        while (i < a.length || j < b.length) {
            long next;
            if (j >= b.length || i < a.length && a[i] < b[j]) next = a[i++];
            else if (i >= a.length || b[j] < a[i]) next = b[j++];
            else { next = a[i++]; j++; }
            result[n++] = next;
        }
        return n == result.length ? result : Arrays.copyOf(result, n);
    }

    // Blocks this player's client currently has as a replacement (for /antifreecam status)
    public static int hiddenCount(UUID viewer) {
        PlayerHidden state = HIDDEN.get(viewer);
        if (state == null) {
            return 0;
        }
        int count = 0;
        for (long[] positions : state.chunks.values()) {
            count += positions.length;
        }
        return count;
    }

    // ---- /antifreecam trace (finding phantom blocks) ----
    private static final String[] TRACE_EVENTS = new String[40];
    static {
        TRACE_EVENTS[1] = "replaced in a chunk packet";
        TRACE_EVENTS[2] = "replaced by a live block update";
        TRACE_EVENTS[3] = "marked hidden by block entity data";
        TRACE_EVENTS[10] = "revealed: scan, near";
        TRACE_EVENTS[11] = "revealed: scan, ray";
        TRACE_EVENTS[12] = "revealed: room flood";
        TRACE_EVENTS[13] = "revealed: spread through air";
        TRACE_EVENTS[14] = "revealed: view ray";
        TRACE_EVENTS[15] = "unhidden: live update, near";
        TRACE_EVENTS[16] = "unhidden: live update, visible or not a hidden block";
        TRACE_EVENTS[20] = "second send: sent";
        TRACE_EVENTS[21] = "second send: skipped, hidden again";
        TRACE_EVENTS[22] = "second send: skipped, chunk not the player's";
        TRACE_EVENTS[24] = "block update sent to the client: air";
        TRACE_EVENTS[25] = "block update sent to the client: a block";
        TRACE_EVENTS[30] = "chunk packet listed";
        TRACE_EVENTS[31] = "chunk forgotten";
        TRACE_EVENTS[32] = "chunk packet sent to the client";
    }

    private static int tick() {
        MinecraftServer current = server;
        return current == null ? 0 : current.getTickCount();
    }

    private static void trace(PlayerHidden state, long pos, int event) {
        Map<Long, long[]> map = state.trace;
        if (map != null) {
            push(map, pos, ((long) tick() << 8) | event, 8);
        }
    }

    // chunk events: tick << 8 | event, the count of listed positions above bit 40
    private static void traceChunk(PlayerHidden state, long chunk, int event, int count) {
        if (state.trace != null) {
            push(state.traceChunks, chunk, ((long) count << 40) | ((long) tick() << 8) | event, 12);
        }
    }

    private static void push(Map<Long, long[]> map, long key, long value, int max) {
        map.compute(key, (k, old) -> {
            if (old == null) return new long[]{value};
            long[] next = old.length < max ? Arrays.copyOf(old, old.length + 1) : Arrays.copyOfRange(old, 1, old.length + 1);
            next[next.length - 1] = value;
            return next;
        });
    }

    public static boolean toggleTrace(ServerPlayer player) {
        PlayerHidden state = HIDDEN.computeIfAbsent(player.getUUID(), k -> new PlayerHidden());
        if (state.trace != null) {
            state.trace = null;
            return false;
        }
        state.traceDrops.clear();
        state.traceChunks.clear();
        state.traceClear = -1;
        state.trace = new ConcurrentHashMap<>();
        return true;
    }

    private static String traceOf(PlayerHidden state, long chunkKey, long pos) {
        if (state == null || state.trace == null) {
            return " | history: trace is off (/antifreecam trace)";
        }
        int now = tick();
        StringBuilder history = new StringBuilder();
        long[] events = state.trace.get(pos);
        if (events == null) {
            history.append("nothing recorded");
        } else {
            for (long value : events) {
                history.append("\n  ").append(now - (int) ((value >> 8) & 0xFFFFFFFFL)).append(" ago: ").append(TRACE_EVENTS[(int) (value & 0xFF)]);
            }
        }
        StringBuilder chunk = new StringBuilder();
        long[] chunkEvents = state.traceChunks.get(chunkKey);
        if (chunkEvents == null) {
            chunk.append("nothing recorded");
        } else {
            for (long value : chunkEvents) {
                int event = (int) (value & 0xFF);
                chunk.append("\n  ").append(now - (int) ((value >> 8) & 0xFFFFFFFFL)).append(" ago: ").append(TRACE_EVENTS[event]);
                if (event == 30) chunk.append(" (").append(value >>> 40).append(" positions)");
            }
        }
        return "\n history of the block:" + history
            + "\n history of its chunk:" + chunk
            + "\n lists cleared: " + (state.traceClear < 0 ? "no" : (now - state.traceClear) + " ticks ago");
    }

    // A chunk packet left the server for this player (ConnectionMixin): the client has the chunk from now on
    public static void chunkSent(UUID viewer, long chunk) {
        PlayerHidden state = HIDDEN.get(viewer);
        if (state == null) {
            return;
        }
        state.unsent.computeIfPresent(chunk, (k, v) -> v[0] <= 1 ? null : new int[]{v[0] - 1, v[1]});
        state.recheck(chunk);
        traceChunk(state, chunk, 32, 0);
    }

    // /antifreecam trace: a block update really left the server for this player
    public static void blockSent(UUID viewer, BlockPos pos, BlockState sent) {
        PlayerHidden state = HIDDEN.get(viewer);
        if (state != null && state.trace != null) {
            trace(state, pos.asLong(), sent.isAir() ? 24 : 25);
        }
    }

    // /antifreecam trace: a section update really left the server for this player
    public static void sectionSent(UUID viewer, ClientboundSectionBlocksUpdatePacket packet) {
        PlayerHidden state = HIDDEN.get(viewer);
        if (state != null && state.trace != null) {
            packet.runUpdates((pos, sent) -> trace(state, pos.asLong(), sent.isAir() ? 24 : 25));
        }
    }

    // The client doesn't have this chunk's last packet yet: a block sent now would reach him before the chunk and be
    // thrown away, the chunk would then bring the replacement back. A packet that never leaves (connection gone) stops
    // counting after 30 s
    private static boolean unsent(PlayerHidden state, long chunk) {
        int[] v = state.unsent.get(chunk);
        return v != null && tick() - v[1] < 600;
    }

    // /antifreecam resend: forget the structures this player has opened - they come filled again (testing)
    public static int forgetStructures(ServerPlayer player) {
        PlayerHidden state = HIDDEN.get(player.getUUID());
        if (state == null) {
            return 0;
        }
        int count = state.unlocked.size();
        state.unlocked.clear();
        return count;
    }

    // fill-structures: id of a structure for "opened for this player" - the corner of its box
    public static long structureKey(net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        return BlockPos.asLong(box.minX(), box.minY(), box.minZ());
    }

    // fill-structures: this structure was seen by the player - send it to him without the fill (packet thread reads it)
    public static boolean isUnlocked(UUID viewer, long structureKey) {
        PlayerHidden state = viewer == null ? null : HIDDEN.get(viewer);
        return state != null && state.unlocked.contains(structureKey);
    }

    // A filled cell of a listed structure was really seen: the whole structure opens for this player at once - the fill in
    // reverse. Its chunks the player has are sent again without its fill (a chunk packet each, not a block update per
    // cell - a structure has hundreds of thousands of them). Only a cell in one of its pieces (rooms, corridors) opens
    // it; the cave around them opens around the seen cell. Returns false if the cell isn't in a filled structure piece.
    private static boolean unlockStructure(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller,
                                           PlayerHidden state, int x, int y, int z) {
        net.minecraft.world.level.levelgen.structure.BoundingBox box = controller.rtStructureBoxAt(level, new BlockPos(x, y, z), player.getUUID());
        if (box == null) {
            return false;
        }
        if (!state.unlocked.add(structureKey(box))) {
            return true; // already opened - the new chunks are on their way
        }
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (!player.getChunkTrackingView().contains(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                // like resendAll: forget the chunk and send it again (only marking it pending didn't always resend an
                // already sent chunk - the client kept the fill while our lists for it were gone: phantom blocks)
                player.connection.chunkSender.dropChunk(player, new ChunkPos(cx, cz));
                player.connection.chunkSender.markChunkPendingToSend(chunk);
            }
        }
        return true;
    }

    // /antifreecam block <pos>: what the mod knows about this position for this player (finding phantom blocks)
    public static String describe(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        if (!(Util.getBlockController(level) instanceof ChunkPacketBlockControllerAntiXray controller) || !controller.rtEnabled()) {
            return "block hiding is off in this world";
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return "chunk not loaded";
        }
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        long packed = pos.asLong();
        PlayerHidden state = HIDDEN.get(player.getUUID());
        boolean hidden = state != null && isHiddenPos(state, chunkKey, packed);
        boolean filled = state != null && contains(state.filled, chunkKey, packed);
        long[] list = state == null ? null : state.chunks.get(chunkKey);
        int[] used = new int[1];
        boolean visible = isVisible(level, controller, player.getX(), player.getEyeY(), player.getZ(), pos.getX(), pos.getY(), pos.getZ(), used);
        return "real: " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock())
            + " | replaced for you: " + (hidden ? (filled ? "yes (filled)" : "yes") : "NO")
            + " | a ray sees it now: " + (visible ? "yes" : "no")
            + " | near: " + (isNear(player, controller, pos) ? "yes" : "no")
            + " | chunk waiting to be sent: " + (player.connection.chunkSender.isPending(chunkKey) ? "yes" : "no")
            + " | replaced in this chunk: " + (list == null ? 0 : list.length)
            + " | world of the lists: " + (state == null || state.dimension == null ? "none" : state.dimension.toString())
            + traceOf(state, chunkKey, packed);
    }

    public static void dropChunk(UUID viewer, long chunk) {
        PlayerHidden state = HIDDEN.get(viewer);
        if (state != null) {
            if (state.trace != null) {
                state.traceDrops.put(chunk, tick());
                traceChunk(state, chunk, 31, 0);
            }
            state.chunks.remove(chunk);
            state.filled.remove(chunk);
            for (int i = 0; i < PASSES; i++) {
                state.cursor[i].remove(chunk);
            }
            state.recheck(chunk);
        }
    }

    // revealed this tick (pendingReveal) counts as no longer hidden - it leaves the lists at the end of the tick
    private static boolean isHiddenPos(PlayerHidden state, long chunk, long pos) {
        return contains(state.chunks, chunk, pos) && !state.pendingReveal.contains(pos);
    }

    private static boolean contains(Map<Long, long[]> map, long chunk, long pos) {
        long[] positions = map.get(chunk);
        return positions != null && Arrays.binarySearch(positions, pos) >= 0;
    }

    private static void addHiddenPos(PlayerHidden state, long chunk, long pos) {
        state.chunks.merge(chunk, new long[]{pos}, BlockReveal::union);
        state.recheck(chunk);
    }

    private static void removeHiddenPos(PlayerHidden state, long chunk, long pos) {
        removeFrom(state.chunks, chunk, pos);
        removeFrom(state.filled, chunk, pos);
    }

    private static void removeFrom(Map<Long, long[]> map, long chunk, long pos) {
        map.computeIfPresent(chunk, (k, positions) -> {
            int index = Arrays.binarySearch(positions, pos);
            if (index < 0) return positions;
            if (positions.length == 1) return null;
            long[] result = new long[positions.length - 1];
            System.arraycopy(positions, 0, result, 0, index);
            System.arraycopy(positions, index + 1, result, index, positions.length - index - 1);
            return result;
        });
    }

    private static final java.util.Set<UUID> SPECTATORS = ConcurrentHashMap.newKeySet();

    /**
     * Sends every chunk the player has loaded again (with the current hiding rules for him). Returns the count.
     */
    public static int resendAll(ServerPlayer player) {
        ServerLevel level = player.level();
        int[] count = {0};
        player.getChunkTrackingView().forEach(pos -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk != null) {
                player.connection.chunkSender.dropChunk(player, pos);
                player.connection.chunkSender.markChunkPendingToSend(chunk);
                count[0]++;
            }
        });
        return count[0];
    }

    // Spectators get real blocks (they fly through walls anyway): entering or leaving spectator mode re-sends the
    // loaded chunks with the rules for the new mode
    private static void checkSpectators(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean spectator = player.isSpectator();
            if (spectator == SPECTATORS.contains(player.getUUID())) {
                continue;
            }
            if (spectator) {
                SPECTATORS.add(player.getUUID());
            } else {
                SPECTATORS.remove(player.getUUID());
            }
            if (Util.getBlockController(player.level()) instanceof ChunkPacketBlockControllerAntiXray controller && controller.rtEnabled()) {
                resendAll(player);
            }
        }
        if (server.getTickCount() % 100 == 0) {
            SPECTATORS.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
    }

    public static void tick(MinecraftServer server) {
        BlockReveal.server = server;
        checkSpectators(server);
        if (HIDDEN.isEmpty()) {
            return;
        }
        if (server.getTickCount() % 100 == 0) {
            HIDDEN.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        int budget = RAY_BUDGET_PER_TICK;
        viewRaysPerPlayer = Math.max(VIEW_RAYS_MIN, Math.min(VIEW_RAYS, VIEW_RAYS_TOTAL / players.size()));
        // start from a different player each tick, so one player with a huge storage room can't starve the others
        playerOffset = (playerOffset + 1) % players.size();
        for (int n = 0; n < players.size() && budget > 0; n++) {
            ServerPlayer player = players.get((playerOffset + n) % players.size());
            PlayerHidden state = HIDDEN.get(player.getUUID());
            if (state == null) {
                continue;
            }
            if (state.chunks.isEmpty()) {
                continue;
            }
            ServerLevel level = player.level();
            if (!level.dimension().equals(state.dimension)) {
                continue;
            }
            if (!(Util.getBlockController(level) instanceof ChunkPacketBlockControllerAntiXray controller) || !controller.rtEnabled()) {
                continue;
            }
            budget = revealFor(player, level, controller, state, budget);
            flushReveals(player, level, state);
        }
    }

    // A whole checked list is checked again only after the player moved or turned, or after RECHECK_TICKS
    // (blocks around may have changed), so rays go to what is new instead of the same walls every tick.
    private static final int RECHECK_TICKS = 40;
    // in front of the eyes: angle to the look direction below ~75 degrees
    private static final double CONE_COS = 0.25;
    // distance rings of the passes (blocks); the last ring is up to reveal-distance
    private static final double RING_1 = 16;
    private static final double RING_2 = 32;
    // passes: around the crosshair (any distance), in front of the eyes ring 1, 2, 3, then behind / to the side ring 1, 2, 3
    private static final int PASSES = 7;
    // share of the tick's rays per pass (crosshair; in front: near, middle, far; then behind / to the side)
    private static final double[] PASS_SHARE = {0.35, 0.20, 0.12, 0.13, 0.08, 0.06, 0.06};
    // around the crosshair (~32 degrees): checked first at any distance - what the player looks at opens first, not
    // after thousands of hidden blocks to the side (a big build like an ancient city)
    private static final double AIM_COS = 0.85;

    // Seven passes: around the crosshair first, then in front of the eyes - closest first - is what the player is
    // looking at right now, the rest gets the rays left. Closest chunks first; a chunk the budget ran out in
    // continues from there next tick.
    private static int revealFor(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller, PlayerHidden state, int budget) {
        double ex = player.getX();
        double ey = player.getEyeY();
        double ez = player.getZ();
        int maxDistance = controller.rtRevealDistance();
        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        int chunkRange = (maxDistance >> 4) + 1;
        int tick = level.getServer().getTickCount();

        double mx = ex - state.lastX;
        double my = ey - state.lastY;
        double mz = ez - state.lastZ;
        if (mx * mx + my * my + mz * mz > 0.25
            || Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot() - state.lastYaw)) > 5
            || Math.abs(player.getXRot() - state.lastPitch) > 5) {
            state.forget();
            state.lastX = ex;
            state.lastY = ey;
            state.lastZ = ez;
            state.lastYaw = player.getYRot();
            state.lastPitch = player.getXRot();
        }
        net.minecraft.world.phys.Vec3 look = player.getLookAngle();
        // what the player really sees opens first, whatever the length of the lists
        viewRays(player, level, controller, state, ex, ey, ez, look, maxDistance);

        LongArrayList keys = new LongArrayList();
        for (Long key : state.chunks.keySet()) {
            if (Math.abs((int) (long) key - pcx) <= chunkRange && Math.abs((int) (key >>> 32) - pcz) <= chunkRange) {
                keys.add((long) key);
            }
        }
        keys.sort((a, b) -> Integer.compare(chunkDistance(a, pcx, pcz), chunkDistance(b, pcx, pcz)));

        // every pass gets its share of the rays each tick (what it doesn't use goes on to the next one), so the far
        // part of a big cave in front of the eyes is checked too, not only the nearby walls
        int total = budget;
        int carry = 0;
        for (int pass = 0; pass < PASSES; pass++) {
            int passBudget = pass == PASSES - 1 ? budget : Math.min(budget, (int) (total * PASS_SHARE[pass]) + carry);
            int before = passBudget;
            // a chunk wholly farther than this pass's ring isn't walked through at all (filled structures make the lists
            // of a chunk long)
            double ringMax = pass == 1 || pass == 4 ? RING_1 : pass == 2 || pass == 5 ? RING_2 : maxDistance;
            for (int k = 0; k < keys.size() && passBudget > 0; k++) {
                long chunkKey = keys.getLong(k);
                if (horizontalMinDistance(chunkKey, ex, ez) > ringMax) {
                    continue;
                }
                Integer checkedAt = state.checked[pass].get(chunkKey);
                if (checkedAt != null && tick - checkedAt < RECHECK_TICKS) {
                    continue;
                }
                passBudget = scanChunk(player, level, controller, state, chunkKey, pass, look, ex, ey, ez, tick, passBudget);
            }
            budget -= before - passBudget;
            carry = passBudget;
        }
        return budget;
    }

    private static int scanChunk(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller, PlayerHidden state,
                                 long chunkKey, int pass, net.minecraft.world.phys.Vec3 look, double ex, double ey, double ez,
                                 int tick, int budget) {
        long[] positions = state.chunks.get(chunkKey);
        if (positions == null || positions.length == 0 || unsent(state, chunkKey)) {
            return budget;
        }
        int maxDistance = controller.rtRevealDistance();
        double maxDistanceSq = (double) maxDistance * maxDistance;
        double nearSq = controller.rtNearRadiusSq();
        int i = state.cursor[pass].getOrDefault(chunkKey, 0);
        if (i >= positions.length) i = 0;
        boolean[] revealed = null;
        int done = 0;
        for (; done < positions.length && budget > 0; done++, i = (i + 1) % positions.length) {
            if (revealed != null && revealed[i]) {
                continue; // already opened together with its room
            }
            long packed = positions[i];
            if (state.pendingReveal.contains(packed)) {
                continue; // revealed this tick already
            }
            int x = BlockPos.getX(packed);
            int y = BlockPos.getY(packed);
            int z = BlockPos.getZ(packed);
            double dx = x + 0.5 - ex;
            double dy = y + 0.5 - ey;
            double dz = z + 0.5 - ez;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > maxDistanceSq) {
                continue;
            }
            boolean near = distanceSq <= nearSq;
            double dot = dx * look.x + dy * look.y + dz * look.z;
            double distance = Math.sqrt(distanceSq);
            boolean aim = !near && dot > AIM_COS * distance;
            boolean inFront = near || dot > CONE_COS * distance;
            int ring = distanceSq <= RING_1 * RING_1 ? 0 : distanceSq <= RING_2 * RING_2 ? 1 : 2;
            int blockPass = aim ? 0 : (inFront ? 1 : 4) + ring;
            if (blockPass != pass) {
                continue; // another pass takes it
            }
            boolean visible;
            if (near) {
                // the player is right there - never leave a fake block he could walk into or try to break
                visible = true;
            } else {
                int[] used = new int[1];
                visible = isVisible(level, controller, ex, ey, ez, x, y, z, used);
                budget -= used[0];
            }
            if (visible) {
                if (revealed == null) revealed = new boolean[positions.length];
                revealed[i] = true;
                trace(state, packed, near ? 10 : 11);
                reveal(player, level, x, y, z);
                if (contains(state.filled, chunkKey, packed)) {
                    // a filled structure opens whole (its chunks come again); a filled room - around the seen cell
                    if (!controller.rtFillsStructures() || !unlockStructure(player, level, controller, state, x, y, z)) {
                        budget -= floodReveal(player, level, controller, state, chunkKey, positions, revealed, packed);
                    }
                } else if (!near) {
                    budget -= spreadReveal(player, level, controller, state, chunkKey, positions, revealed, x, y, z, ex, ey, ez) / 4;
                }
            }
        }
        if (done >= positions.length) {
            state.cursor[pass].remove(chunkKey);
            state.checked[pass].put(chunkKey, tick);
        } else {
            state.cursor[pass].put(chunkKey, i);
        }
        if (revealed != null) {
            LongArrayList left = new LongArrayList(positions.length);
            for (int j = 0; j < positions.length; j++) {
                if (revealed[j]) {
                    removeFrom(state.filled, chunkKey, positions[j]);
                } else {
                    left.add(positions[j]);
                }
            }
            // replace only if no new chunk packet / live update replaced the list meanwhile (order stays sorted)
            if (left.isEmpty()) {
                state.chunks.remove(chunkKey, positions);
            } else {
                state.chunks.replace(chunkKey, positions, left.toLongArray());
            }
        }
        return budget;
    }

    // View rays: every tick rays from the eyes in random directions inside the field of view walk the REAL world; a
    // hidden block one of them reaches (the first full block it hits, and thin or filled cells on the way) is what the
    // player really sees - it opens at once. In a huge build (an ancient city has a million hidden blocks) the per-block
    // scan reaches the block in front of the eyes only after seconds; with these the screen fills in a few ticks.
    private static final int VIEW_RAYS = 256;
    // all players together: with many online each gets fewer view rays (never below VIEW_RAYS_MIN)
    private static final int VIEW_RAYS_TOTAL = 2048;
    private static final int VIEW_RAYS_MIN = 48;
    private static int viewRaysPerPlayer = VIEW_RAYS;
    // half of the sampled field of view: ~51 degrees to the sides, ~39 degrees up and down (tangents)
    private static final double VIEW_TAN_H = 1.25;
    private static final double VIEW_TAN_V = 0.8;
    // the grid of view-ray cells (a power of two in all) and an odd stride - every cell once per round, scattered
    private static final int VIEW_GRID_W = 64;
    private static final int VIEW_GRID_H = 32;
    private static final int VIEW_GRID_STRIDE = 1031;

    private static void viewRays(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller,
                                 PlayerHidden state, double ex, double ey, double ez, net.minecraft.world.phys.Vec3 look,
                                 int maxDistance) {
        double lx = look.x, ly = look.y, lz = look.z;
        // right = look x (0, 1, 0); straight up or down - any horizontal direction
        double rx = -lz, rz = lx;
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1.0E-4) {
            rx = 1;
            rz = 0;
            rl = 1;
        }
        rx /= rl;
        rz /= rl;
        // up = right x look
        double ux = -rz * ly, uy = rz * lx - rx * lz, uz = rx * ly;
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // the field of view is a grid of VIEW_GRID_W x VIEW_GRID_H cells walked in a fixed scattered order: each tick the
        // next cells get a ray (a random point inside the cell), so the whole view is covered in a few ticks instead of
        // waiting for random rays to hit the last uncovered spots
        for (int n = 0; n < viewRaysPerPlayer; n++) {
            int cell = (int) (((long) state.viewCursor++ * VIEW_GRID_STRIDE) & (VIEW_GRID_W * VIEW_GRID_H - 1));
            double a = (((cell % VIEW_GRID_W) + random.nextDouble()) / VIEW_GRID_W * 2 - 1) * VIEW_TAN_H;
            double b = (((cell / VIEW_GRID_W) + random.nextDouble()) / VIEW_GRID_H * 2 - 1) * VIEW_TAN_V;
            double dx = lx + rx * a + ux * b;
            double dy = ly + uy * b;
            double dz = lz + rz * a + uz * b;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            viewRay(player, level, controller, state, ex, ey, ez, dx / length, dy / length, dz / length, maxDistance, pos);
        }
    }

    // One view ray (Amanatides & Woo, unit direction): reveals the hidden cells it passes and stops at the first real full block
    private static void viewRay(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller,
                                PlayerHidden state, double sx, double sy, double sz, double dx, double dy, double dz,
                                int maxDistance, BlockPos.MutableBlockPos pos) {
        int x = (int) Math.floor(sx);
        int y = (int) Math.floor(sy);
        int z = (int) Math.floor(sz);
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.MAX_VALUE;
        double tMaxX = stepX > 0 ? (x + 1 - sx) / dx : stepX < 0 ? (sx - x) / -dx : Double.MAX_VALUE;
        double tMaxY = stepY > 0 ? (y + 1 - sy) / dy : stepY < 0 ? (sy - y) / -dy : Double.MAX_VALUE;
        double tMaxZ = stepZ > 0 ? (z + 1 - sz) / dz : stepZ < 0 ? (sz - z) / -dz : Double.MAX_VALUE;
        LevelChunk chunk = null;
        for (int step = 0; step < MAX_RAY_STEPS * 2; step++) {
            if (step > 0) {
                if (chunk == null || chunk.getPos().x() != x >> 4 || chunk.getPos().z() != z >> 4) {
                    chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) {
                        return;
                    }
                }
                long chunkKey = ChunkPos.pack(x >> 4, z >> 4);
                long packed = BlockPos.asLong(x, y, z);
                if (isHiddenPos(state, chunkKey, packed) && !unsent(state, chunkKey)) {
                    boolean filled = contains(state.filled, chunkKey, packed);
                    trace(state, packed, 14);
                    reveal(player, level, x, y, z);
                    if (filled) {
                        if (!(controller.rtFillsStructures() && unlockStructure(player, level, controller, state, x, y, z))) {
                            floodReveal(player, level, controller, state, chunkKey, null, null, packed);
                        }
                    } else {
                        // the hidden blocks lining the same visible air next to it open too - a wall opens in pieces
                        spreadReveal(player, level, controller, state, chunkKey, null, null, x, y, z, sx, sy, sz);
                    }
                }
                if (controller.rtOccludes(chunk.getBlockState(pos.set(x, y, z)))) {
                    return; // the first real full block - nothing behind it is seen
                }
            }
            double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (next > maxDistance) {
                return;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
    }

    private static final int FLOOD_MAX = 8192;
    // a filled room opens within this many blocks of the seen cell: a huge filled structure (fill-structures) must
    // not open whole - with all its corridors - from one look through a doorway
    private static final int FLOOD_RADIUS = 16;

    // One filled air cell of a room is visible - the player is looking into that room: reveal all its filled air at
    // once instead of cell by cell. Only filled cells that aren't full blocks spread it; hidden blocks inside (furniture, redstone) still wait
    // until they are seen themselves. Returns the ray budget to charge.
    private static int floodReveal(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller,
                                   PlayerHidden state, long chunkKey,
                                   long[] positions, boolean[] revealed, long start) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet visited = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        queue.add(start);
        visited.add(start);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int count = 0;
        for (int head = 0; head < queue.size() && count < FLOOD_MAX; head++) {
            long current = queue.getLong(head);
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                pos.set(current).move(direction);
                long next = pos.asLong();
                if (!visited.add(next)) continue;
                long nextChunk = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
                if (!contains(state.filled, nextChunk, next) || !isHiddenPos(state, nextChunk, next) || unsent(state, nextChunk)) continue;
                if (Math.abs(pos.getX() - BlockPos.getX(start)) > FLOOD_RADIUS || Math.abs(pos.getY() - BlockPos.getY(start)) > FLOOD_RADIUS
                    || Math.abs(pos.getZ() - BlockPos.getZ(start)) > FLOOD_RADIUS) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                // through every filled cell that isn't a full block (air, and in filled structures also water, sculk veins...)
                if (chunk == null || controller.rtOccludes(chunk.getBlockState(pos))) continue;
                trace(state, next, 12);
                reveal(player, level, pos.getX(), pos.getY(), pos.getZ());
                count++;
                if (positions != null && nextChunk == chunkKey) {
                    int index = Arrays.binarySearch(positions, next);
                    if (index >= 0) {
                        revealed[index] = true;
                    }
                }
                queue.add(next);
            }
        }
        return count / 4;
    }

    // A block was seen by a ray: the hidden blocks lining the same open cells next to it (on its sides turned to the
    // eyes) open too - they face the same visible air, so a cave wall opens whole instead of in spots (a block seen
    // only at a grazing angle misses its rays). Only through real open cells the client also sees as open: a block
    // behind a wall touches no such cell. Filled air (fake rock of a room) is left to floodReveal. Returns the count.
    private static int spreadReveal(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller,
                                    PlayerHidden state, long chunkKey, long[] positions, boolean[] revealed,
                                    int bx, int by, int bz, double ex, double ey, double ez) {
        int count = 0;
        BlockPos.MutableBlockPos air = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            double toEye = (ex - (bx + 0.5)) * side.getStepX() + (ey - (by + 0.5)) * side.getStepY() + (ez - (bz + 0.5)) * side.getStepZ();
            if (toEye <= 0) {
                continue; // a side turned away from the eyes
            }
            air.set(bx + side.getStepX(), by + side.getStepY(), bz + side.getStepZ());
            long airChunk = ChunkPos.pack(air.getX() >> 4, air.getZ() >> 4);
            if (!isOpen(level, controller, air.getX(), air.getY(), air.getZ()) || isHiddenPos(state, airChunk, air.asLong())) {
                continue;
            }
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                next.setWithOffset(air, direction);
                if (next.getX() == bx && next.getY() == by && next.getZ() == bz) {
                    continue;
                }
                long nextChunk = ChunkPos.pack(next.getX() >> 4, next.getZ() >> 4);
                long packed = next.asLong();
                if (!isHiddenPos(state, nextChunk, packed) || contains(state.filled, nextChunk, packed) || unsent(state, nextChunk)) {
                    continue;
                }
                if (positions != null && nextChunk == chunkKey) {
                    int index = Arrays.binarySearch(positions, packed);
                    if (index >= 0) {
                        if (revealed[index]) {
                            continue;
                        }
                        revealed[index] = true;
                    }
                }
                trace(state, packed, 13);
                reveal(player, level, next.getX(), next.getY(), next.getZ());
                count++;
            }
        }
        return count;
    }

    // horizontal distance from the eyes to the nearest column of the chunk
    private static double horizontalMinDistance(long chunkKey, double ex, double ez) {
        int minX = ((int) chunkKey) << 4;
        int minZ = ((int) (chunkKey >>> 32)) << 4;
        double dx = ex < minX ? minX - ex : ex > minX + 16 ? ex - (minX + 16) : 0;
        double dz = ez < minZ ? minZ - ez : ez > minZ + 16 ? ez - (minZ + 16) : 0;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int chunkDistance(long chunkKey, int pcx, int pcz) {
        return Math.max(Math.abs((int) chunkKey - pcx), Math.abs((int) (chunkKey >>> 32) - pcz));
    }

    // A revealed block waits for the end of the player's scan this tick; then all of them go out together, one packet per
    // chunk section (flushReveals) - a room flood opens thousands of blocks at once, a packet each was too much
    private static void reveal(ServerPlayer player, ServerLevel level, int x, int y, int z) {
        PlayerHidden state = HIDDEN.get(player.getUUID());
        if (state == null) {
            sendReal(player, level, x, y, z);
            return;
        }
        state.pendingReveal.add(BlockPos.asLong(x, y, z));
    }

    private static long[] without(long[] positions, it.unimi.dsi.fastutil.longs.LongOpenHashSet gone) {
        int kept = 0;
        long[] result = new long[positions.length];
        for (long pos : positions) {
            if (!gone.contains(pos)) result[kept++] = pos;
        }
        return kept == 0 ? null : kept == positions.length ? positions : Arrays.copyOf(result, kept);
    }

    // The blocks revealed to this player this tick: one section update per chunk section (a single block - a block
    // update), then the data of the block entities among them
    private static void flushReveals(ServerPlayer player, ServerLevel level, PlayerHidden state) {
        if (state.resendReset) {
            state.resendReset = false;
            state.pendingReveal.clear();
        }
        if (state.pendingReveal.isEmpty()) {
            return;
        }
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.ShortArraySet> sections = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        java.util.List<BlockPos> withEntity = new java.util.ArrayList<>();
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.longs.LongOpenHashSet> byChunk = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        it.unimi.dsi.fastutil.longs.LongIterator iterator = state.pendingReveal.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            BlockPos pos = BlockPos.of(packed);
            sections.computeIfAbsent(net.minecraft.core.SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4),
                k -> new it.unimi.dsi.fastutil.shorts.ShortArraySet()).add(net.minecraft.core.SectionPos.sectionRelativePos(pos));
            byChunk.computeIfAbsent(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(packed);
        }
        state.pendingReveal.clear();
        // the revealed blocks leave the lists: one pass per chunk, not an array copy per block
        for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<it.unimi.dsi.fastutil.longs.LongOpenHashSet> entry : byChunk.long2ObjectEntrySet()) {
            it.unimi.dsi.fastutil.longs.LongOpenHashSet gone = entry.getValue();
            state.chunks.computeIfPresent(entry.getLongKey(), (k, positions) -> without(positions, gone));
            state.filled.computeIfPresent(entry.getLongKey(), (k, positions) -> without(positions, gone));
        }
        bypass = true;
        try {
            for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<it.unimi.dsi.fastutil.shorts.ShortArraySet> entry : sections.long2ObjectEntrySet()) {
                net.minecraft.core.SectionPos sectionPos = net.minecraft.core.SectionPos.of(entry.getLongKey());
                LevelChunk chunk = level.getChunkSource().getChunkNow(sectionPos.x(), sectionPos.z());
                if (chunk == null) {
                    continue;
                }
                int index = chunk.getSectionIndexFromSectionY(sectionPos.y());
                if (index < 0 || index >= chunk.getSections().length) {
                    continue;
                }
                it.unimi.dsi.fastutil.shorts.ShortArraySet cells = entry.getValue();
                if (cells.size() == 1) {
                    BlockPos pos = sectionPos.relativeToBlockPos(cells.iterator().nextShort());
                    player.connection.send(new ClientboundBlockUpdatePacket(pos, chunk.getBlockState(pos)));
                } else {
                    player.connection.send(new ClientboundSectionBlocksUpdatePacket(sectionPos, cells, chunk.getSections()[index]));
                }
                it.unimi.dsi.fastutil.shorts.ShortIterator cellIterator = cells.iterator();
                while (cellIterator.hasNext()) {
                    BlockPos pos = sectionPos.relativeToBlockPos(cellIterator.nextShort());
                    if (chunk.getBlockEntity(pos) != null) {
                        withEntity.add(pos);
                    }
                }
            }
            for (BlockPos pos : withEntity) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                Packet<?> update = blockEntity == null ? null : blockEntity.getUpdatePacket();
                if (update != null) {
                    player.connection.send(update);
                }
            }
        } finally {
            bypass = false;
        }
    }

    private static void sendReal(ServerPlayer player, ServerLevel level, int x, int y, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        bypass = true;
        try {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, chunk.getBlockState(pos)));
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (blockEntity != null) {
                Packet<?> update = blockEntity.getUpdatePacket();
                if (update != null) {
                    player.connection.send(update);
                }
            }
        } finally {
            bypass = false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Live packets
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Called for every packet sent to a player. Returns true if the packet was handled here (dropped or replaced) and
     * must not be sent as is.
     */
    public static boolean filter(ServerGamePacketListenerImpl listener, Packet<?> packet, ChannelFutureListener sendListener) {
        if (bypass) {
            return false;
        }
        if (!(packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket
            || packet instanceof ClientboundBlockEntityDataPacket || packet instanceof ClientboundBlockEventPacket
            || packet instanceof ClientboundBlockDestructionPacket || packet instanceof ClientboundLevelEventPacket
            || packet instanceof ClientboundSoundPacket)) {
            return false;
        }
        ServerPlayer player = listener.player;
        if (player == null || player.isSpectator()) {
            return false;
        }
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        // world access only on the server thread (block packets practically always come from there)
        if (server == null || !server.isSameThread()) {
            return false;
        }
        if (!(Util.getBlockController(level) instanceof ChunkPacketBlockControllerAntiXray controller) || !controller.rtEnabled()) {
            return false;
        }
        if (packetBudgetTick != server.getTickCount()) {
            packetBudgetTick = server.getTickCount();
            packetBudget = PACKET_RAY_BUDGET_PER_TICK;
        }
        PlayerHidden state = HIDDEN.computeIfAbsent(player.getUUID(), k -> new PlayerHidden());
        if (!level.dimension().equals(state.dimension)) {
            // no chunk of this dimension was sent yet - nothing is known as hidden, positions get added below
            state.clear();
            state.dimension = level.dimension();
        }

        try {
            if (packet instanceof ClientboundBlockUpdatePacket update) {
                BlockPos pos = update.getPos();
                BlockState replacement = liveUpdate(player, level, controller, state, pos, update.getBlockState());
                if (replacement == null) {
                    return false;
                }
                send(listener, new ClientboundBlockUpdatePacket(pos, replacement), sendListener);
                return true;
            }
            if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdate) {
                List<BlockPos> positions = new ArrayList<>();
                List<BlockState> states = new ArrayList<>();
                boolean[] changed = {false};
                sectionUpdate.runUpdates((pos, blockState) -> {
                    BlockState replacement = liveUpdate(player, level, controller, state, pos, blockState);
                    positions.add(pos.immutable());
                    states.add(replacement != null ? replacement : blockState);
                    changed[0] |= replacement != null;
                });
                if (!changed[0]) {
                    return false;
                }
                // split into single updates - a section packet can't be built from custom states
                for (int i = 0; i < positions.size(); i++) {
                    send(listener, new ClientboundBlockUpdatePacket(positions.get(i), states.get(i)), i == positions.size() - 1 ? sendListener : null);
                }
                return true;
            }
            if (packet instanceof ClientboundBlockEntityDataPacket data) {
                if (!hiddenFor(player, level, controller, state, data.getPos(), null)) {
                    return false;
                }
                // the client keeps old data (sign text) - mark it, so the reveal sends the new data when it gets visible
                BlockPos pos = data.getPos();
                addHiddenPos(state, ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), pos.asLong());
                trace(state, pos.asLong(), 3);
                return true;
            }
            if (packet instanceof ClientboundBlockEventPacket event) {
                return hiddenFor(player, level, controller, state, event.getPos(), null);
            }
            if (packet instanceof ClientboundBlockDestructionPacket destruction) {
                return hiddenFor(player, level, controller, state, destruction.getPos(), null);
            }
            if (packet instanceof ClientboundLevelEventPacket event) {
                if (event.isGlobalEvent()) {
                    return false;
                }
                // 2001 = block broken: the block is already gone, its state is in the data
                BlockState broken = event.getType() == 2001 ? Block.stateById(event.getData()) : null;
                return hiddenFor(player, level, controller, state, event.getPos(), broken);
            }
            if (packet instanceof ClientboundSoundPacket sound) {
                if (sound.getSource() != SoundSource.BLOCKS) {
                    return false;
                }
                return hiddenFor(player, level, controller, state,
                    BlockPos.containing(sound.getX(), sound.getY(), sound.getZ()), null);
            }
        } catch (RuntimeException e) {
            // never break packet sending - just let the packet through
            return false;
        }
        return false;
    }

    private static void send(ServerGamePacketListenerImpl listener, Packet<?> packet, ChannelFutureListener sendListener) {
        bypass = true;
        try {
            listener.send(packet, sendListener);
        } finally {
            bypass = false;
        }
    }

    // Block update: returns the replacement state if the new block must stay hidden from this player, else null
    // (and the position is no longer hidden on his client)
    private static BlockState liveUpdate(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller,
                                         PlayerHidden state, BlockPos pos, BlockState blockState) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        long packed = pos.asLong();
        if (chunk == null || isNear(player, controller, pos)) {
            removeHiddenPos(state, chunkKey, packed);
            trace(state, packed, 15);
            return null;
        }
        // a filled cell (pocket, building or structure) stays solid on the client, whatever happens there (block broken ->
        // air, a right click answered with the real block) - until a ray really sees it
        boolean filledCell = controller.rtFillsAnything() && isHiddenPos(state, chunkKey, packed) && contains(state.filled, chunkKey, packed);
        if (!filledCell && !controller.rtShouldHideAt(chunk, blockState, pos.getX(), pos.getY(), pos.getZ())
            || isVisibleNow(player, level, controller, pos)) {
            removeHiddenPos(state, chunkKey, packed);
            trace(state, packed, 16);
            return null;
        }
        addHiddenPos(state, chunkKey, packed);
        trace(state, packed, 2);
        return filledCell || controller.rtSolidAsRock(blockState) ? controller.rtFillAt(chunk, pos) : controller.rtReplacementAt(chunk, pos);
    }

    // Must a packet about this position be kept from the player (its block is hidden on his client or would be)
    private static boolean hiddenFor(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller,
                                     PlayerHidden state, BlockPos pos, BlockState knownState) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || isNear(player, controller, pos)) {
            return false;
        }
        BlockState blockState = knownState != null ? knownState : chunk.getBlockState(pos);
        if (!controller.rtShouldHideAt(chunk, blockState, pos.getX(), pos.getY(), pos.getZ())) {
            return false;
        }
        if (isHiddenPos(state, ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), pos.asLong())) {
            return true;
        }
        return !isVisibleNow(player, level, controller, pos);
    }

    private static boolean isNear(ServerPlayer player, ChunkPacketBlockControllerAntiXray controller, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz <= controller.rtNearRadiusSq();
    }

    // Out of reveal distance or out of rays -> not visible (it gets revealed later by the tick if it is)
    private static boolean isVisibleNow(ServerPlayer player, ServerLevel level, ChunkPacketBlockControllerAntiXray controller, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double max = controller.rtRevealDistance();
        if (dx * dx + dy * dy + dz * dz > max * max || packetBudget <= 0) {
            return false;
        }
        int[] used = new int[1];
        boolean visible = isVisible(level, controller, player.getX(), player.getEyeY(), player.getZ(), pos.getX(), pos.getY(), pos.getZ(), used);
        packetBudget -= used[0];
        return visible;
    }

    // ---------------------------------------------------------------------------------------------------------------

    // Visible if a straight line from the eyes reaches the block (its center or a side turned to the eyes) without
    // passing through a full opaque block. Glass, leaves, fences, slabs... don't block the view.
    private static boolean isVisible(ServerLevel level, ChunkPacketBlockControllerAntiXray controller,
                                     double ex, double ey, double ez, int bx, int by, int bz, int[] used) {
        // Walled in on all 6 sides by opaque blocks - can't be seen, no rays needed (most hidden blocks of a build)
        if (!hasOpenSide(level, controller, bx, by, bz)) {
            return false;
        }
        // the center, then the middles of the (up to 3) faces turned to the eyes and the corner between them
        double cx = bx + 0.5;
        double cy = by + 0.5;
        double cz = bz + 0.5;
        double fx = ex < cx ? -0.45 : 0.45;
        double fy = ey < cy ? -0.45 : 0.45;
        double fz = ez < cz ? -0.45 : 0.45;
        double[][] points = {
            {cx, cy, cz}, {cx + fx, cy, cz}, {cx, cy + fy, cz}, {cx, cy, cz + fz}, {cx + fx, cy + fy, cz + fz}
        };
        for (double[] point : points) {
            used[0]++;
            if (rayClear(level, controller, ex, ey, ez, point[0], point[1], point[2], bx, by, bz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOpenSide(ServerLevel level, ChunkPacketBlockControllerAntiXray controller, int x, int y, int z) {
        return isOpen(level, controller, x + 1, y, z) || isOpen(level, controller, x - 1, y, z)
            || isOpen(level, controller, x, y + 1, z) || isOpen(level, controller, x, y - 1, z)
            || isOpen(level, controller, x, y, z + 1) || isOpen(level, controller, x, y, z - 1);
    }

    private static boolean isOpen(ServerLevel level, ChunkPacketBlockControllerAntiXray controller, int x, int y, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        return chunk != null && !controller.rtOccludes(chunk.getBlockState(new BlockPos(x, y, z)));
    }

    // Voxel traversal (Amanatides & Woo) from the eye to a point inside the target block
    private static boolean rayClear(ServerLevel level, ChunkPacketBlockControllerAntiXray controller,
                                    double sx, double sy, double sz, double tx, double ty, double tz,
                                    int targetX, int targetY, int targetZ) {
        int x = (int) Math.floor(sx);
        int y = (int) Math.floor(sy);
        int z = (int) Math.floor(sz);
        double dx = tx - sx;
        double dy = ty - sy;
        double dz = tz - sz;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.MAX_VALUE;
        double tMaxX = stepX > 0 ? (x + 1 - sx) / dx : stepX < 0 ? (sx - x) / -dx : Double.MAX_VALUE;
        double tMaxY = stepY > 0 ? (y + 1 - sy) / dy : stepY < 0 ? (sy - y) / -dy : Double.MAX_VALUE;
        double tMaxZ = stepZ > 0 ? (z + 1 - sz) / dz : stepZ < 0 ? (sz - z) / -dz : Double.MAX_VALUE;

        LevelChunk chunk = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int step = 0; step < MAX_RAY_STEPS; step++) {
            if (x == targetX && y == targetY && z == targetZ) {
                return true;
            }
            if (step > 0) { // the eye's own block never blocks
                if (chunk == null || chunk.getPos().x() != x >> 4 || chunk.getPos().z() != z >> 4) {
                    chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) {
                        return false;
                    }
                }
                BlockState state = chunk.getBlockState(pos.set(x, y, z));
                if (controller.rtOccludes(state)) {
                    return false;
                }
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                if (tMaxX > 1.0) return true;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > 1.0) return true;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1.0) return true;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return false;
    }
}
