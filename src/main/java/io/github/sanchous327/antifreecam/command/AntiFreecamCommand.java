package io.github.sanchous327.antifreecam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.sanchous327.antifreecam.AntiFreecam;
import io.github.sanchous327.antifreecam.blocks.util.BlockReveal;
import io.github.sanchous327.antifreecam.blocks.util.Util;
import io.github.sanchous327.antifreecam.blocks.util.controller.ChunkPacketBlockControllerAntiXray;
import io.github.sanchous327.antifreecam.culling.CullPlayer;
import io.github.sanchous327.antifreecam.culling.CullShip;
import io.github.sanchous327.antifreecam.culling.fabric.common.IServerLevel;
import io.github.sanchous327.antifreecam.culling.fabric.platform.FabricWorld;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.permissions.PermissionLevel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /antifreecam reload | status | hidden <player> | resend [player] | pocket [x y z]
 */
public final class AntiFreecamCommand {

    private AntiFreecamCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AntiFreecam mod) {
        dispatcher.register(Commands.literal("antifreecam")
                .requires(Permissions.require("antifreecam.command", PermissionLevel.GAMEMASTERS))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx, mod)))
                .then(Commands.literal("status").executes(ctx -> status(ctx, mod)))
                .then(Commands.literal("resend")
                        .executes(ctx -> resend(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> resend(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("pocket")
                        .executes(ctx -> pocket(ctx, ctx.getSource().getLevel(), ctx.getSource().getPlayerOrException().blockPosition()))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> pocket(ctx, ctx.getSource().getLevel(), BlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(Commands.literal("trace").executes(ctx -> {
                    boolean on = BlockReveal.toggleTrace(ctx.getSource().getPlayerOrException());
                    ctx.getSource().sendSuccess(() -> Component.literal(on
                            ? "Trace on: every replaced / revealed position of yours is recorded (see /antifreecam block). Resend your chunks: /antifreecam resend"
                            : "Trace off"), false);
                    return on ? 1 : 0;
                }))
                .then(Commands.literal("block")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> block(ctx, ctx.getSource().getPlayerOrException(), BlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(Commands.literal("hidden")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> hidden(ctx, mod, EntityArgument.getPlayer(ctx, "player"))))));
    }

    private static int reload(CommandContext<CommandSourceStack> ctx, AntiFreecam mod) {
        try {
            mod.reload();
        } catch (IOException exception) {
            ctx.getSource().sendFailure(Component.literal("Config not reloaded: " + exception.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("AntiFreecam config reloaded. ")
                .append(Component.literal("[blocks], [ore-engine] and [worlds] apply after a restart.")
                        .withStyle(ChatFormatting.GRAY)), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx, AntiFreecam mod) {
        CullShip ship = mod.getCullShip();
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("AntiFreecam").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" - preset " + mod.getConfigFile().getPreset()).withStyle(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> Component.literal(String.format("Culling threads: %d, slowest pass: %.2f ms",
                ship.getContainers().size(), ship.getLongestCullTime() / 1_000_000d)), false);
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            CullPlayer cullPlayer = ship.getPlayer(player.getUUID());
            int players = cullPlayer != null ? cullPlayer.getHiddenCount() : 0;
            int entities = cullPlayer != null ? cullPlayer.getHiddenEntityCount() : 0;
            int blocks = BlockReveal.hiddenCount(player.getUUID());
            FabricWorld world = ((IServerLevel) player.level()).getCullWorld();
            int candidates = world != null ? world.getCulledEntities().size() : 0;
            String seesAll = cullPlayer != null ? cullPlayer.getSeesAllReason() : "not tracked yet";
            source.sendSuccess(() -> Component.literal(String.format(" %s: hidden %d players, %d of %d entities, %d blocks",
                    player.getScoreboardName(), players, entities, candidates, blocks)).withStyle(ChatFormatting.GRAY)
                    .append(seesAll == null ? Component.empty()
                            : Component.literal(" - sees everything: " + seesAll).withStyle(ChatFormatting.YELLOW)), false);
        }
        return 1;
    }

    // Sends every chunk the player has loaded again - to see the effect of new block settings without relogging
    private static int resend(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        int structures = BlockReveal.forgetStructures(player);
        int count = BlockReveal.resendAll(player);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("Sending %d chunks to %s again (structures he had opened, filled again: %d)",
                count, player.getScoreboardName(), structures)), false);
        return count;
    }

    // Is the air pocket at this position (or where the player stands) filled for other players, and if not - why
    private static int pocket(CommandContext<CommandSourceStack> ctx, ServerLevel level, BlockPos pos) {
        if (!(Util.getBlockController(level) instanceof ChunkPacketBlockControllerAntiXray controller) || !controller.rtEnabled()) {
            ctx.getSource().sendFailure(Component.literal("Block hiding is off in this world"));
            return 0;
        }
        String probe = controller.rtProbe(level, pos);
        String result = probe.startsWith("OPEN") && controller.rtFillBuildsOn()
                ? probe + " - but fill-builds still fills the parts of it inside buildings" : probe;
        ctx.getSource().sendSuccess(() -> Component.literal("Pocket: " + result), false);
        return result.startsWith("CLOSED") ? 1 : 0;
    }

    // What the mod knows about one position for you - real block, replaced or not, seen by a ray (phantom blocks)
    private static int block(CommandContext<CommandSourceStack> ctx, ServerPlayer player, BlockPos pos) {
        String result = BlockReveal.describe(player, pos);
        ctx.getSource().sendSuccess(() -> Component.literal(pos.toShortString() + ": " + result), false);
        return 1;
    }

    private static int hidden(CommandContext<CommandSourceStack> ctx, AntiFreecam mod, ServerPlayer player) throws CommandSyntaxException {
        CullPlayer cullPlayer = mod.getCullShip().getPlayer(player.getUUID());
        if (cullPlayer == null) {
            ctx.getSource().sendFailure(Component.literal(player.getScoreboardName() + " is not tracked yet"));
            return 0;
        }
        List<String> names = new ArrayList<>();
        for (UUID id : cullPlayer.getHidden()) {
            ServerPlayer hidden = ctx.getSource().getServer().getPlayerList().getPlayer(id);
            names.add(hidden != null ? hidden.getScoreboardName() : id.toString());
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("Hidden from %s: %s",
                player.getScoreboardName(), names.isEmpty() ? "nobody" : String.join(", ", names))), false);
        return names.size();
    }
}
