package io.github.sanchous327.antifreecam.blocks.interfaces;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

public interface IChunkPacket extends IPacket {
    void antifreecam$setReady(boolean antifreecam$ready);

    static void antifreecam$setReady(ClientboundLevelChunkWithLightPacket chunkPacket, boolean ready) {
        ((IChunkPacket) (Object) chunkPacket).antifreecam$setReady(ready);
    }
}
