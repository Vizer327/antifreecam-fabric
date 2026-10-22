package io.github.sanchous327.antifreecam.culling.platform;

import io.github.sanchous327.antifreecam.culling.platform.entity.PlatformPlayer;
import io.github.sanchous327.antifreecam.culling.platform.world.PlatformWorld;
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NullMarked;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@NullMarked
public interface IPlatform {

    void tick();

    long getCurrentTick();

    @Unmodifiable
    Collection<PlatformWorld> getWorlds();

    PlatformPlayer getPlayer(UUID playerId);

    @Unmodifiable
    Set<? extends PlatformPlayer> getPlayers();

    int runTaskLaterAsync(Runnable runnable, long delay);

    int runTaskRepeatingAsync(Runnable runnable, long delay, long period);

    void cancelTask(int taskId);
}
