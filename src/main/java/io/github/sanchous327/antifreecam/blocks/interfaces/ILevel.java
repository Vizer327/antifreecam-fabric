package io.github.sanchous327.antifreecam.blocks.interfaces;

import io.github.sanchous327.antifreecam.blocks.util.controller.ChunkPacketBlockController;

public interface ILevel {

    void initValues();

    ChunkPacketBlockController getChunkPacketBlockController();

}
