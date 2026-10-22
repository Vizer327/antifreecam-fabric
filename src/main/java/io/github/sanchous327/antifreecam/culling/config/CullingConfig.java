package io.github.sanchous327.antifreecam.culling.config;

import io.github.sanchous327.antifreecam.AntiFreecam;
import io.github.sanchous327.antifreecam.config.AntiFreecamConfig;
import io.github.sanchous327.antifreecam.culling.util.WaypointMode;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings of player and entity culling ([players], [entities], [scheduler] of antifreecam.toml).
 */
@NullMarked
public final class CullingConfig {

    public Scheduler scheduler = new Scheduler();
    public Culling culling = new Culling();
    public WaypointMode waypointMode = WaypointMode.VANILLA;

    public static CullingConfig from(AntiFreecamConfig file) {
        CullingConfig config = new CullingConfig();
        AntiFreecamConfig.Section players = file.section("players");
        AntiFreecamConfig.Section entities = file.section("entities");
        AntiFreecamConfig.Section scheduler = file.section("scheduler");

        Culling culling = config.culling;
        culling.playersEnabled = players.getBoolean("enabled", culling.playersEnabled);
        culling.nametagVisibilityDistance = players.getDouble("nametag-visibility-distance", culling.nametagVisibilityDistance);
        culling.exemptTag = players.getString("exempt-tag", culling.exemptTag);
        culling.blacklistedWorlds = Set.copyOf(players.getStringList("disabled-worlds", List.of()));
        String waypoints = players.getString("waypoint-mode", config.waypointMode.name());
        try {
            config.waypointMode = WaypointMode.valueOf(waypoints.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            AntiFreecam.LOGGER.warn("Unknown waypoint-mode \"{}\" - using VANILLA", waypoints);
        }

        culling.entityMode = entities.getString("mode", culling.entityMode).toLowerCase(Locale.ROOT);
        culling.entityCullDistance = entities.getDouble("cull-distance", culling.entityCullDistance);
        culling.entityAlwaysVisibleDistance = entities.getDouble("always-visible-distance", culling.entityAlwaysVisibleDistance);
        culling.neverHideTagged = entities.getBoolean("never-hide-tagged", culling.neverHideTagged);
        culling.ignoredTags = Set.copyOf(entities.getStringList("ignored-tags", List.of()));

        Scheduler sched = config.scheduler;
        int threads = scheduler.getInt("max-threads", 0);
        sched.maxThreads = threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
        sched.cleanupInterval = scheduler.getInt("cleanup-interval", sched.cleanupInterval);
        sched.containerTtl = scheduler.getInt("container-ttl", sched.containerTtl);
        sched.maxCullTime = scheduler.getInt("max-cull-time", sched.maxCullTime);
        sched.maxTransferFactor = scheduler.getDouble("max-transfer-factor", sched.maxTransferFactor);
        sched.maxMergeFactor = scheduler.getDouble("max-merge-factor", sched.maxMergeFactor);
        return config;
    }

    public static final class Scheduler {

        public int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
        public int cleanupInterval = 30; // sec
        public int containerTtl = 30; // sec
        public int maxCullTime = 45; // ms
        public double maxTransferFactor = 0.7;
        public double maxMergeFactor = 0.5;

        public long getMaxCullTimeNs() {
            return this.maxCullTime * 1_000_000L;
        }

        public long getCleanupIntervalMs() {
            return this.cleanupInterval * 1_000L;
        }

        public long getContainerTtlMs() {
            return this.containerTtl * 1_000L;
        }

        public double getMaxTransferNs() {
            return this.maxTransferFactor * this.getMaxCullTimeNs();
        }

        public double getMaxMergeNs() {
            return this.maxMergeFactor * this.getMaxCullTimeNs();
        }
    }

    public static final class Culling {

        public boolean playersEnabled = true;
        public double nametagVisibilityDistance = 64; // blocks
        public String exemptTag = "";
        public Set<String> blacklistedWorlds = Set.of();
        // non-player entities: "whitelist" - every entity except technical ones; "blacklist" - only the listed
        // kinds (monsters, villagers, animals, ...); anything else - off
        public String entityMode = "whitelist";
        public double entityCullDistance = 160; // blocks - entities farther from every player are not checked
        public double entityAlwaysVisibleDistance = 6; // blocks
        public boolean neverHideTagged = false;
        public Set<String> ignoredTags = Set.of();

        public double getNametagVisibilityDistanceSquared() {
            return this.nametagVisibilityDistance * this.nametagVisibilityDistance;
        }
    }
}
