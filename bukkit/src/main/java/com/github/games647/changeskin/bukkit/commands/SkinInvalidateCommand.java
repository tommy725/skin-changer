package com.github.games647.changeskin.bukkit.commands;

import com.github.games647.changeskin.bukkit.ChangeSkinBukkit;
import com.github.games647.changeskin.bukkit.tasks.SkinInvalidater;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SkinInvalidateCommand implements CommandExecutor {

    private final ChangeSkinBukkit plugin;

    public SkinInvalidateCommand(ChangeSkinBukkit plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            Player targetPlayer = Bukkit.getPlayerExact(args[0]);
            if (targetPlayer == null) {
                plugin.sendMessage(sender, "not-online");
                return true;
            }

            SkinInvalidater skinInvalidater = new SkinInvalidater(plugin, sender, targetPlayer);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, skinInvalidater);
            return true;
        }

        if (!(sender instanceof Player)) {
            plugin.sendMessage(sender, "no-console");
            return true;
        }

        Player receiver = (Player) sender;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new SkinInvalidater(plugin, sender, receiver));
        return true;
    }
}
