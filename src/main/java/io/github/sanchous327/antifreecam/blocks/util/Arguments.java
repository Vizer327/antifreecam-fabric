package io.github.sanchous327.antifreecam.blocks.util;

import io.github.sanchous327.antifreecam.blocks.interfaces.IClientboundChunkBatchStartPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.List;

/**
 * Some of these arguments are used by multiple methods. This allows them to be accessed by all called methods, but
 * may cause them to be present 'unexpectedly'.
 */
public class Arguments {
    public static final ScopedValue<ChunkPacketInfo<BlockState>> PACKET_INFO = ScopedValue.newInstance();
    public static final ScopedValue<ChunkAccess> CHUNK_ACCESS = ScopedValue.newInstance();
    public static final ScopedValue<Integer> CHUNK_SECTION_INDEX = ScopedValue.newInstance();
    public static final ScopedValue<Object[]> PRESET_VALUES = ScopedValue.newInstance();
    public static final ScopedValue<IClientboundChunkBatchStartPacket> BATCH_START_PACKET = ScopedValue.newInstance();
    public static final ScopedValue<ServerGamePacketListenerImpl> PACKET_LISTENER = ScopedValue.newInstance();
    public static final ScopedValue<List<?>> PALETTE_ENTRIES = ScopedValue.newInstance();
}
