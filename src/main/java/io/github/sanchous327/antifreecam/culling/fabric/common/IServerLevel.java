package io.github.sanchous327.antifreecam.culling.fabric.common;
// Created by booky10 in PlayerCulling (18:06 14.07.2025)

import io.github.sanchous327.antifreecam.culling.fabric.platform.FabricWorld;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface IServerLevel {

    @Nullable FabricWorld getCullWorld();

    FabricWorld getCullWorldOrCreate();
}
