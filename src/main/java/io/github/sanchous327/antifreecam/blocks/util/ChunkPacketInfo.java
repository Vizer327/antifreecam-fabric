package io.github.sanchous327.antifreecam.blocks.util;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.Palette;

public class ChunkPacketInfo<T> {

    private final LevelChunk chunk;
    private final int[] bits;
    private final Object[] palettes;
    private final int[] indexes;
    private final Object[][] presetValues;
    private ClientboundLevelChunkWithLightPacket chunkPacket;
    private byte[] buffer;
    // the player this packet is built for and his eye position at that moment (null viewer - unknown, e.g. sent by another mod)
    private java.util.UUID rtViewer;
    private double rtEyeX, rtEyeY, rtEyeZ;

    public void rtSetViewer(java.util.UUID viewer, double eyeX, double eyeY, double eyeZ) {
        this.rtViewer = viewer;
        this.rtEyeX = eyeX;
        this.rtEyeY = eyeY;
        this.rtEyeZ = eyeZ;
    }

    public java.util.UUID rtViewer() {
        return rtViewer;
    }

    public double rtEyeX() {
        return rtEyeX;
    }

    public double rtEyeY() {
        return rtEyeY;
    }

    public double rtEyeZ() {
        return rtEyeZ;
    }

    public ChunkPacketInfo(LevelChunk chunk) {
        this.chunk = chunk;
        int sections = chunk.getSectionsCount();
        bits = new int[sections];
        palettes = new Object[sections];
        indexes = new int[sections];
        presetValues = new Object[sections][];
    }

    public void setChunkPacket(ClientboundLevelChunkWithLightPacket chunkPacket) {
        this.chunkPacket = chunkPacket;
    }

    public ClientboundLevelChunkWithLightPacket getChunkPacket() {
        return chunkPacket;
    }

    public LevelChunk getChunk() {
        return chunk;
    }

    public byte[] getBuffer() {
        return buffer;
    }

    public void setBuffer(byte[] buffer) {
        this.buffer = buffer;
    }

    public int getBits(int chunkSectionIndex) {
        return bits[chunkSectionIndex];
    }

    public void setBits(int chunkSectionIndex, int bits) {
        this.bits[chunkSectionIndex] = bits;
    }

    @SuppressWarnings("unchecked")
    public Palette<T> getPalette(int chunkSectionIndex) {
        return (Palette<T>) palettes[chunkSectionIndex];
    }

    public void setPalette(int chunkSectionIndex, Palette<T> palette) {
        palettes[chunkSectionIndex] = palette;
    }

    public int getIndex(int chunkSectionIndex) {
        return indexes[chunkSectionIndex];
    }

    public void setIndex(int chunkSectionIndex, int index) {
        indexes[chunkSectionIndex] = index;
    }

    @SuppressWarnings("unchecked")
    public T[] getPresetValues(int chunkSectionIndex) {
        return (T[]) presetValues[chunkSectionIndex];
    }

    public void setPresetValues(int chunkSectionIndex, T[] presetValues) {
        this.presetValues[chunkSectionIndex] = presetValues;
    }

    public boolean isWritten(int chunkSectionIndex) {
        return bits[chunkSectionIndex] != 0;
    }
}
