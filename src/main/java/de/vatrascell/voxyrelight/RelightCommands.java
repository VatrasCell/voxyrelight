package de.vatrascell.voxyrelight;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * {@code /voxyrelight scan <radius>|all}, {@code /voxyrelight fix <radius>|all [force]},
 * {@code /voxyrelight cancel}, {@code /voxyrelight status}. The radius is counted in LOD 0 sections (32 blocks).
 */
final class RelightCommands {
    private static final int MAX_RADIUS = 1024;
    private static final String PREFIX = "[VoxyRelight] ";

    private RelightCommands() {}

    static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommands.literal("voxyrelight")
                .then(ClientCommands.literal("scan")
                        .then(ClientCommands.literal("all")
                                .executes(ctx -> start(ctx, true, false, -1)))
                        .then(ClientCommands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                                .executes(ctx -> start(ctx, true, false, radius(ctx)))))
                .then(ClientCommands.literal("fix")
                        .then(ClientCommands.literal("all")
                                .executes(ctx -> start(ctx, false, false, -1))
                                .then(ClientCommands.literal("force")
                                        .executes(ctx -> start(ctx, false, true, -1))))
                        .then(ClientCommands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                                .executes(ctx -> start(ctx, false, false, radius(ctx)))
                                .then(ClientCommands.literal("force")
                                        .executes(ctx -> start(ctx, false, true, radius(ctx))))))
                .then(ClientCommands.literal("cancel")
                        .executes(RelightCommands::cancel))
                .then(ClientCommands.literal("status")
                        .executes(RelightCommands::status));
    }

    private static int radius(CommandContext<FabricClientCommandSource> ctx) {
        return IntegerArgumentType.getInteger(ctx, "radius");
    }

    private static int start(CommandContext<FabricClientCommandSource> ctx, boolean dryRun, boolean force, int radius) {
        var source = ctx.getSource();
        if (RelightManager.isRunning()) {
            source.sendError(Component.literal(PREFIX + "A job is already running: " + RelightManager.currentDescription()
                    + ". Use /voxyrelight cancel to stop it."));
            return 0;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            source.sendError(Component.literal(PREFIX + "Voxy is not active (enable it in the settings)."));
            return 0;
        }
        var level = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (level == null || player == null) {
            source.sendError(Component.literal(PREFIX + "No world loaded."));
            return 0;
        }
        var engine = WorldIdentifier.ofEngine(level);
        if (engine == null) {
            source.sendError(Component.literal(PREFIX + "No Voxy world found for the current dimension."));
            return 0;
        }

        RelightJob.Area area = null;
        String scope = "entire dimension " + level.dimension().identifier();
        if (radius >= 0) {
            var pos = player.blockPosition();
            // LOD 0 sections are 32 blocks wide; min/max come from the world limits of the dimension
            area = new RelightJob.Area(pos.getX() >> 5, pos.getZ() >> 5, radius,
                    level.getMinY() >> 5, level.getMaxY() >> 5);
            scope = "radius " + radius + " sections around " + (pos.getX() >> 5) + "/" + (pos.getZ() >> 5);
        }
        String description = (dryRun ? "Scan" : force ? "Repair (force)" : "Repair") + " (" + scope + ")";

        var job = new RelightJob(instance, engine, area, dryRun, force, description, RelightCommands::chat);
        if (!RelightManager.start(job)) {
            source.sendError(Component.literal(PREFIX + "A job is already running."));
            return 0;
        }
        source.sendFeedback(Component.literal(PREFIX + description + " started."
                + (dryRun ? "" : " Back up the .voxy folder first!")));
        return 1;
    }

    private static int cancel(CommandContext<FabricClientCommandSource> ctx) {
        if (RelightManager.cancel()) {
            ctx.getSource().sendFeedback(Component.literal(PREFIX + "Cancellation requested."));
            return 1;
        }
        ctx.getSource().sendError(Component.literal(PREFIX + "No job is running."));
        return 0;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        String running = RelightManager.currentDescription();
        ctx.getSource().sendFeedback(Component.literal(PREFIX + (running == null ? "No job active." : "Active: " + running)));
        return 1;
    }

    /** Thread-safe chat output from the worker. */
    private static void chat(String message) {
        var minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal(PREFIX + message).withStyle(ChatFormatting.GRAY));
            }
        });
    }
}
