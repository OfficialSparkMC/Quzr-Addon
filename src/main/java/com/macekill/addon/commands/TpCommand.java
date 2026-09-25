package com.macekill.addon.commands;

import com.macekill.addon.modules.PlayerTp;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.arguments.PlayerArgumentType;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * .tp &lt;player&gt; - teleport to a named player via stepped position packets.
 * Uses Meteor's PlayerArgumentType (name completion included). Works standalone,
 * the PlayerTp module does not need to be enabled. Runs on the client thread,
 * same as Meteor's own VClipCommand.
 */
public class TpCommand extends Command {
    public TpCommand() {
        super("tp", "Teleport to a player by name (Qazr).", "qzrtp");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(argument("player", PlayerArgumentType.create()).executes(context -> {
            if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
                error("Not in game.");
                return 0;
            }
            PlayerEntity target = PlayerArgumentType.get(context);
            if (target == null) {
                error("Player not found.");
                return 0;
            }
            if (target == mc.player) {
                error("That's you.");
                return 0;
            }
            double dist = mc.player.distanceTo(target);
            PlayerTp.teleportTo(new Vec3d(target.getX(), target.getY(), target.getZ()));
            info("Teleported to %s (%.1fm)", target.getName().getString(), dist);
            return SINGLE_SUCCESS;
        }));
    }
}
