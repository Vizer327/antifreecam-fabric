package io.github.sanchous327.antifreecam.mixin.blocks;

import io.github.sanchous327.antifreecam.blocks.interfaces.IPacket;
import io.github.sanchous327.antifreecam.blocks.util.Util;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.spongepowered.asm.mixin.Mixin;

/**
 * This mixin is required for compatibility with NeoForge, which sends the chunk packet inside a bundle packet:
 * <a href="https://github.com/neoforged/NeoForge/blob/afca8c6ae65d17970327945e792cd848214cfd07/src/main/java/net/neoforged/neoforge/common/world/LevelChunkAuxiliaryLightManager.java#L86">LevelChunkAuxiliaryLightManager</a>
 */
@Mixin(ClientboundBundlePacket.class)
public abstract class ClientboundBundlePacketMixin extends BundlePacket<ClientGamePacketListener> implements IPacket {
    protected ClientboundBundlePacketMixin(Iterable<Packet<? super ClientGamePacketListener>> iterable) {
        super(iterable);
    }

    @Override
    public boolean isAntixray$ready() {
        boolean isReady = true;
        for (Packet<? super ClientGamePacketListener> subPacket : this.subPackets()) {
            isReady &= Util.isReady(subPacket);
        }
        return isReady;
    }
}
