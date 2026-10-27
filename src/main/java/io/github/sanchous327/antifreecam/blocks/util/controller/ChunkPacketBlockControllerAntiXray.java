package io.github.sanchous327.antifreecam.blocks.util.controller;

import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import io.github.sanchous327.antifreecam.AntiFreecam;
import io.github.sanchous327.antifreecam.blocks.interfaces.IChunkPacket;
import io.github.sanchous327.antifreecam.blocks.util.BitStorageReader;
import io.github.sanchous327.antifreecam.blocks.util.BitStorageWriter;
import io.github.sanchous327.antifreecam.blocks.util.ChunkPacketInfo;
import io.github.sanchous327.antifreecam.blocks.util.ChunkPacketInfoAntiXray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

public abstract class ChunkPacketBlockControllerAntiXray implements ChunkPacketBlockController {

    protected static final Palette<BlockState> GLOBAL_BLOCKSTATE_PALETTE = new GlobalPalette<>(Block.BLOCK_STATE_REGISTRY);
    private static final LevelChunkSection EMPTY_SECTION = null;
    private static final ThreadLocal<boolean[]> SOLID = ThreadLocal.withInitial(() -> new boolean[Block.BLOCK_STATE_REGISTRY.size()]);
    private static final ThreadLocal<boolean[]> OBFUSCATE = ThreadLocal.withInitial(() -> new boolean[Block.BLOCK_STATE_REGISTRY.size()]);
    // These boolean arrays represent chunk layers, true means don't obfuscate, false means obfuscate
    private static final ThreadLocal<boolean[][]> CURRENT = ThreadLocal.withInitial(() -> new boolean[16][16]);
    private static final ThreadLocal<boolean[][]> NEXT = ThreadLocal.withInitial(() -> new boolean[16][16]);
    private static final ThreadLocal<boolean[][]> NEXT_NEXT = ThreadLocal.withInitial(() -> new boolean[16][16]);
    protected final int maxBlockHeight;
    private final int maxBlockHeightUpdatePosition;
    private final int updateRadius;
    private final Object2BooleanOpenHashMap<BlockState> solidGlobal = new Object2BooleanOpenHashMap<>(Block.BLOCK_STATE_REGISTRY.size());
    private final Object2BooleanOpenHashMap<BlockState> obfuscateGlobal = new Object2BooleanOpenHashMap<>(Block.BLOCK_STATE_REGISTRY.size());
    private final LevelChunkSection[] emptyNearbyChunkSections = {EMPTY_SECTION, EMPTY_SECTION, EMPTY_SECTION, EMPTY_SECTION};
    // Actually these fields should be variables inside the obfuscate method but in sync mode or with SingleThreadExecutor in async mode it's okay (even without ThreadLocal)
    // If an ExecutorService with multiple threads is used, ThreadLocal must be used here
    private final ThreadLocal<int[]> presetBlockStateBits = ThreadLocal.withInitial(() -> new int[getPresetBlockStatesLength()]);

    protected ChunkPacketBlockControllerAntiXray(Level level, Set<Block> toObfuscate, int maxBlockHeight, int updateRadius, boolean lavaObscures) {
        this.rtLevel = level instanceof ServerLevel serverLevel ? serverLevel : null;
        this.maxBlockHeight = maxBlockHeight;
        this.updateRadius = updateRadius;

        for (Block block : toObfuscate) {
            // Don't obfuscate air because air causes unnecessary block updates and causes block updates to fail in the void
            if (!block.defaultBlockState().isAir()) {
                // Replace all block states of a specified block
                for (BlockState blockState : block.getStateDefinition().getPossibleStates()) {
                    obfuscateGlobal.put(blockState, true);
                }
            }
        }

        EmptyLevelChunk emptyChunk = new EmptyLevelChunk(level, new ChunkPos(0, 0), level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));

        Block.BLOCK_STATE_REGISTRY.iterator().forEachRemaining((blockState) -> {
                solidGlobal.put(blockState, isSolid(emptyChunk, blockState, lavaObscures));
            }
        );

