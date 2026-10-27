package io.github.sanchous327.antifreecam.blocks.config;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.sanchous327.antifreecam.AntiFreecam;
import io.github.sanchous327.antifreecam.blocks.util.EngineMode;
import io.github.sanchous327.antifreecam.blocks.util.controller.*;
import io.github.sanchous327.antifreecam.config.AntiFreecamConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Block settings of one world: [blocks] and [ore-engine] of antifreecam.toml with [worlds."id".*] on top.
 */
public class WorldConfig {

    // not in [players] disabled-worlds
    public boolean worldEnabled = true;
    // [blocks] - raytraced hiding
    public boolean enabled = true;
    // "blacklist" - hide hiddenBlocks; "whitelist" - hide everything except visibleBlocks
    public String mode = "whitelist";
    // "everywhere"; "underground" - only below the highest coverBlocks block of the column
    public String scope = "underground";
    public boolean fillPockets = false;
    public boolean fillEmptyPockets = false;
    public boolean fillBuilds = false;
    public int fillMaxPocket = 2048;
    // structures whose pieces are filled whole until seen (ids like "minecraft:ancient_city"); empty = off
    public List<String> fillStructures = List.of();
    public int nearRadius = 16;
    public int revealDistance = 48;
    public Set<Block> hiddenBlocks = new HashSet<>();
    public Set<Block> visibleBlocks = new HashSet<>();
    public Set<Block> coverBlocks = new HashSet<>();

    // [ore-engine] - Paper's Anti-Xray (fake ores)
    public boolean oreEngine = false;
    public EngineMode engineMode = EngineMode.HIDE;
    public int maxBlockHeight = 64;
    public int updateRadius = 2;
    public boolean lavaObscures = false;
    public Set<Block> oreHiddenBlocks = new HashSet<>();
    public Set<Block> replacementBlocks = new HashSet<>();

    public WorldConfig(Identifier location) {
        AntiFreecamConfig file = AntiFreecam.getInstance().getConfigFile();
        String worldId = location.toString();
        AntiFreecamConfig.Section blocks = file.worldSection(worldId, "blocks");
        AntiFreecamConfig.Section engine = file.worldSection(worldId, "ore-engine");

        this.worldEnabled = !file.section("players").getStringList("disabled-worlds", List.of()).contains(worldId);
        this.enabled = blocks.getBoolean("enabled", this.enabled);
        this.mode = blocks.getString("mode", this.mode).toLowerCase(Locale.ROOT);
        this.scope = blocks.getString("scope", this.scope).toLowerCase(Locale.ROOT);
        this.fillPockets = blocks.getBoolean("fill-pockets", this.fillPockets);
        this.fillEmptyPockets = blocks.getBoolean("fill-empty-pockets", this.fillEmptyPockets);
        this.fillBuilds = blocks.getBoolean("fill-builds", this.fillBuilds);
        this.fillMaxPocket = blocks.getInt("fill-max-pocket", this.fillMaxPocket);
        this.fillStructures = blocks.getStringList("fill-structures", this.fillStructures);
        this.nearRadius = blocks.getInt("near-radius", this.nearRadius);
        this.revealDistance = blocks.getInt("reveal-distance", this.revealDistance);
        this.hiddenBlocks = parseBlocks(blocks.getStringList("hidden", List.of()));
        this.visibleBlocks = parseBlocks(blocks.getStringList("visible", List.of()));
        this.coverBlocks = parseBlocks(blocks.getStringList("cover", List.of()));

        this.oreEngine = engine.getBoolean("enabled", this.oreEngine);
        EngineMode engineMode = EngineMode.getById(engine.getInt("engine-mode", this.engineMode.getId()));
        if (engineMode != null) {
            this.engineMode = engineMode;
        }
        this.maxBlockHeight = engine.getInt("max-block-height", this.maxBlockHeight) >> 4 << 4;
        this.updateRadius = engine.getInt("update-radius", this.updateRadius);
        this.lavaObscures = engine.getBoolean("lava-obscures", this.lavaObscures);
        this.oreHiddenBlocks = parseBlocks(engine.getStringList("hidden-blocks", List.of()));
        this.replacementBlocks = parseBlocks(engine.getStringList("replacement-blocks", List.of()));
    }

    private Set<Block> parseBlocks(List<String> blocks) {
        Set<Block> result = new HashSet<>();
        for (String blockId : blocks) {
            try {
                StringReader stringReader = new StringReader(blockId);
                boolean isTag = false;
                Consumer<Block> blockConsumer = result::add;
                if (stringReader.canRead() && stringReader.peek() == '!') {
                    blockConsumer = result::remove;
                    stringReader.skip();
                }
                if (stringReader.canRead() && stringReader.peek() == '#') {
                    isTag = true;
                    stringReader.skip();
                }
                Identifier location = Identifier.read(stringReader);
                if (isTag) {
                    TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, location);
                    Optional<HolderSet.Named<Block>> optional = BuiltInRegistries.BLOCK.get(tagKey);
                    if (optional.isPresent()) {
                        for (Holder<Block> holder : optional.get()) {
                            blockConsumer.accept(holder.value());
                        }
                    } else {
                        AntiFreecam.LOGGER.warn("Config: no block tag \"{}\" - skipped", blockId);
                    }
                } else {
                    Optional<Block> optional = BuiltInRegistries.BLOCK.getOptional(location);
                    optional.ifPresentOrElse(
                        blockConsumer,
                        () -> AntiFreecam.LOGGER.warn("Config: no block \"{}\" - skipped", blockId)
                    );
                }
            } catch (CommandSyntaxException exception) {
                AntiFreecam.LOGGER.warn("Config: \"{}\" is not a block id - skipped", blockId);
            }
        }
        return result;
    }

    public ChunkPacketBlockController createChunkPacketBlockController(Level level) {
        if (!this.worldEnabled || !this.enabled && !this.oreEngine) {
            return DisabledChunkPacketBlockController.NO_OPERATION_INSTANCE;
        }
        ChunkPacketBlockControllerAntiXray controller;
        if (this.oreEngine) {
            controller = switch (engineMode) {
                case HIDE ->
                    new HideChunkPacketBlockController(level, oreHiddenBlocks, maxBlockHeight, updateRadius, lavaObscures);
                case OBFUSCATE ->
                    new ObfuscateChunkPacketBlockController(level, replacementBlocks, oreHiddenBlocks, maxBlockHeight, updateRadius, lavaObscures);
                case OBFUSCATE_LAYER ->
                    new ObfuscateLayerChunkPacketBlockController(level, replacementBlocks, oreHiddenBlocks, maxBlockHeight, updateRadius, lavaObscures);
            };
        } else {
            // no ore engine: nothing to obfuscate and a max height below the world - only the raytraced pass runs
            controller = new HideChunkPacketBlockController(level, Set.of(), -4096, 0, lavaObscures);
        }
        if (this.enabled) {
            controller.rtConfigure(this.mode, this.scope, this.hiddenBlocks, this.visibleBlocks, this.coverBlocks,
                this.nearRadius, this.revealDistance);
            controller.rtConfigureFill(this.fillPockets, this.fillEmptyPockets, this.fillBuilds, this.fillMaxPocket);
            controller.rtConfigureStructures(new HashSet<>(this.fillStructures));
        }
        return controller;
    }

}
