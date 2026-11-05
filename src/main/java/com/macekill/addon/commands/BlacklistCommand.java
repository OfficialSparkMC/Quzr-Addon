package com.macekill.addon.commands;

import com.macekill.addon.modules.Blacklist;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * .blacklist — manage the Blacklist auto-smash list.
 *
 * <p>.blacklist add &lt;player&gt; — add a player by name (offline names accepted,
 * online names tab-complete).
 * <p>.blacklist remove &lt;player&gt; — remove a name (tab-completes entries).
 * <p>.blacklist dis &lt;value&gt; — scan range in blocks (default 60, max 256).
 * <p>.blacklist list — entries, range and module state.
 *
 * <p>Runs on the client thread like every Meteor command; it only mutates module
 * settings, no packets are sent from the command itself.
 */
public class BlacklistCommand extends Command {
    public BlacklistCommand() {
        super("blacklist", "Manage the Blacklist auto-smash list (add/remove/dis/list).");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder
            .then(literal("add")
                .then(argument("player", StringArgumentType.word())
                    .suggests((context, suggestions) ->
                        CommandSource.suggestMatching(onlineNames(), suggestions))
                    .executes(context -> {
                        Blacklist module = Blacklist.get();
                        if (module == null) {
                            error("Blacklist module is not registered.");
                            return 0;
                        }
                        String stored = module.addName(StringArgumentType.getString(context, "player"));
                        if (stored == null) {
                            error("That name is already blacklisted.");
                            return 0;
                        }
                        info("%s blacklisted. (%d name(s), scan %d blocks)",
                            stored, module.nameSnapshot().size(), module.getDistance());
                        return SINGLE_SUCCESS;
                    })))
            .then(literal("remove")
                .then(argument("player", StringArgumentType.word())
                    .suggests((context, suggestions) ->
                        CommandSource.suggestMatching(blacklistNames(), suggestions))
                    .executes(context -> {
                        Blacklist module = Blacklist.get();
                        if (module == null) {
                            error("Blacklist module is not registered.");
                            return 0;
                        }
                        String removed = module.removeName(StringArgumentType.getString(context, "player"));
                        if (removed == null) {
                            error("That name is not blacklisted.");
                            return 0;
                        }
                        info("%s removed from the blacklist. (%d name(s) left)",
                            removed, module.nameSnapshot().size());
                        return SINGLE_SUCCESS;
                    })))
            .then(literal("dis")
                .then(argument("value", IntegerArgumentType.integer(0, Blacklist.MAX_DISTANCE))
                    .executes(context -> {
                        Blacklist module = Blacklist.get();
                        if (module == null) {
                            error("Blacklist module is not registered.");
                            return 0;
                        }
                        int value = IntegerArgumentType.getInteger(context, "value");
                        module.setDistance(value);
                        info("Blacklist scan distance: %d blocks.", value);
                        return SINGLE_SUCCESS;
                    })))
            .then(literal("list")
                .executes(context -> {
                    Blacklist module = Blacklist.get();
                    if (module == null) {
                        error("Blacklist module is not registered.");
                        return 0;
                    }
                    printStatus(module);
                    return SINGLE_SUCCESS;
                }))
            .executes(context -> {
                Blacklist module = Blacklist.get();
                if (module == null) {
                    error("Blacklist module is not registered.");
                    return 0;
                }
                printStatus(module);
                warning("Usage: .blacklist add/remove <player> | dis <0-%d> | list",
                    Blacklist.MAX_DISTANCE);
                return SINGLE_SUCCESS;
            });
    }

    private void printStatus(Blacklist module) {
        List<String> entries = module.nameSnapshot();
        if (entries.isEmpty()) {
            info("Blacklist is empty.");
        } else {
            info("Blacklist (%d): %s", entries.size(), String.join(", ", entries));
        }
        info("Scan distance: %d blocks — module is %s. Manage: .blacklist add/remove <player>, dis <value>.",
            module.getDistance(), module.isActive() ? "ON" : "off");
    }

    /** Online player names for the add-suggestions; self excluded. */
    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        if (mc != null && mc.world != null) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player) continue;
                names.add(player.getName().getString());
            }
        }
        return names;
    }

    /** Current blacklist entries for the remove-suggestions. */
    private List<String> blacklistNames() {
        Blacklist module = Blacklist.get();
        return module == null ? List.of() : module.nameSnapshot();
    }
}
