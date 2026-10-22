package io.github.sanchous327.antifreecam.culling.platform.world;

import io.github.sanchous327.antifreecam.culling.vector.AABB;
import org.jspecify.annotations.NullMarked;

import java.util.UUID;

/**
 * A non-player entity that may be hidden from players who can't see it (villagers, monsters,
 * animals, item frames, ...). Snapshot taken on the server thread, read by the culling threads.
 */
@NullMarked
public record CulledEntity(UUID uuid, int id, AABB box) {
}