        this.maxBlockHeightUpdatePosition = maxBlockHeight + updateRadius - 1;

    }

    @Override
    public boolean shouldModify(ServerPlayer player) {
        // spectators fly through walls anyway - they get the real blocks
        return !player.isSpectator() && !AntiFreecam.hasBypassPermission(player);
    }

    @Override
    public ChunkPacketInfoAntiXray getChunkPacketInfo(LevelChunk chunk) {
        // Return a new instance to collect data and objects in the right state while creating the chunk packet for thread safe access later
        return new ChunkPacketInfoAntiXray(chunk, this);
    }

    @Override
    public void onBlockChange(ServerLevel level, BlockPos blockPos, BlockState newBlockState, BlockState oldBlockState, int flags, int maxUpdateDepth) {
        if (oldBlockState != null && solidGlobal.getOrDefault(oldBlockState, false) && !solidGlobal.getOrDefault(newBlockState, false) && blockPos.getY() <= maxBlockHeightUpdatePosition) {
            updateNearbyBlocks(level, blockPos);
        }
    }

    @Override
    public void modifyBlocks(ClientboundLevelChunkWithLightPacket chunkPacket, ChunkPacketInfo<BlockState> chunkPacketInfo) {
        if (!(chunkPacketInfo instanceof ChunkPacketInfoAntiXray antiXrayInfo)) {
            IChunkPacket.antifreecam$setReady(chunkPacket, true);
            return;
        }

        if (chunkPacketInfo.getChunk().getLevel() instanceof ServerLevel serverLevel) {
            if (!serverLevel.getServer().isSameThread()) {
                serverLevel.getServer().execute(() -> modifyBlocks(chunkPacket, chunkPacketInfo));
                return;
            }
        }

        LevelChunk chunk = chunkPacketInfo.getChunk();
        int x = chunk.getPos().x();
        int z = chunk.getPos().z();
        ServerChunkCache chunkCache = ((ServerLevel) chunk.getLevel()).getChunkSource();
        antiXrayInfo.setNearbyChunks(
            getChunkAccess(chunkCache, x - 1, z),
            getChunkAccess(chunkCache, x + 1, z),
            getChunkAccess(chunkCache, x, z - 1),
            getChunkAccess(chunkCache, x, z + 1)
        );
        if (this.rtFill) {
            // only already loaded ones - a missing neighbour just makes pockets reaching it count as open
            ChunkAccess[] area = new ChunkAccess[RT_AREA_CHUNKS * RT_AREA_CHUNKS];
            for (int dz = -RT_AREA_RADIUS; dz <= RT_AREA_RADIUS; dz++) {
                for (int dx = -RT_AREA_RADIUS; dx <= RT_AREA_RADIUS; dx++) {
                    if (dx != 0 || dz != 0) {
                        area[(dz + RT_AREA_RADIUS) * RT_AREA_CHUNKS + dx + RT_AREA_RADIUS] = rtLoadedChunk(chunkCache, x + dx, z + dz);
                    }
                }
            }
            antiXrayInfo.setAreaChunks(area);
        }
        if (this.rtOn && !this.rtStructures.isEmpty()) {
            antiXrayInfo.setStructureBoxes(rtStructureBoxes((ServerLevel) chunk.getLevel(), chunkCache, chunk));
        }
        Util.backgroundExecutor().execute((Runnable) chunkPacketInfo);
    }

    // fill-structures (server thread): boxes of the pieces of the listed structures that touch this chunk. The start of
    // a structure lives in its start chunk - only an already loaded one is read (never loads or generates a chunk), a
    // structure whose start chunk isn't loaded just isn't filled here this time
    private BoundingBox[] rtStructureBoxes(ServerLevel level, ServerChunkCache chunkCache, LevelChunk chunk) {
        java.util.Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> references = chunk.getAllReferences();
        if (references.isEmpty()) {
            return null;
        }
        net.minecraft.core.Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxX = chunk.getPos().getMaxBlockX();
        int maxZ = chunk.getPos().getMaxBlockZ();
        java.util.List<BoundingBox> boxes = null;
        for (java.util.Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> entry : references.entrySet()) {
            net.minecraft.resources.Identifier id = registry.getKey(entry.getKey());
            if (id == null || !this.rtStructures.contains(id.toString())) {
                continue;
            }
            for (long startChunk : entry.getValue()) {
                ChunkAccess startAccess = rtLoadedChunk(chunkCache, ChunkPos.getX(startChunk), ChunkPos.getZ(startChunk));
                if (startAccess == null) {
                    continue;
                }
                StructureStart start = startAccess.getStartForStructure(entry.getKey());
                if (start == null || !start.isValid()) {
                    continue;
                }
                for (StructurePiece piece : start.getPieces()) {
                    BoundingBox box = piece.getBoundingBox();
                    if (box.intersects(minX, minZ, maxX, maxZ)) {
                        if (boxes == null) boxes = new java.util.ArrayList<>();
                        boxes.add(box);
                    }
                }
            }
        }
        return boxes == null ? null : boxes.toArray(new BoundingBox[0]);
    }

    private static ChunkAccess rtLoadedChunk(ServerChunkCache chunkCache, int chunkX, int chunkZ) {
        ChunkHolder chunkHolder = chunkCache.getVisibleChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
        return chunkHolder != null ? chunkHolder.getLatestChunk() : null;
    }

    private ChunkAccess getChunkAccess(ServerChunkCache chunkCache, int chunkX, int chunkZ) {
        ChunkHolder chunkHolder = chunkCache.getVisibleChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
        if (chunkHolder != null) {
            ChunkAccess chunkAccess = chunkHolder.getLatestChunk();
            if (chunkAccess != null) {
                return chunkAccess;
            } else {
                AntiFreecam.LOGGER.warn("Neighbour chunk {}, {} is not ready - loading it for block hiding", chunkX, chunkZ);
            }
        } else {
            AntiFreecam.LOGGER.warn("Neighbour chunk {}, {} is not loaded - loading it for block hiding", chunkX, chunkZ);
        }
        // Slow fallback
        return chunkCache.getChunk(chunkX, chunkZ, ChunkStatus.LIGHT, true);
    }

    @Override
    public void onPlayerLeftClickBlock(ServerPlayerGameMode serverPlayerGameMode, BlockPos blockPos, ServerboundPlayerActionPacket.Action action, Direction direction, int worldHeight) {
        if (blockPos.getY() <= maxBlockHeightUpdatePosition) {
            updateNearbyBlocks(serverPlayerGameMode.level, blockPos);
        }
    }

    private void updateNearbyBlocks(ServerLevel level, BlockPos blockPos) {
        for (int i = 0; i <= updateRadius; i++) {
            updateNearbyBlocks2d(level, blockPos.above(updateRadius - i), i);
        }
        for (int i = 0; i < updateRadius; i++) {
            updateNearbyBlocks2d(level, blockPos.below(updateRadius - i), i);
        }
    }

    private void updateNearbyBlocks2d(ServerLevel level, BlockPos blockPos, int diamondSize) {
        for (int i = 0; i <= diamondSize; i++) {
            for (int j = 0; j < 2 * i + 1; j++) {
                updateBlock(level, blockPos.offset(diamondSize - i, 0, j - i));
            }
        }
        for (int i = 0; i < diamondSize; i++) {
            for (int j = 0; j < 2 * i + 1; j++) {
                updateBlock(level, blockPos.offset(-diamondSize + i, 0, j - i));
            }
        }
    }

    protected abstract int getPresetBlockStatesLength();

    protected abstract int[] getPresetBlockStateBits(Level level, int bottomBlockY);

    private void updateBlock(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        if (chunk != null && obfuscateGlobal.getOrDefault(chunk.getBlockState(pos), false)) {
            level.getChunkSource().blockChanged(pos);
        }
    }

    public void obfuscate(ChunkPacketInfoAntiXray chunkPacketInfoAntiXray) {
        int[] presetBlockStateBits = this.presetBlockStateBits.get();
        boolean[] solid = SOLID.get();
        boolean[] obfuscate = OBFUSCATE.get();
        boolean[][] current = CURRENT.get();
        boolean[][] next = NEXT.get();
        boolean[][] nextNext = NEXT_NEXT.get();
        // bitStorageReader, bitStorageWriter and nearbyChunkSections could also be reused (with ThreadLocal if necessary) but it's not worth it
        BitStorageReader bitStorageReader = new BitStorageReader();
        BitStorageWriter bitStorageWriter = new BitStorageWriter();
        LevelChunkSection[] nearbyChunkSections = new LevelChunkSection[4];
        LevelChunk chunk = chunkPacketInfoAntiXray.getChunk();
        Level level = chunk.getLevel();
        int maxChunkSectionIndex = Math.min((maxBlockHeight >> 4) - chunk.getMinSectionY(), chunk.getSectionsCount() - 1);
        bitStorageReader.setBuffer(chunkPacketInfoAntiXray.getBuffer());
        bitStorageWriter.setBuffer(chunkPacketInfoAntiXray.getBuffer());

        for (int chunkSectionIndex = 0; chunkSectionIndex <= maxChunkSectionIndex; chunkSectionIndex++) {
            if (chunkPacketInfoAntiXray.isWritten(chunkSectionIndex) && chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex) != null) {
                int[] presetBlockStateBitsTemp;

                if (chunkPacketInfoAntiXray.getPalette(chunkSectionIndex) instanceof GlobalPalette) {
                    presetBlockStateBitsTemp = getPresetBlockStateBits(level, (chunkSectionIndex + chunk.getMinSectionY()) << 4);
                } else {
                    BlockState[] presetBlockStates = chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex);
                    presetBlockStateBitsTemp = presetBlockStateBits;

                    for (int i = 0; i < presetBlockStateBitsTemp.length; i++) {
                        // This is thread safe because we only request IDs that are guaranteed to be in the palette and are visible
                        // For more details see the comments in the readPalette method
                        presetBlockStateBitsTemp[i] = chunkPacketInfoAntiXray.getPalette(chunkSectionIndex).idFor(presetBlockStates[i], PaletteResize.noResizeExpected());
                    }
                }
                int numberOfBlocks = presetBlockStateBitsTemp.length;

                bitStorageWriter.setIndex(chunkPacketInfoAntiXray.getIndex(chunkSectionIndex));

                // Check if the chunk section below was not obfuscated
                if (chunkSectionIndex == 0 || !chunkPacketInfoAntiXray.isWritten(chunkSectionIndex - 1) || chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex - 1) == null) {
                    // If so, initialize some stuff
                    bitStorageReader.setBits(chunkPacketInfoAntiXray.getBits(chunkSectionIndex));
                    bitStorageReader.setIndex(chunkPacketInfoAntiXray.getIndex(chunkSectionIndex));
                    readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex), solid, solidGlobal);
                    readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex), obfuscate, obfuscateGlobal);
                    // Read the blocks of the upper layer of the chunk section below if it exists
                    LevelChunkSection belowChunkSection = null;
                    boolean skipFirstLayer = chunkSectionIndex == 0 || (belowChunkSection = chunk.getSections()[chunkSectionIndex - 1]) == EMPTY_SECTION;

                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            current[z][x] = true;
                            next[z][x] = skipFirstLayer || isTransparent(belowChunkSection, x, 15, z);
                        }
                    }

                    // Abuse the obfuscateLayer method to read the blocks of the first layer of the current chunk section
                    bitStorageWriter.setBits(0);
                    obfuscateLayer(-1, bitStorageReader, bitStorageWriter, solid, obfuscate, presetBlockStateBitsTemp, current, next, nextNext, emptyNearbyChunkSections, layerIntSupplier(numberOfBlocks));
                }

                bitStorageWriter.setBits(chunkPacketInfoAntiXray.getBits(chunkSectionIndex));
                nearbyChunkSections[0] = chunkPacketInfoAntiXray.getNearbyChunks()[0] == null ? EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[0].getSections()[chunkSectionIndex];
                nearbyChunkSections[1] = chunkPacketInfoAntiXray.getNearbyChunks()[1] == null ? EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[1].getSections()[chunkSectionIndex];
                nearbyChunkSections[2] = chunkPacketInfoAntiXray.getNearbyChunks()[2] == null ? EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[2].getSections()[chunkSectionIndex];
                nearbyChunkSections[3] = chunkPacketInfoAntiXray.getNearbyChunks()[3] == null ? EMPTY_SECTION : chunkPacketInfoAntiXray.getNearbyChunks()[3].getSections()[chunkSectionIndex];

                // Obfuscate all layers of the current chunk section except the upper one
                for (int y = 0; y < 15; y++) {
                    boolean[][] temp = current;
                    current = next;
                    next = nextNext;
                    nextNext = temp;
                    obfuscateLayer(y, bitStorageReader, bitStorageWriter, solid, obfuscate, presetBlockStateBitsTemp, current, next, nextNext, nearbyChunkSections, layerIntSupplier(numberOfBlocks));
                }

                // Check if the chunk section above doesn't need obfuscation
                if (chunkSectionIndex == maxChunkSectionIndex || !chunkPacketInfoAntiXray.isWritten(chunkSectionIndex + 1) || chunkPacketInfoAntiXray.getPresetValues(chunkSectionIndex + 1) == null) {
                    // If so, obfuscate the upper layer of the current chunk section by reading blocks of the first layer from the chunk section above if it exists
                    LevelChunkSection aboveChunkSection;

                    if (chunkSectionIndex != chunk.getSectionsCount() - 1 && (aboveChunkSection = chunk.getSections()[chunkSectionIndex + 1]) != EMPTY_SECTION) {
                        boolean[][] temp = current;
                        current = next;
                        next = nextNext;
                        nextNext = temp;

                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (isTransparent(aboveChunkSection, x, 0, z)) {
                                    current[z][x] = true;
                                }
                            }
                        }

                        // There is nothing to read anymore
                        bitStorageReader.setBits(0);
                        solid[0] = true;
                        obfuscateLayer(15, bitStorageReader, bitStorageWriter, solid, obfuscate, presetBlockStateBitsTemp, current, next, nextNext, nearbyChunkSections, layerIntSupplier(numberOfBlocks));
                    }
                } else {
                    // If not, initialize the reader and other stuff for the chunk section above to obfuscate the upper layer of the current chunk section
                    bitStorageReader.setBits(chunkPacketInfoAntiXray.getBits(chunkSectionIndex + 1));
                    bitStorageReader.setIndex(chunkPacketInfoAntiXray.getIndex(chunkSectionIndex + 1));
                    readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex + 1), solid, solidGlobal);
                    readPalette(chunkPacketInfoAntiXray.getPalette(chunkSectionIndex + 1), obfuscate, obfuscateGlobal);
                    boolean[][] temp = current;
                    current = next;
                    next = nextNext;
                    nextNext = temp;
                    obfuscateLayer(15, bitStorageReader, bitStorageWriter, solid, obfuscate, presetBlockStateBitsTemp, current, next, nextNext, nearbyChunkSections, layerIntSupplier(numberOfBlocks));
                }

                bitStorageWriter.flush();
            }
        }

        // block-list hiding (chests, signs, ...) - a separate pass over the finished section data
        try {
            rtPass(chunkPacketInfoAntiXray);
        } catch (RuntimeException e) {
            AntiFreecam.LOGGER.warn("Block hiding failed for a chunk, sending it as is", e);
        }

        IChunkPacket.antifreecam$setReady(chunkPacketInfoAntiXray.getChunkPacket(), true);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // blocks from rtHiddenBlocks are hidden from a player unless he can see them (see BlockReveal), no matter if they
    // are exposed to air. On chunk send every such block farther than rtNearRadius from the player's eyes is replaced
    // with the most common non-hidden neighbour inside its section (a chest in a wall becomes wall, in a room - air).
    // ---------------------------------------------------------------------------------------------------------------

    // blacklist mode: only these are hidden
    private final Object2BooleanOpenHashMap<BlockState> rtHideGlobal = new Object2BooleanOpenHashMap<>();
    // whitelist mode: everything except these (natural blocks) is hidden
    private final Object2BooleanOpenHashMap<BlockState> rtVisibleGlobal = new Object2BooleanOpenHashMap<>();
    // underground scope: only below the highest natural "cover" block (stone, dirt, sand, ...) of the column
    private final Object2BooleanOpenHashMap<BlockState> rtCoverGlobal = new Object2BooleanOpenHashMap<>();
    private boolean rtWhitelist = false;
    private boolean rtUnderground = false;
    private boolean rtOn = false;
    private double rtNearRadiusSq = 6 * 6;
    private int rtRevealDistance = 48;
    // ultra: closed air pockets (rooms, stashes) around hidden blocks are filled with solid blocks too
    private boolean rtFill = false;
    private int rtFillMax = 2048;
    // also closed pockets with nothing hidden inside (a room built only from natural blocks)
    private boolean rtFillEmpty = false;
    // also air inside buildings that are open to a cave (see rtFillBuilds)
    private boolean rtFillBuildsOn = false;

    // fill-structures: ids of structures whose pieces are filled whole (empty = off)
    private Set<String> rtStructures = Set.of();

    public void rtConfigureStructures(Set<String> structures) {
        this.rtStructures = structures;
    }

    public void rtConfigureFill(boolean fill, boolean fillEmpty, boolean fillBuilds, int maxPocket) {
        this.rtFillBuildsOn = fill && fillBuilds;
        this.rtFill = fill;
        this.rtFillEmpty = fill && fillEmpty;
        this.rtFillMax = maxPocket;
    }

    public boolean rtUnderground() {
        return rtUnderground;
    }

    public int rtFillMax() {
        return rtFillMax;
    }

    public boolean rtFillBuildsOn() {
        return rtFillBuildsOn;
    }

    public boolean rtFillPockets() {
        return rtFill;
    }

    // Solid block for a single filled cell (live updates): the most common solid non-hidden neighbour, else stone
    public BlockState rtFillAt(LevelChunk chunk, BlockPos pos) {
        BlockState[] candidates = new BlockState[6];
        int count = 0;
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            neighbour.setWithOffset(pos, direction);
            LevelChunk neighbourChunk = chunk.getLevel().getChunkSource().getChunkNow(neighbour.getX() >> 4, neighbour.getZ() >> 4);
            if (neighbourChunk == null) {
                continue;
            }
            BlockState state = neighbourChunk.getBlockState(neighbour);
            if (rtOccludes(state) && !rtHides(state)) {
                candidates[count++] = state;
            }
        }
        BlockState best = null;
        int bestCount = 0;
        for (int i = 0; i < count; i++) {
            int c = 0;
            for (int j = 0; j < count; j++) {
                if (candidates[j] == candidates[i]) c++;
            }
            if (c > bestCount) {
                bestCount = c;
                best = candidates[i];
            }
        }
        if (best != null) {
            return best;
        }
        if (chunk.getLevel().dimension() == net.minecraft.world.level.Level.NETHER) {
            return Blocks.NETHERRACK.defaultBlockState();
        }
        return pos.getY() < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    // hidden blocks lower than half a block (redstone dust, comparators, repeaters, rails, carpets, torches,
    // buttons): a player walks over/through them, so for the pocket search they are space like air, not walls
    private final Object2BooleanOpenHashMap<BlockState> rtThin = new Object2BooleanOpenHashMap<>();

    public boolean rtIsThin(BlockState state) {
        return state != null && rtThin.getBoolean(state);
    }

    public void rtConfigure(String mode, String scope, Set<Block> hidden, Set<Block> visible, Set<Block> cover, int nearRadius, int revealDistance) {
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            boolean thin;
            try {
                net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                thin = shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 0.5;
            } catch (RuntimeException e) {
                thin = false;
            }
            rtThin.put(state, thin);
        }
        rtPut(rtHideGlobal, hidden);
        rtPut(rtVisibleGlobal, visible);
        rtPut(rtCoverGlobal, cover);
        this.rtWhitelist = "whitelist".equalsIgnoreCase(mode);
        this.rtUnderground = "underground".equalsIgnoreCase(scope);
        this.rtOn = (this.rtWhitelist ? !rtVisibleGlobal.isEmpty() : !rtHideGlobal.isEmpty())
            && (!this.rtUnderground || !rtCoverGlobal.isEmpty());
        this.rtNearRadiusSq = (double) nearRadius * nearRadius;
        this.rtRevealDistance = revealDistance;
    }

    private static void rtPut(Object2BooleanOpenHashMap<BlockState> map, Set<Block> blocks) {
        for (Block block : blocks) {
            for (BlockState blockState : block.getStateDefinition().getPossibleStates()) {
                map.put(blockState, true);
            }
        }
    }

    public boolean rtEnabled() {
        return rtOn;
    }

    // Could this state be hidden at all (in underground scope the position check comes on top, see rtShouldHideAt)
    public boolean rtHides(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        return rtWhitelist ? !rtVisibleGlobal.getOrDefault(state, false) : rtHideGlobal.getOrDefault(state, false);
    }

    public boolean rtShouldHideAt(LevelChunk chunk, BlockState state, int x, int y, int z) {
        return rtHides(state) && (!rtUnderground || y < rtCoverTop(chunk, x, z));
    }

    // Replacement for a single hidden block (live updates): the most common neighbour that is not hidden itself, else air
    public BlockState rtReplacementAt(LevelChunk chunk, BlockPos pos) {
        BlockState[] candidates = new BlockState[6];
        int count = 0;
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            neighbour.setWithOffset(pos, direction);
            LevelChunk neighbourChunk = neighbour.getX() >> 4 == chunk.getPos().x() && neighbour.getZ() >> 4 == chunk.getPos().z()
                ? chunk : chunk.getLevel().getChunkSource().getChunkNow(neighbour.getX() >> 4, neighbour.getZ() >> 4);
            if (neighbourChunk == null) {
                continue;
            }
            BlockState state = neighbourChunk.getBlockState(neighbour);
            if (!rtHides(state)) {
                candidates[count++] = state;
            }
        }
        BlockState best = Blocks.AIR.defaultBlockState();
        int bestCount = 0;
        for (int i = 0; i < count; i++) {
            int c = 0;
            for (int j = 0; j < count; j++) {
                if (candidates[j] == candidates[i]) c++;
            }
            if (c > bestCount) {
                bestCount = c;
                best = candidates[i];
            }
        }
        return best;
    }

    // A hidden block that is sent as the surrounding rock (see rtPass)
    public boolean rtSolidAsRock(BlockState state) {
        return rtUnderground && rtOccludes(state);
    }

    public double rtNearRadiusSq() {
        return rtNearRadiusSq;
    }

    // Underground scope: y of the highest natural cover block in this column; blocks below it are underground
    public int rtCoverTop(ChunkAccess chunk, int x, int z) {
        // a column is underground only below the generated terrain too: a castle of diorite or stone (blocks that
        // count as ground) standing on a hill must not turn everything under its roof into "underground"
        return Math.min(rtScanCoverTop(chunk, x, z), rtTerrainTop(x, z));
    }

    // generated terrain height without anything built (sampled every 4 blocks, cached per chunk, a few blocks lower
    // to be safe); MAX_VALUE when the generator can't tell (then only the ground blocks decide)
    private static final int RT_TERRAIN_MARGIN = 3;
    private final java.util.concurrent.ConcurrentHashMap<Long, int[]> rtTerrain = new java.util.concurrent.ConcurrentHashMap<>();
    private ServerLevel rtLevel;

    private int rtTerrainTop(int x, int z) {
        ServerLevel level = this.rtLevel;
        if (level == null) {
            return Integer.MAX_VALUE;
        }
        long key = ChunkPos.pack(x >> 4, z >> 4);
        int[] cells = rtTerrain.get(key);
        if (cells == null) {
            cells = new int[16];
            int baseX = (x >> 4) << 4;
            int baseZ = (z >> 4) << 4;
            try {
                net.minecraft.world.level.chunk.ChunkGenerator generator = level.getChunkSource().getGenerator();
                net.minecraft.world.level.levelgen.RandomState random = level.getChunkSource().randomState();
                for (int cell = 0; cell < 16; cell++) {
                    cells[cell] = generator.getBaseHeight(baseX + (cell & 3) * 4 + 2, baseZ + (cell >> 2) * 4 + 2,
                        net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, random) - 1 - RT_TERRAIN_MARGIN;
                }
            } catch (RuntimeException e) {
                java.util.Arrays.fill(cells, Integer.MAX_VALUE);
            }
            if (rtTerrain.size() > 65536) {
                rtTerrain.clear();
            }
            rtTerrain.put(key, cells);
        }
        return cells[((z & 15) >> 2) * 4 + ((x & 15) >> 2)];
    }

    private int rtScanCoverTop(ChunkAccess chunk, int x, int z) {
        int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = top; y >= chunk.getMinY(); y--) {
            try {
                if (rtCoverGlobal.getOrDefault(chunk.getBlockState(pos.set(x, y, z)), false)) {
                    return y;
                }
            } catch (RuntimeException ignored) {
                // race with the server thread (async packet build) - skip this block
            }
        }
        return Integer.MIN_VALUE;
    }

    public int rtRevealDistance() {
        return rtRevealDistance;
    }

    /**
     * Full opaque block - blocks the view for the reveal raycasts
     */
    public boolean rtOccludes(BlockState state) {
        return solidGlobal.getOrDefault(state, false);
    }

    public boolean rtIsFar(ChunkPacketInfo<BlockState> info, int x, int y, int z) {
        double dx = x + 0.5 - info.rtEyeX();
        double dy = y + 0.5 - info.rtEyeY();
        double dz = z + 0.5 - info.rtEyeZ();
        return dx * dx + dy * dy + dz * dz > rtNearRadiusSq;
    }

    private void rtPass(ChunkPacketInfoAntiXray info) {
        if (!rtEnabled() || info.rtViewer() == null) {
            return;
        }
        LevelChunk chunk = info.getChunk();
        int baseX = chunk.getPos().x() << 4;
        int baseZ = chunk.getPos().z() << 4;
        int sections = chunk.getSectionsCount();
        BitStorageReader reader = new BitStorageReader();
        BitStorageWriter writer = new BitStorageWriter();
        reader.setBuffer(info.getBuffer());
        writer.setBuffer(info.getBuffer());
        // per section, null = section not in the packet buffer
        int[][] ids = new int[sections][];
        boolean[][] hide = new boolean[sections][];
        BoundingBox[] structureBoxes = info.getStructureBoxes();
        boolean[][] air = rtFill || structureBoxes != null ? new boolean[sections][] : null;
        BlockState[][] real = new BlockState[sections][];
        LevelChunkSection[] chunkSections = chunk.getSections();
        boolean[] sectionAny = new boolean[sections];
        boolean anyAtAll = false;
        int[] coverTop = null;

        // 1. read everything (pockets can span sections)
        for (int section = 0; section < sections; section++) {
            Palette<BlockState> palette = info.getPalette(section);
            if (!info.isWritten(section) || palette == null) {
                continue;
            }
            reader.setBits(info.getBits(section));
            reader.setIndex(info.getIndex(section));
            int[] sIds = new int[4096];
            boolean[] sHide = new boolean[4096];
            boolean[] sAir = air != null ? new boolean[4096] : null;
            BlockState[] sReal = new BlockState[4096];
            LevelChunkSection realSection = section < chunkSections.length ? chunkSections[section] : null;
            // flags of the REAL block (not the packet id - the ore engine put fake ores there): 1 = hidden type, 2 = air
            it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<BlockState> flagsByState = new it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<>();
            flagsByState.defaultReturnValue(-1);
            boolean any = false;
            for (int i = 0; i < 4096; i++) {
                int id = reader.read();
                sIds[i] = id;
                BlockState state = null;
                if (realSection != null) {
                    try {
                        state = realSection.getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                    } catch (RuntimeException ignored) {
                        // race with the server thread - fall back to the packet value
                    }
                }
                if (state == null) {
                    state = rtValueFor(palette, id);
                }
                sReal[i] = state;
                int flags = state == null ? 0 : flagsByState.getInt(state);
                if (flags < 0) {
                    flags = (rtHides(state) ? 1 : 0) | (state.isAir() ? 2 : 0);
                    flagsByState.put(state, flags);
                }
                sHide[i] = (flags & 1) != 0;
                if (sAir != null) sAir[i] = (flags & 2) != 0;
                any |= sHide[i];
            }
            int baseY = (chunk.getMinSectionY() + section) << 4;
            if (rtUnderground && (any || air != null)) {
                // only underground: below the highest natural cover block of the column
                if (coverTop == null) {
                    coverTop = new int[256];
                    for (int column = 0; column < 256; column++) {
                        coverTop[column] = rtCoverTop(chunk, baseX + (column & 15), baseZ + (column >> 4));
                    }
                }
                any = false;
                for (int i = 0; i < 4096; i++) {
                    if (baseY + (i >> 8) >= coverTop[i & 255]) {
                        sHide[i] = false;
                    }
                    any |= sHide[i];
                }
            }
            ids[section] = sIds;
            hide[section] = sHide;
            real[section] = sReal;
            if (air != null) air[section] = sAir;
            sectionAny[section] = any;
            anyAtAll |= any;
        }
        long chunkKey = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
        if (!anyAtAll && !this.rtFillEmpty && structureBoxes == null) {
            io.github.sanchous327.antifreecam.blocks.util.BlockReveal.setHidden(info.rtViewer(), chunk.getLevel().dimension(), chunkKey, new long[0], new long[0]);
            return;
        }

        // 2. ultra: closed air pockets around hidden blocks become solid
        boolean[][] fill = air != null && rtFill ? rtFindPockets(info, chunk, hide, air, real, coverTop) : null;
        if (fill != null && this.rtFillBuildsOn) {
            rtFillBuilds(info, chunk, hide, air, real, coverTop, fill);
        }
        // 2b. fill-structures: all air inside the pieces of the listed structures becomes rock too
        if (structureBoxes != null) {
            if (fill == null) {
                fill = new boolean[sections][];
            }
            rtFillStructures(info, chunk, air, coverTop, fill, structureBoxes);
        }

        // 3. write
        it.unimi.dsi.fastutil.longs.LongArrayList hidden = new it.unimi.dsi.fastutil.longs.LongArrayList();
        it.unimi.dsi.fastutil.longs.LongArrayList filled = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int section = 0; section < sections; section++) {
            boolean[] sFill = fill != null ? fill[section] : null;
            if (ids[section] == null || !sectionAny[section] && sFill == null) {
                continue;
            }
            Palette<BlockState> palette = info.getPalette(section);
            int[] sIds = ids[section];
            boolean[] sHide = hide[section];
            int baseY = (chunk.getMinSectionY() + section) << 4;

            int airId = rtIdFor(palette, Blocks.AIR.defaultBlockState());
            BlockState[] presets = info.getPresetValues(section);
            int presetId = presets != null && presets.length > 0 ? rtIdFor(palette, presets[0]) : -1;
            int fallback = airId >= 0 ? airId : presetId;
            int fillId = sFill != null ? rtFillId(palette, real[section], sHide, sFill, presets) : -1;
            // underground a hidden full block becomes the surrounding rock, not its neighbour (often the air of the
            // room it lines) - so walls and floors of a base don't show its shape. One that faces air staying open in
            // the packet (a cave) vanishes like before, or it would hang in the cave as a lump; -2 = not computed yet
            int solidId = -2;

            writer.setBits(info.getBits(section));
            writer.setIndex(info.getIndex(section));
            for (int i = 0; i < 4096; i++) {
                int x = i & 15;
                int z = (i >> 4) & 15;
                int y = i >> 8;
                if (sFill != null && sFill[i] && fillId >= 0) {
                    writer.write(fillId);
                    hidden.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                    filled.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                    continue;
                }
                if (sHide[i] && rtIsFar(info, baseX + x, baseY + y, baseZ + z)) {
                    int replacement = -1;
                    BlockState hiddenState = real[section][i];
                    int openSides = rtOpenAirSides(real, hide, fill, section, x, y, z);
                    // rock: a full block of the ground with at most one side to open air (ores, granite... in a cave
                    // wall stay wall; a pillar or decoration standing in a hall vanishes), a full block of a building
                    // that doesn't face open air, or anything hidden next to a filled room (comparators, redstone in
                    // a filled base)
                    if (rtUnderground && (rtOccludes(hiddenState) && (rtCoverGlobal.getOrDefault(hiddenState, false) ? openSides <= 1 : openSides == 0)
                        || openSides == 0 && rtTouchesFill(fill, section, x, y, z))) {
                        if (solidId == -2) {
                            solidId = fillId >= 0 ? fillId : rtFillId(palette, real[section], sHide, NO_FILL, presets);
                        }
                        replacement = solidId;
                    }
                    if (replacement < 0) {
                        replacement = rtReplacement(sIds, sHide, x, y, z, fallback);
                    }
                    if (replacement >= 0) {
                        writer.write(replacement);
                        hidden.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                        continue;
                    }
                }
                writer.skip();
            }
            writer.flush();
        }
        io.github.sanchous327.antifreecam.blocks.util.BlockReveal.setHidden(info.rtViewer(), chunk.getLevel().dimension(), chunkKey, hidden.toLongArray(), filled.toLongArray());
    }

    // pocket search area: this chunk and RT_AREA_RADIUS chunks around it (5x5 chunks = 80x80 columns), so a
    // building up to 3 chunks wide is seen whole from every chunk it is in and gets the same answer everywhere
    private static final int RT_AREA_RADIUS = 2;
    private static final int RT_AREA_CHUNKS = RT_AREA_RADIUS * 2 + 1;
    private static final int RT_AREA = RT_AREA_CHUNKS * 16;
    private static final int RT_OFFSET = RT_AREA_RADIUS * 16;

    // fill-builds: air inside a building is filled even when the building is open to a cave. Air and cave air are
    // the same - what tells them apart is around: from air inside a building, rays hit the building (hidden blocks
    // that aren't ores / ground) in at least RT_BUILD_HITS of the 6 directions within RT_BUILD_RAY blocks; in a cave
    // they hit natural rock.
    private static final int RT_BUILD_RAY = 12;
    private static final int RT_BUILD_HITS = 3;

    // fill-structures: air inside the pieces of the listed structures (ancient city, trial chambers, stronghold...) is
    // sent as the surrounding rock whatever the size of the room and even when it is open to a cave. Underground scope:
    // only below the ground; never closer than near-radius
    private void rtFillStructures(ChunkPacketInfoAntiXray info, LevelChunk chunk, boolean[][] air, int[] coverTop,
                                  boolean[][] fill, BoundingBox[] boxes) {
        int minY = chunk.getMinY();
        int maxY = minY + air.length * 16 - 1;
        int baseX = chunk.getPos().x() << 4;
        int baseZ = chunk.getPos().z() << 4;
        for (BoundingBox box : boxes) {
            int x0 = Math.max(box.minX(), baseX);
            int x1 = Math.min(box.maxX(), baseX + 15);
            int z0 = Math.max(box.minZ(), baseZ);
            int z1 = Math.min(box.maxZ(), baseZ + 15);
            int y0 = Math.max(box.minY(), minY);
            int y1 = Math.min(box.maxY(), maxY);
            for (int y = y0; y <= y1; y++) {
                int s = (y - minY) >> 4;
                if (air[s] == null) continue;
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        int i = (x - baseX) | ((z - baseZ) << 4) | (((y - minY) & 15) << 8);
                        if (!air[s][i]) continue;
                        if (coverTop != null && y >= coverTop[i & 255]) continue;
                        if (!rtIsFar(info, x, y, z)) continue;
                        if (fill[s] == null) fill[s] = new boolean[4096];
                        fill[s][i] = true;
                    }
                }
            }
        }
    }

    private boolean rtIsBuilt(BlockState state) {
        return state != null && rtHides(state) && !rtCoverGlobal.getOrDefault(state, false);
    }

    private void rtFillBuilds(ChunkPacketInfoAntiXray info, LevelChunk chunk, boolean[][] hide, boolean[][] air,
                              BlockState[][] real, int[] coverTop, boolean[][] fill) {
        int sections = real.length;
        int minY = chunk.getMinY();
        int baseX = chunk.getPos().x() << 4;
        int baseZ = chunk.getPos().z() << 4;
        ChunkAccess[] area = info.getAreaChunks();
        // only air in a section with a building block, or next to one, can be inside a building
        boolean[] built = new boolean[sections];
        for (int s = 0; s < sections; s++) {
            if (real[s] == null || hide[s] == null) continue;
            for (int i = 0; i < 4096; i++) {
                if (hide[s][i] && rtIsBuilt(real[s][i])) {
                    built[s] = true;
                    break;
                }
            }
        }
        for (int s = 0; s < sections; s++) {
            if (real[s] == null || air[s] == null) continue;
            if (!(built[s] || s > 0 && built[s - 1] || s + 1 < sections && built[s + 1])) continue;
            for (int i = 0; i < 4096; i++) {
                // space: air and hidden blocks a player walks through (comparators, redstone, rails)
                if (!(air[s][i] || hide[s][i] && rtIsThin(real[s][i])) || fill[s] != null && fill[s][i]) continue;
                int x = baseX + (i & 15);
                int y = minY + (s << 4) + (i >> 8);
                int z = baseZ + ((i >> 4) & 15);
                if (coverTop != null && y >= coverTop[i & 255]) continue; // underground scope: only below the ground
                if (!rtIsFar(info, x, y, z)) continue;
                int hits = 0;
                for (int dir = 0; dir < 6 && hits < RT_BUILD_HITS && hits + 6 - dir >= RT_BUILD_HITS; dir++) {
                    int dx = dir == 0 ? -1 : dir == 1 ? 1 : 0;
                    int dz = dir == 2 ? -1 : dir == 3 ? 1 : 0;
                    int dy = dir == 4 ? -1 : dir == 5 ? 1 : 0;
                    if (rtBuildRay(chunk, area, real, baseX, baseZ, minY, x, y, z, dx, dy, dz)) {
                        hits++;
                    }
                }
                if (hits >= RT_BUILD_HITS) {
                    if (fill[s] == null) fill[s] = new boolean[4096];
                    fill[s][i] = true;
                }
            }
        }
    }

    // First non-air block in this direction (within RT_BUILD_RAY) belongs to a building
    private boolean rtBuildRay(LevelChunk chunk, ChunkAccess[] area, BlockState[][] real, int baseX, int baseZ, int minY,
                               int x, int y, int z, int dx, int dy, int dz) {
        for (int step = 1; step <= RT_BUILD_RAY; step++) {
            BlockState state = rtStateAt(chunk, area, real, baseX, baseZ, minY, x + dx * step, y + dy * step, z + dz * step);
            if (state == null) {
                return false;
            }
            if (!state.isAir()) {
                return rtIsBuilt(state);
            }
        }
        return false;
    }

    private BlockState rtStateAt(LevelChunk chunk, ChunkAccess[] area, BlockState[][] real, int baseX, int baseZ, int minY,
                                 int x, int y, int z) {
        int s = (y - minY) >> 4;
        if (y < minY || s >= real.length) {
            return null;
        }
        int ox = (x >> 4) - (baseX >> 4);
        int oz = (z >> 4) - (baseZ >> 4);
        try {
            if (ox == 0 && oz == 0) {
                if (real[s] != null) {
                    return real[s][(((y - minY) & 15) << 8) | ((z & 15) << 4) | (x & 15)];
                }
                LevelChunkSection section = chunk.getSections()[s];
                return section == null ? null : section.getBlockState(x & 15, (y - minY) & 15, z & 15);
            }
            if (Math.abs(ox) > RT_AREA_RADIUS || Math.abs(oz) > RT_AREA_RADIUS || area == null) {
                return null;
            }
            ChunkAccess neighbour = area[(oz + RT_AREA_RADIUS) * RT_AREA_CHUNKS + ox + RT_AREA_RADIUS];
            return neighbour == null ? null : neighbour.getBlockState(new BlockPos(x, y, z));
        } catch (RuntimeException e) {
            return null; // race with the server thread
        }
    }

    private static final boolean[] NO_FILL = new boolean[4096];

    // A neighbour inside this chunk is filled
    private static boolean rtTouchesFill(boolean[][] fill, int s, int x, int y, int z) {
        if (fill == null) return false;
        return x > 0 && rtFilled(fill, s, (y << 8) | (z << 4) | (x - 1))
            || x < 15 && rtFilled(fill, s, (y << 8) | (z << 4) | (x + 1))
            || z > 0 && rtFilled(fill, s, (y << 8) | ((z - 1) << 4) | x)
            || z < 15 && rtFilled(fill, s, (y << 8) | ((z + 1) << 4) | x)
            || (y > 0 ? rtFilled(fill, s, ((y - 1) << 8) | (z << 4) | x) : rtFilled(fill, s - 1, (15 << 8) | (z << 4) | x))
            || (y < 15 ? rtFilled(fill, s, ((y + 1) << 8) | (z << 4) | x) : rtFilled(fill, s + 1, (z << 4) | x));
    }

    private static boolean rtFilled(boolean[][] fill, int s, int index) {
        return s >= 0 && s < fill.length && fill[s] != null && fill[s][index];
    }

    // How many neighbours inside this chunk show as air in the packet: real air or a hidden block that isn't a full block
    // (comparators, hoppers, slabs, fences vanish into their neighbours) - and it isn't filled
    private int rtOpenAirSides(BlockState[][] real, boolean[][] hide, boolean[][] fill, int s, int x, int y, int z) {
        int sides = 0;
        if (x > 0 && rtOpenAir(real, hide, fill, s, (y << 8) | (z << 4) | (x - 1))) sides++;
        if (x < 15 && rtOpenAir(real, hide, fill, s, (y << 8) | (z << 4) | (x + 1))) sides++;
        if (z > 0 && rtOpenAir(real, hide, fill, s, (y << 8) | ((z - 1) << 4) | x)) sides++;
        if (z < 15 && rtOpenAir(real, hide, fill, s, (y << 8) | ((z + 1) << 4) | x)) sides++;
        if (y > 0 ? rtOpenAir(real, hide, fill, s, ((y - 1) << 8) | (z << 4) | x) : rtOpenAir(real, hide, fill, s - 1, (15 << 8) | (z << 4) | x)) sides++;
        if (y < 15 ? rtOpenAir(real, hide, fill, s, ((y + 1) << 8) | (z << 4) | x) : rtOpenAir(real, hide, fill, s + 1, (z << 4) | x)) sides++;
        return sides;
    }

    private boolean rtOpenAir(BlockState[][] real, boolean[][] hide, boolean[][] fill, int s, int index) {
        if (s < 0 || s >= real.length || real[s] == null) {
            return false;
        }
        if (fill != null && fill[s] != null && fill[s][index]) {
            return false;
        }
        BlockState state = real[s][index];
        return state != null && (state.isAir() || hide[s] != null && hide[s][index] && !rtOccludes(state));
    }

    private static final int RT_OPEN = 0;
    private static final int RT_WALL = 1;
    private static final int RT_PASS = 2;
    // a hidden block a player can't walk through (planks, cobblestone, a door, a slab in the wall): it closes the
    // pocket like stone does, and is filled together with it
    private static final int RT_HIDDEN_WALL = 3;

    // Ultra: finds closed pockets of air and fills them. A search starts from air that touches a hidden block (or,
    // with fill-empty-pockets, from any air below the ground) and runs through the chunks around (see RT_AREA).
    // Redstone, rails, carpets... count as space; every hidden block a player can't walk through - doors, slabs,
    // stairs in a wall - closes the pocket like stone: a slab in a wall next to a cave must not make a base part of the cave. A pocket of at most
    // fill-max-pocket cells closed on all sides is a room / stash: its cells in this chunk and the hidden blocks
    // around it get filled. It is OPEN (left as is) when
    // it reaches the surface (air above the cover in underground scope), the player (his own room), the edge of the
    // searched area or an unloaded chunk - caves are never filled.
    private boolean[][] rtFindPockets(ChunkPacketInfoAntiXray info, LevelChunk chunk, boolean[][] hide, boolean[][] air,
                                      BlockState[][] real, int[] coverTop) {
        int sections = hide.length;
        int height = sections << 4;
        int baseX = chunk.getPos().x() << 4;
        int baseZ = chunk.getPos().z() << 4;
        int minY = chunk.getMinY();
        ChunkAccess[] area = info.getAreaChunks();
        boolean[][] fill = new boolean[sections][];
        it.unimi.dsi.fastutil.ints.IntOpenHashSet open = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        it.unimi.dsi.fastutil.ints.IntOpenHashSet done = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        it.unimi.dsi.fastutil.ints.IntOpenHashSet visited = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap neighbourCover = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        it.unimi.dsi.fastutil.ints.IntArrayList queue = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayList walls = new it.unimi.dsi.fastutil.ints.IntArrayList();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // fill-empty-pockets: air below the ground starts a search too (below the cover, or below the surface
        // heightmap in "everywhere" scope)
        int[] airTop = null;
        if (this.rtFillEmpty) {
            airTop = coverTop;
            if (airTop == null) {
                airTop = new int[256];
                for (int column = 0; column < 256; column++) {
                    airTop[column] = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, column & 15, column >> 4);
                }
            }
        }

        for (int s = 0; s < sections; s++) {
            if (hide[s] == null) continue;
            for (int i = 0; i < 4096; i++) {
                if (!rtIsSpace(air, hide, real, s, i)) continue;
                boolean underground = airTop != null && air[s][i] && minY + (s << 4) + (i >> 8) < airTop[i & 255];
                if (!underground && !hide[s][i] && !rtTouchesHidden(hide, s, i)) continue;
                int seed = rtCell((i & 15) + RT_OFFSET, (s << 4) + (i >> 8), ((i >> 4) & 15) + RT_OFFSET);
                if (done.contains(seed) || open.contains(seed)) continue;
                if (!rtIsFar(info, baseX + (i & 15), minY + (s << 4) + (i >> 8), baseZ + ((i >> 4) & 15))) continue;
                queue.clear();
                walls.clear();
                visited.clear();
                queue.add(seed);
                visited.add(seed);
                boolean isOpen = false;
                for (int head = 0; head < queue.size() && !isOpen; head++) {
                    if (queue.size() > rtFillMax) {
                        isOpen = true;
                        break;
                    }
                    int c = queue.getInt(head);
                    int cx = c % RT_AREA;
                    int cz = (c / RT_AREA) % RT_AREA;
                    int cy = c / (RT_AREA * RT_AREA);
                    for (int dir = 0; dir < 6; dir++) {
                        int nx = cx + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                        int nz = cz + (dir == 2 ? -1 : dir == 3 ? 1 : 0);
                        int ny = cy + (dir == 4 ? -1 : dir == 5 ? 1 : 0);
                        if (ny < 0) continue; // world bottom
                        if (nx < 0 || nx >= RT_AREA || nz < 0 || nz >= RT_AREA || ny >= height) {
                            isOpen = true;
                            break;
                        }
                        int key = rtCell(nx, ny, nz);
                        if (visited.contains(key)) continue;
                        if (open.contains(key)) {
                            isOpen = true;
                            break;
                        }
                        int kind = rtCellKind(info, chunk, area, hide, air, real, coverTop, neighbourCover, pos, baseX, baseZ, minY, nx, ny, nz);
                        if (kind == RT_OPEN) {
                            isOpen = true;
                            break;
                        }
                        if (kind == RT_PASS) {
                            visited.add(key);
                            queue.add(key);
                        } else if (kind == RT_HIDDEN_WALL) {
                            visited.add(key);
                            walls.add(key);
                        }
                    }
                }
                if (isOpen || queue.size() < RT_SMALL_POCKET && rtBordersOtherSpace(info, chunk, area, hide, air, real,
                    coverTop, neighbourCover, pos, baseX, baseZ, minY, height, walls, visited)) {
                    open.addAll(queue);
                    continue;
                }
                done.addAll(queue);
                rtMarkFill(fill, queue);
                rtMarkFill(fill, walls);
            }
        }
        return fill;
    }

    private static final int RT_SMALL_POCKET = 64;

    // A small pocket that borders other space through its walls is a nook of a bigger space (under a bar counter,
    // between fences and slabs) - filled, it would hang in that space as a lump whenever the bigger space stays open.
    // Not filling it is safe: inside a filled bigger space it stays an enclosed hole nobody sees.
    private boolean rtBordersOtherSpace(ChunkPacketInfoAntiXray info, LevelChunk chunk, ChunkAccess[] area, boolean[][] hide,
                                        boolean[][] air, BlockState[][] real, int[] coverTop,
                                        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap neighbourCover, BlockPos.MutableBlockPos pos,
                                        int baseX, int baseZ, int minY, int height,
                                        it.unimi.dsi.fastutil.ints.IntArrayList walls, it.unimi.dsi.fastutil.ints.IntOpenHashSet visited) {
        for (int w = 0; w < walls.size(); w++) {
            int c = walls.getInt(w);
            int cx = c % RT_AREA;
            int cz = (c / RT_AREA) % RT_AREA;
            int cy = c / (RT_AREA * RT_AREA);
            for (int dir = 0; dir < 6; dir++) {
                int nx = cx + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                int nz = cz + (dir == 2 ? -1 : dir == 3 ? 1 : 0);
                int ny = cy + (dir == 4 ? -1 : dir == 5 ? 1 : 0);
                if (ny < 0 || ny >= height || nx < 0 || nx >= RT_AREA || nz < 0 || nz >= RT_AREA) continue;
                if (visited.contains(rtCell(nx, ny, nz))) continue;
                int kind = rtCellKind(info, chunk, area, hide, air, real, coverTop, neighbourCover, pos, baseX, baseZ, minY, nx, ny, nz);
                if (kind == RT_PASS || kind == RT_OPEN) {
                    return true;
                }
            }
        }
        return false;
    }

    // Cells of this chunk (the middle of the area) are filled; neighbour chunks fill theirs in their own packets
    private static void rtMarkFill(boolean[][] fill, it.unimi.dsi.fastutil.ints.IntArrayList cells) {
        for (int q = 0; q < cells.size(); q++) {
            int c = cells.getInt(q);
            int cx = c % RT_AREA;
            int cz = (c / RT_AREA) % RT_AREA;
            int cy = c / (RT_AREA * RT_AREA);
            if (cx < RT_OFFSET || cx >= RT_OFFSET + 16 || cz < RT_OFFSET || cz >= RT_OFFSET + 16) continue;
            int gs = cy >> 4;
            if (fill[gs] == null) fill[gs] = new boolean[4096];
            fill[gs][((cy & 15) << 8) | ((cz & 15) << 4) | (cx & 15)] = true;
        }
    }

    // Space a pocket consists of: air and hidden blocks a player walks through (see rtThin)
    private boolean rtIsSpace(boolean[][] air, boolean[][] hide, BlockState[][] real, int s, int i) {
        return air[s][i] || hide[s][i] && rtIsThin(real[s][i]);
    }

    // A neighbour inside this chunk is hidden (the search starts next to hidden blocks, e.g. inside a base)
    private static boolean rtTouchesHidden(boolean[][] hide, int s, int i) {
        int x = i & 15;
        int z = (i >> 4) & 15;
        int y = i >> 8;
        return x > 0 && hide[s][i - 1] || x < 15 && hide[s][i + 1]
            || z > 0 && hide[s][i - 16] || z < 15 && hide[s][i + 16]
            || (y > 0 ? hide[s][i - 256] : s > 0 && hide[s - 1] != null && hide[s - 1][i + 15 * 256])
            || (y < 15 ? hide[s][i + 256] : s + 1 < hide.length && hide[s + 1] != null && hide[s + 1][i - 15 * 256]);
    }

    private static int rtCell(int areaX, int y, int areaZ) {
        return (y * RT_AREA + areaZ) * RT_AREA + areaX;
    }

    private int rtCellKind(ChunkPacketInfoAntiXray info, LevelChunk chunk, ChunkAccess[] area, boolean[][] hide, boolean[][] air,
                           BlockState[][] real, int[] coverTop, it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap neighbourCover,
                           BlockPos.MutableBlockPos pos, int baseX, int baseZ, int minY, int nx, int ny, int nz) {
        int ox = (nx >> 4) - RT_AREA_RADIUS;
        int oz = (nz >> 4) - RT_AREA_RADIUS;
        int wx = baseX + nx - RT_OFFSET;
        int wy = minY + ny;
        int wz = baseZ + nz - RT_OFFSET;
        if (!rtIsFar(info, wx, wy, wz)) {
            return RT_OPEN; // reaches the player - that's his own room
        }
        if (ox == 0 && oz == 0) {
            int s = ny >> 4;
            int i = ((ny & 15) << 8) | ((nz & 15) << 4) | (nx & 15);
            if (hide[s] == null) {
                // a section of one single block (all deepslate, all air) has no block data in the packet - read it
                LevelChunkSection section = s < chunk.getSections().length ? chunk.getSections()[s] : null;
                if (section == null) return RT_OPEN;
                BlockState state;
                try {
                    state = section.getBlockState(nx & 15, ny & 15, nz & 15);
                } catch (RuntimeException e) {
                    return RT_OPEN;
                }
                boolean isAir = state.isAir();
                if (rtUnderground && coverTop != null && wy >= coverTop[((nz & 15) << 4) | (nx & 15)]) {
                    return isAir ? RT_OPEN : RT_WALL;
                }
                return isAir ? RT_PASS : !rtHides(state) ? RT_WALL : rtIsThin(state) ? RT_PASS : RT_HIDDEN_WALL;
            }
            if (rtUnderground && coverTop != null && wy >= coverTop[((nz & 15) << 4) | (nx & 15)]) {
                return air[s][i] ? RT_OPEN : RT_WALL;
            }
            if (air[s][i]) return RT_PASS;
            if (hide[s][i]) return rtIsThin(real[s][i]) ? RT_PASS : RT_HIDDEN_WALL;
            return RT_WALL;
        }
        ChunkAccess neighbour = area == null ? null : area[(oz + RT_AREA_RADIUS) * RT_AREA_CHUNKS + ox + RT_AREA_RADIUS];
        if (neighbour == null) {
            return RT_OPEN;
        }
        BlockState state;
        try {
            state = neighbour.getBlockState(pos.set(wx, wy, wz));
        } catch (RuntimeException e) {
            return RT_OPEN;
        }
        boolean isAir = state.isAir();
        if (rtUnderground) {
            int column = nx * RT_AREA + nz;
            int top;
            if (neighbourCover.containsKey(column)) {
                top = neighbourCover.get(column);
            } else {
                top = rtCoverTop(neighbour, wx, wz);
                neighbourCover.put(column, top);
            }
            if (wy >= top) {
                return isAir ? RT_OPEN : RT_WALL;
            }
        }
        if (isAir) return RT_PASS;
        if (rtHides(state)) return rtIsThin(state) ? RT_PASS : RT_HIDDEN_WALL;
        return RT_WALL;
    }

    /**
     * /antifreecam pocket: runs the same pocket search as a chunk packet does (same cell rules, same 5x5 area), for a
     * viewer far away, from the air block at start - and says why the pocket is or isn't filled.
     */
    public String rtProbe(ServerLevel level, BlockPos start) {
        if (!this.rtFill) {
            return "fill-pockets is off in this world";
        }
        ServerChunkCache chunkCache = level.getChunkSource();
        LevelChunk chunk = chunkCache.getChunkNow(start.getX() >> 4, start.getZ() >> 4);
        if (chunk == null) {
            return "this chunk is not loaded";
        }
        int sections = chunk.getSectionsCount();
        int minY = chunk.getMinY();
        int baseX = chunk.getPos().x() << 4;
        int baseZ = chunk.getPos().z() << 4;
        int[] coverTop = null;
        if (this.rtUnderground) {
            coverTop = new int[256];
            for (int column = 0; column < 256; column++) {
                coverTop[column] = rtCoverTop(chunk, baseX + (column & 15), baseZ + (column >> 4));
            }
        }
        boolean[][] hide = new boolean[sections][];
        boolean[][] air = new boolean[sections][];
        BlockState[][] real = new BlockState[sections][];
        LevelChunkSection[] chunkSections = chunk.getSections();
        for (int s = 0; s < sections; s++) {
            if (s >= chunkSections.length || chunkSections[s] == null) continue;
            hide[s] = new boolean[4096];
            air[s] = new boolean[4096];
            real[s] = new BlockState[4096];
            for (int i = 0; i < 4096; i++) {
                BlockState state = chunkSections[s].getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                real[s][i] = state;
                air[s][i] = state.isAir();
                hide[s][i] = rtHides(state) && (coverTop == null || minY + (s << 4) + (i >> 8) < coverTop[i & 255]);
            }
        }
        ChunkAccess[] area = new ChunkAccess[RT_AREA_CHUNKS * RT_AREA_CHUNKS];
        for (int dz = -RT_AREA_RADIUS; dz <= RT_AREA_RADIUS; dz++) {
            for (int dx = -RT_AREA_RADIUS; dx <= RT_AREA_RADIUS; dx++) {
                if (dx != 0 || dz != 0) {
                    area[(dz + RT_AREA_RADIUS) * RT_AREA_CHUNKS + dx + RT_AREA_RADIUS] =
                        rtLoadedChunk(chunkCache, chunk.getPos().x() + dx, chunk.getPos().z() + dz);
                }
            }
        }
        // a viewer nobody is near to
        ChunkPacketInfoAntiXray info = new ChunkPacketInfoAntiXray(chunk, this);
        info.rtSetViewer(null, baseX, 1.0E7, baseZ);

        int sy = start.getY() - minY;
        int ss = sy >> 4;
        int si = ((sy & 15) << 8) | ((start.getZ() & 15) << 4) | (start.getX() & 15);
        if (ss < 0 || ss >= sections || air[ss] == null || !(air[ss][si] || hide[ss][si] && rtIsThin(real[ss][si]))) {
            return "not an air block - give the coordinates of an air block inside the room";
        }
        int height = sections << 4;
        it.unimi.dsi.fastutil.ints.IntOpenHashSet visited = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        it.unimi.dsi.fastutil.ints.IntArrayList queue = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap neighbourCover = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        it.unimi.dsi.fastutil.ints.IntArrayList walls = new it.unimi.dsi.fastutil.ints.IntArrayList();
        int seed = rtCell((start.getX() & 15) + RT_OFFSET, sy, (start.getZ() & 15) + RT_OFFSET);
        queue.add(seed);
        visited.add(seed);
        for (int head = 0; head < queue.size(); head++) {
            if (queue.size() > this.rtFillMax) {
                return "OPEN - more than " + this.rtFillMax + " air blocks (fill-max-pocket)";
            }
            int c = queue.getInt(head);
            int cx = c % RT_AREA;
            int cz = (c / RT_AREA) % RT_AREA;
            int cy = c / (RT_AREA * RT_AREA);
            for (int dir = 0; dir < 6; dir++) {
                int nx = cx + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                int nz = cz + (dir == 2 ? -1 : dir == 3 ? 1 : 0);
                int ny = cy + (dir == 4 ? -1 : dir == 5 ? 1 : 0);
                if (ny < 0) continue;
                int wx = baseX + nx - RT_OFFSET;
                int wy = minY + ny;
                int wz = baseZ + nz - RT_OFFSET;
                if (nx < 0 || nx >= RT_AREA || nz < 0 || nz >= RT_AREA) {
                    return "OPEN - goes farther than 2 chunks from this chunk at " + wx + " " + wy + " " + wz;
                }
                if (ny >= height) {
                    return "OPEN - reaches the top of the world at " + wx + " " + wy + " " + wz;
                }
                int key = rtCell(nx, ny, nz);
                if (visited.contains(key)) continue;
                int kind = rtCellKind(info, chunk, area, hide, air, real, coverTop, neighbourCover, pos, baseX, baseZ, minY, nx, ny, nz);
                if (kind == RT_OPEN) {
                    int ox = (nx >> 4) - RT_AREA_RADIUS;
                    int oz = (nz >> 4) - RT_AREA_RADIUS;
                    boolean missing = (ox != 0 || oz != 0) && area[(oz + RT_AREA_RADIUS) * RT_AREA_CHUNKS + ox + RT_AREA_RADIUS] == null;
                    return (missing ? "OPEN - reaches a chunk that is not loaded at "
                        : "OPEN - reaches the surface (air above the ground) at ") + wx + " " + wy + " " + wz;
                }
                if (kind == RT_PASS) {
                    visited.add(key);
                    queue.add(key);
                } else if (kind == RT_HIDDEN_WALL) {
                    visited.add(key);
                    walls.add(key);
                }
            }
        }
        if (queue.size() < RT_SMALL_POCKET && rtBordersOtherSpace(info, chunk, area, hide, air, real, coverTop, neighbourCover,
            pos, baseX, baseZ, minY, height, walls, visited)) {
            return "OPEN - a small nook (" + queue.size() + " blocks) of a bigger space behind its slabs/fences - not filled,"
                + " so it can't hang as a lump; check the bigger space";
        }
        return "CLOSED - " + queue.size() + " air blocks and " + walls.size() + " hidden blocks around them are filled"
            + " for everyone farther than near-radius";
    }

    // Fill block of a section: its most common real solid block that is not hidden itself (stone, deepslate, ...)
    private int rtFillId(Palette<BlockState> palette, BlockState[] real, boolean[] hide, boolean[] fill, BlockState[] presets) {
        it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<BlockState> counts = new it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<>();
        BlockState best = null;
        int bestCount = 0;
        for (int i = 0; i < 4096; i++) {
            BlockState state = real[i];
            if (hide[i] || fill[i] || state == null || !rtOccludes(state) || rtHides(state)) continue;
            int c = counts.addTo(state, 1) + 1;
            if (c > bestCount) {
                bestCount = c;
                best = state;
            }
        }
        if (best != null) {
            int id = rtIdFor(palette, best);
            if (id >= 0) return id;
        }
        if (presets != null) {
            for (BlockState preset : presets) {
                if (rtOccludes(preset) && !rtHides(preset)) {
                    int id = rtIdFor(palette, preset);
                    if (id >= 0) return id;
                }
            }
        }
        return -1;
    }

    // Most common neighbour (6 directions, same section only - its id is guaranteed to be in the palette) that is not hidden itself
    private static int rtReplacement(int[] ids, boolean[] hide, int x, int y, int z, int fallback) {
        int[] candidates = new int[6];
        int count = 0;
        if (x > 0) count = rtAdd(ids, hide, (y << 8) | (z << 4) | (x - 1), candidates, count);
        if (x < 15) count = rtAdd(ids, hide, (y << 8) | (z << 4) | (x + 1), candidates, count);
        if (z > 0) count = rtAdd(ids, hide, (y << 8) | ((z - 1) << 4) | x, candidates, count);
        if (z < 15) count = rtAdd(ids, hide, (y << 8) | ((z + 1) << 4) | x, candidates, count);
        if (y > 0) count = rtAdd(ids, hide, ((y - 1) << 8) | (z << 4) | x, candidates, count);
        if (y < 15) count = rtAdd(ids, hide, ((y + 1) << 8) | (z << 4) | x, candidates, count);
        if (count == 0) {
            return fallback;
        }
        int best = candidates[0];
        int bestCount = 0;
        for (int i = 0; i < count; i++) {
            int c = 0;
            for (int j = 0; j < count; j++) {
                if (candidates[j] == candidates[i]) c++;
            }
            if (c > bestCount) {
                bestCount = c;
                best = candidates[i];
            }
        }
        return best;
    }

    private static int rtAdd(int[] ids, boolean[] hide, int index, int[] candidates, int count) {
        if (!hide[index]) {
            candidates[count++] = ids[index];
        }
        return count;
    }

    // Read-only palette lookups: Palette#idFor may ADD a missing value to the live palette - never call it here
    private static BlockState rtValueFor(Palette<BlockState> palette, int id) {
        try {
            return palette.valueFor(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int rtIdFor(Palette<BlockState> palette, BlockState state) {
        if (palette instanceof GlobalPalette) {
            return Block.BLOCK_STATE_REGISTRY.getId(state);
        }
        try {
            for (int id = 0; id < palette.getSize(); id++) {
                if (palette.valueFor(id) == state) {
                    return id;
                }
            }
        } catch (RuntimeException ignored) {
            // race with the server thread - just treat as missing
        }
        return -1;
    }

    private void obfuscateLayer(int y, BitStorageReader bitStorageReader, BitStorageWriter bitStorageWriter, boolean[] solid, boolean[] obfuscate, int[] presetBlockStateBits, boolean[][] current, boolean[][] next, boolean[][] nextNext, LevelChunkSection[] nearbyChunkSections, IntSupplier random) {
        int bits;
        for (int z = 0; z <= 15; z++) {
            for (int x = 0; x <= 15; x++) {
                bits = bitStorageReader.read();
                if (nextNext[z][x] = !solid[bits]) {
                    bitStorageWriter.skip();
                    if (x > 0) next[z][x - 1] = true;
                    if (x < 15) next[z][x + 1] = true;
                    if (z > 0) next[z - 1][x] = true;
                    if (z < 15) next[z + 1][x] = true;
                } else {
                    // Check neighbouring chunks
                    boolean negX = x == 0 && isTransparent(nearbyChunkSections[0], 15, y, z);
                    boolean posX = x == 15 && isTransparent(nearbyChunkSections[1], 0, y, z);
                    boolean negZ = z == 0 && isTransparent(nearbyChunkSections[2], x, y, 15);
                    boolean posZ = z == 15 && isTransparent(nearbyChunkSections[3], x, y, 0);
                    if (current[z][x] || negX || posX || negZ || posZ) {
                        bitStorageWriter.skip();
                    } else {
                        bitStorageWriter.write(presetBlockStateBits[random.getAsInt()]);
                    }
                }
                if (!obfuscate[bits]) {
                    next[z][x] = true;
                }
            }
        }
    }

    private boolean isTransparent(LevelChunkSection chunkSection, int x, int y, int z) {
        if (chunkSection == EMPTY_SECTION) {
            return true;
        }

        try {
            return !solidGlobal.getOrDefault(chunkSection.getBlockState(x, y, z), false);
        } catch (MissingPaletteEntryException e) {
            // Race condition / visibility issue / no happens-before relationship
            // We don't care and treat the block as transparent
            // Internal implementation details of PalettedContainer, LinearPalette, HashMapPalette, CrudeIncrementalIntIdentityHashBiMap, ... guarantee us that no (other) exceptions will occur
            return true;
        }
    }

    private void readPalette(Palette<BlockState> palette, boolean[] temp, Object2BooleanOpenHashMap<BlockState> global) {
        try {
            for (int i = 0; i < palette.getSize(); i++) {
                temp[i] = global.getOrDefault(palette.valueFor(i), false);
            }
        } catch (MissingPaletteEntryException e) {
            // Race condition / visibility issue / no happens-before relationship
            // We don't care because we at least see the state as it was when the chunk packet was created
            // Internal implementation details of PalettedContainer, LinearPalette, HashMapPalette, CrudeIncrementalIntIdentityHashBiMap, ... guarantee us that no (other) exceptions will occur until we have all the data that we need here
            // Since all palettes have a fixed initial maximum size and there is no internal restructuring and no values are removed from palettes, we are also guaranteed to see the data
        }

    }

    public IntSupplier layerIntSupplier(int numberOfBlocks) {
        return numberOfBlocks == 1 ? (() -> 0) : new IntSupplier() {
            private int state;

            {
                while ((state = ThreadLocalRandom.current().nextInt()) == 0) ;
            }

            @Override
            public int getAsInt() {
                // https://en.wikipedia.org/wiki/Xorshift
                state ^= state << 13;
                state ^= state >>> 17;
                state ^= state << 5;
                // https://www.pcg-random.org/posts/bounded-rands.html
                return (int) ((Integer.toUnsignedLong(state) * numberOfBlocks) >>> 32);
            }
        };
    }

    private static boolean isSolid(EmptyLevelChunk emptyChunk, BlockState blockState, boolean lavaObscures) {
        return (Block.isShapeFullBlock(blockState.getVisualShape(emptyChunk, BlockPos.ZERO, CollisionContext.empty())) && blockState.canOcclude()) || (lavaObscures && blockState == Blocks.LAVA.defaultBlockState());
        // Comparing blockState == Blocks.LAVA.defaultBlockState() instead of blockState.getBlock() == Blocks.LAVA ensures that only "stationary lava" is used
    }

}
