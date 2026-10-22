package io.github.sanchous327.antifreecam.blocks.util.controller;

import io.github.sanchous327.antifreecam.blocks.interfaces.IChunkPacket;
import io.github.sanchous327.antifreecam.blocks.util.ChunkPacketInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public class DisabledChunkPacketBlockController implements ChunkPacketBlockController {

    public static final DisabledChunkPacketBlockController NO_OPERATION_INSTANCE = new DisabledChunkPacketBlockController();

    private DisabledChunkPacketBlockController() {
    }

    @Override
    public boolean shouldModify(ServerPlayer player) {
        return false;
    }

    @Override
    public BlockState[] getPresetBlockStates(Level level, int bottomBlockY) {
        return null;
    }

    @Override
    public ChunkPacketInfo<BlockState> getChunkPacketInfo(LevelChunk chunk) {
        return null;
    }

    @Override
    public void modifyBlocks(ClientboundLevelChunkWithLightPacket chunkPacket, ChunkPacketInfo<BlockState> chunkPacketInfo) {
        IChunkPacket.antifreecam$setReady(chunkPacket, true);
    }

    @Override
    public void onBlockChange(ServerLevel level, BlockPos blockPos, BlockState newBlockState, BlockState oldBlockState, int flags, int maxUpdateDepth) {
    }

    @Override
    public void onPlayerLeftClickBlock(ServerPlayerGameMode serverPlayerGameMode, BlockPos blockPos, ServerboundPlayerActionPacket.Action action, Direction direction, int worldHeight) {
    }
}
