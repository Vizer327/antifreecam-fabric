package io.github.sanchous327.antifreecam.culling.cache;


import io.github.sanchous327.antifreecam.culling.platform.world.PlatformWorld;

public interface DataProvider {

    void world(PlatformWorld world);

    boolean isOpaqueFullCube(int x, int y, int z);

    int getPlayerViewDistance();
}
