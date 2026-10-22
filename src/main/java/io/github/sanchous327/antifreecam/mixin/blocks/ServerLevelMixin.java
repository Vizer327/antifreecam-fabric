package io.github.sanchous327.antifreecam.mixin.blocks;

import io.github.sanchous327.antifreecam.blocks.interfaces.ILevel;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.Executor;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin extends Level implements ILevel {
    protected ServerLevelMixin(
        WritableLevelData writableLevelData, ResourceKey<Level> resourceKey, RegistryAccess registryAccess,
        Holder<DimensionType> holder, boolean bl, boolean bl2, long l, int i
    ) {
        super(writableLevelData, resourceKey, registryAccess, holder, bl, bl2, l, i);
    }

    @Inject(
        method = "<init>",
        at = @At("TAIL")
    )
    public void onInit(
        MinecraftServer minecraftServer, Executor executor, LevelStorageSource.LevelStorageAccess levelStorageAccess,
        ServerLevelData serverLevelData, ResourceKey resourceKey, LevelStem levelStem, boolean bl, long l, List list,
        boolean bl2, CallbackInfo ci
    ) {
        initValues();
    }

}
