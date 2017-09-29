package com.github.games647.changeskin.sponge.commands;

import com.github.games647.changeskin.sponge.ChangeSkinSponge;
import com.github.games647.changeskin.sponge.tasks.SkinInvalidator;

import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.scheduler.Task;

public class InvalidateCommand implements CommandExecutor {

    private final ChangeSkinSponge plugin;

    public InvalidateCommand(ChangeSkinSponge plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
        if (!(src instanceof Player)) {
            plugin.sendMessageKey(src, "no-console");
            return CommandResult.empty();
        }

        Player receiver = (Player) src;
        Task.builder()
                .async()
                .execute(new SkinInvalidator(plugin, receiver))
                .submit(plugin);
        return CommandResult.success();
    }
}
