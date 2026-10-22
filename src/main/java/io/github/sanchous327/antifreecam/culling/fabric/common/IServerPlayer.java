package io.github.sanchous327.antifreecam.culling.fabric.common;
// Created by booky10 in PlayerCulling (18:15 14.07.2025)

import io.github.sanchous327.antifreecam.culling.fabric.platform.FabricPlayer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface IServerPlayer {

    @Nullable FabricPlayer getCullPlayer();

    FabricPlayer getCullPlayerOrCreate();
}
