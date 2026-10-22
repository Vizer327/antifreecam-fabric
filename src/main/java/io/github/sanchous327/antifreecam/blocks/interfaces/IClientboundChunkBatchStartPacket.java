package io.github.sanchous327.antifreecam.blocks.interfaces;

public interface IClientboundChunkBatchStartPacket extends IPacket {

    void antifreecam$setBatchSize(int batchSize);

    void antifreecam$notifyChunkReady();

}
