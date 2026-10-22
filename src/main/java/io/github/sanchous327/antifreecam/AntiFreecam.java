package io.github.sanchous327.antifreecam;

import io.github.sanchous327.antifreecam.command.AntiFreecamCommand;
import io.github.sanchous327.antifreecam.config.AntiFreecamConfig;
import io.github.sanchous327.antifreecam.culling.CullShip;
import io.github.sanchous327.antifreecam.culling.config.CullingConfig;
import io.github.sanchous327.antifreecam.culling.config.CullingConfigHolder;
import io.github.sanchous327.antifreecam.culling.fabric.CullingListener;
import io.github.sanchous327.antifreecam.culling.fabric.platform.FabricPlatform;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * AntiFreecam Fabric: players and entities a player can't see are not sent to him (occlusion culling, from
 * PlayerCulling), blocks he can't see are replaced in the chunk packets until he does (from AntiXray).
 */
public class AntiFreecam implements ModInitializer {

    public static final String MOD_ID = "antifreecam";
    public static final String BYPASS_PERMISSION = "antifreecam.bypass";
    public static final Logger LOGGER = LoggerFactory.getLogger("AntiFreecam");

    private static AntiFreecam INSTANCE; // Fabric entrypoints are created by the loader - static access it is

    private final AntiFreecamConfig configFile;
    private final CullingConfigHolder cullingConfig;
    private final FabricPlatform platform;
    private CullShip cullShip;

    public AntiFreecam() {
        INSTANCE = this;
        this.configFile = new AntiFreecamConfig(FabricLoader.getInstance().getConfigDir());
        try {
            this.configFile.load();
        } catch (IOException exception) {
            throw new IllegalStateException("AntiFreecam can't read its config - fix or delete " + this.configFile.getPath(), exception);
        }
        this.cullingConfig = new CullingConfigHolder(CullingConfig.from(this.configFile));
        this.platform = new FabricPlatform(this);
    }

    public static AntiFreecam getInstance() {
        return INSTANCE;
    }

    @Override
    public void onInitialize() {
        this.cullShip = new CullShip(this.platform, this.cullingConfig);
        this.cullShip.enable();
        new CullingListener(this).register();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                AntiFreecamCommand.register(dispatcher, this));
        LOGGER.info("AntiFreecam enabled");
    }

    /**
     * Re-reads the config file. Player/entity settings apply at once, block settings on the next world load.
     */
    public void reload() throws IOException {
        this.configFile.load();
        this.cullingConfig.set(CullingConfig.from(this.configFile));
    }

    public static boolean hasBypassPermission(ServerPlayer player) {
        return Permissions.check(player, BYPASS_PERMISSION, false);
    }

    public AntiFreecamConfig getConfigFile() {
        return this.configFile;
    }

    public FabricPlatform getPlatform() {
        return this.platform;
    }

    public CullShip getCullShip() {
        return this.cullShip;
    }
}
