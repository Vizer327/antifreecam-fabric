package io.github.sanchous327.antifreecam.blocks.util;

import io.github.sanchous327.antifreecam.blocks.util.controller.ChunkPacketBlockControllerAntiXray;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkPacketInfoAntiXray extends ChunkPacketInfo<BlockState> implements Runnable {

    private final ChunkPacketBlockControllerAntiXray chunkPacketBlockControllerAntiXray;
    private ChunkAccess[] nearbyChunks;
    // pocket search: the 5x5 chunks around this one, index (dz + 2) * 5 + (dx + 2); null = this chunk / not loaded
    private ChunkAccess[] areaChunks;

    public ChunkPacketInfoAntiXray(LevelChunk chunk, ChunkPacketBlockControllerAntiXray chunkPacketBlockControllerAntiXray) {
        super(chunk);
        this.chunkPacketBlockControllerAntiXray = chunkPacketBlockControllerAntiXray;
    }

    public ChunkPacketBlockControllerAntiXray getController() {
        return chunkPacketBlockControllerAntiXray;
    }

    public ChunkAccess[] getNearbyChunks() {
        return nearbyChunks;
    }

    public void setNearbyChunks(ChunkAccess... nearbyChunks) {
        this.nearbyChunks = nearbyChunks;
    }

    public ChunkAccess[] getAreaChunks() {
        return areaChunks;
    }

    public void setAreaChunks(ChunkAccess[] areaChunks) {
        this.areaChunks = areaChunks;
    }

    @Override
    public void run() {
        chunkPacketBlockControllerAntiXray.obfuscate(this);
    }
}