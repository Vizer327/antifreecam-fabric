package io.github.sanchous327.antifreecam.config;

import com.moandjiezana.toml.Toml;
import io.github.sanchous327.antifreecam.AntiFreecam;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single config file, config/antifreecam.toml. Created from the bundled default on first start; never written
 * back, so the comments in it stay. Values are layered: presets/base.toml, then the chosen preset
 * (low / medium / high), then whatever the file sets itself. Missing keys fall back to the defaults in code.
 */
public final class AntiFreecamConfig {

    public static final String FILE_NAME = "antifreecam.toml";
    private static final String DEFAULT_RESOURCE = "/antifreecam-default.toml";
    private static final String PRESET_DIR = "/antifreecam/presets/";
    public static final List<String> PRESETS = List.of("low", "medium", "high");

    private final Path path;
    private Map<String, Object> root = Map.of();
    private String preset = "medium";

    public AntiFreecamConfig(Path configDir) {
        this.path = configDir.resolve(FILE_NAME);
    }

    public Path getPath() {
        return this.path;
    }

    /**
     * Reads the file. On a broken file the previous values stay and the error is thrown to the caller.
     */
    public void load() throws IOException {
        if (Files.notExists(this.path)) {
            Files.createDirectories(this.path.getParent());
            try (InputStream in = Objects.requireNonNull(AntiFreecamConfig.class.getResourceAsStream(DEFAULT_RESOURCE))) {
                Files.copy(in, this.path);
            }
            AntiFreecam.LOGGER.info("Created default config {}", this.path);
        }
        Map<String, Object> file;
        try (InputStream in = Files.newInputStream(this.path)) {
            file = normalize(new Toml().read(in).toMap());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid TOML in " + this.path + ": " + exception.getMessage(), exception);
        }
        String preset = String.valueOf(file.getOrDefault("preset", "medium")).toLowerCase(java.util.Locale.ROOT);
        if (!PRESETS.contains(preset)) {
            AntiFreecam.LOGGER.warn("Unknown preset \"{}\" - using \"medium\" (low, medium, high)", preset);
            preset = "medium";
        }
        Map<String, Object> merged = merge(readResource(PRESET_DIR + "base.toml"), readResource(PRESET_DIR + preset + ".toml"));
        this.root = merge(merged, file);
        this.preset = preset;
    }

    public String getPreset() {
        return this.preset;
    }

    private static Map<String, Object> readResource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(AntiFreecamConfig.class.getResourceAsStream(name), name)) {
            return normalize(new Toml().read(in).toMap());
        }
    }

    // Tables are merged key by key, everything else (values, lists) of "top" replaces "bottom"
    private static Map<String, Object> merge(Map<String, Object> bottom, Map<String, Object> top) {
        Map<String, Object> result = new HashMap<>(bottom);
        for (Map.Entry<String, Object> entry : top.entrySet()) {
            Object old = result.get(entry.getKey());
            if (old instanceof Map<?, ?> oldMap && entry.getValue() instanceof Map<?, ?> newMap) {
                result.put(entry.getKey(), merge(castMap(oldMap), castMap(newMap)));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    // toml4j keeps the quotes of quoted keys ("minecraft:the_nether") - drop them everywhere
    private static Map<String, Object> normalize(Map<String, Object> map) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            result.put(unquote(entry.getKey()), value instanceof Map<?, ?> table ? normalize(castMap(table)) : value);
        }
        return result;
    }

    public Section section(String name) {
        return new Section(table(this.root, name));
    }

    /**
     * A section ("blocks", "ore-engine") for one world: the global section with the keys of
     * [worlds."namespace:path".section] on top. The plain path ("the_nether") is accepted as the world name too.
     */
    public Section worldSection(String worldId, String name) {
        Map<String, Object> merged = new HashMap<>(table(this.root, name));
        Map<String, Object> worlds = table(this.root, "worlds");
        String path = worldId.substring(worldId.indexOf(':') + 1);
        for (Map.Entry<String, Object> entry : worlds.entrySet()) {
            String key = unquote(entry.getKey());
            if ((key.equals(worldId) || key.equals(path)) && entry.getValue() instanceof Map<?, ?> world) {
                merged.putAll(table(castMap(world), name));
            }
        }
        return new Section(merged);
    }

    private static Map<String, Object> table(Map<String, Object> parent, String name) {
        for (Map.Entry<String, Object> entry : parent.entrySet()) {
            if (unquote(entry.getKey()).equals(name) && entry.getValue() instanceof Map<?, ?> map) {
                return castMap(map);
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String unquote(String key) {
        if (key.length() >= 2 && (key.startsWith("\"") && key.endsWith("\"") || key.startsWith("'") && key.endsWith("'"))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    /**
     * Typed read access to one table. Wrong types are reported and replaced by the default.
     */
    public static final class Section {

        private final Map<String, Object> values;

        private Section(Map<String, Object> values) {
            this.values = values;
        }

        private @Nullable Object raw(String key) {
            for (Map.Entry<String, Object> entry : this.values.entrySet()) {
                if (unquote(entry.getKey()).equals(key)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        public boolean has(String key) {
            return this.raw(key) != null;
        }

        public boolean getBoolean(String key, boolean def) {
            Object value = this.raw(key);
            if (value == null) return def;
            if (value instanceof Boolean bool) return bool;
            return wrongType(key, "true/false", def);
        }

        public long getLong(String key, long def) {
            Object value = this.raw(key);
            if (value == null) return def;
            if (value instanceof Number number) return number.longValue();
            return wrongType(key, "a whole number", def);
        }

        public int getInt(String key, int def) {
            return Math.toIntExact(this.getLong(key, def));
        }

        public double getDouble(String key, double def) {
            Object value = this.raw(key);
            if (value == null) return def;
            if (value instanceof Number number) return number.doubleValue();
            return wrongType(key, "a number", def);
        }

        public String getString(String key, String def) {
            Object value = this.raw(key);
            if (value == null) return def;
            if (value instanceof String string) return string;
            return wrongType(key, "a string in quotes", def);
        }

        public List<String> getStringList(String key, List<String> def) {
            Object value = this.raw(key);
            if (value == null) return def;
            if (value instanceof List<?> list) {
                List<String> result = new ArrayList<>(list.size());
                for (Object element : list) {
                    result.add(String.valueOf(element));
                }
                return Collections.unmodifiableList(result);
            }
            return wrongType(key, "a list [\"...\"]", def);
        }

        private static <T> T wrongType(String key, String expected, T def) {
            AntiFreecam.LOGGER.warn("Config key \"{}\" must be {} - using the default {}", key, expected, def);
            return def;
        }
    }
}
