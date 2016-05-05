package com.github.games647.changeskin.bukkit;

import com.github.games647.changeskin.core.UserPreferences;
import com.github.games647.changeskin.bukkit.tasks.NameResolver;
import com.github.games647.changeskin.bukkit.tasks.SkinDownloader;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SetSkinCommand implements CommandExecutor {

    protected final ChangeSkinBukkit plugin;

    public SetSkinCommand(ChangeSkinBukkit plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 1) {
            String targetPlayerName = args[0];
            String toSkin = args[1];

            Player targetPlayer = Bukkit.getPlayerExact(targetPlayerName);
            if (targetPlayer == null) {
                sender.sendMessage(ChatColor.DARK_RED + "This player isn't online");
            } else {
                setSkin(sender, targetPlayer, toSkin);
            }
        } else if (sender instanceof Player) {
            if (args.length == 1) {
                if ("reset".equalsIgnoreCase(args[0])) {
                    setSkinUUID(sender, (Player) sender, ((Player) sender).getUniqueId().toString());
                    return true;
                }

                setSkin(sender, (Player) sender, args[0]);
            } else {
                sender.sendMessage(ChatColor.DARK_RED + "You have to provide the skin you want to change to");
            }
        } else {
            sender.sendMessage(ChatColor.DARK_RED + "You have to be a player to set your own skin");
        }

        return true;
    }

    private void setSkin(CommandSender sender, Player targetPlayer, String toSkin) {
        //minecraft player names has the max length of 16 characters so it could be the uuid
        if (toSkin.length() > 16) {
            setSkinUUID(sender, targetPlayer, toSkin);
        } else {
            sender.sendMessage(ChatColor.GOLD + "Queued name to uuid resolve");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, new NameResolver(plugin, sender, toSkin, targetPlayer));
        }
    }

    private void setSkinUUID(CommandSender sender, Player receiverPayer, String targetUUID) {
        try {
            UUID uuid = UUID.fromString(targetUUID);
            if (plugin.getConfig().getBoolean("skinPermission")) {
                if (sender.hasPermission(plugin.getName().toLowerCase() + ".skin.whitelist." + uuid.toString())) {
                    //allow - is whitelist
                } else if (sender.hasPermission(plugin.getName().toLowerCase() + ".skin.whitelist.*")) {
                    if (sender.hasPermission(plugin.getName().toLowerCase() + ".skin.blacklist." + uuid.toString())) {
                        //dissallow - blacklisted
                        sender.sendMessage(ChatColor.DARK_RED + "You don't have the permission to set this skin");
                        return;
                    } else {
                        //allow - wildcard whitelisted
                    }
                } else {
                    //disallow - not whitelisted
                    sender.sendMessage(ChatColor.DARK_RED + "You don't have the permission to set this skin");
                    return;
                }
            }

            if (receiverPayer.getUniqueId().equals(uuid)) {
                sender.sendMessage(ChatColor.DARK_GREEN + "Reseting preferences to the default value");

                final UserPreferences preferences = plugin.getStorage().getPreferences(uuid, false);
                preferences.setTargetSkin(null);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                    @Override
                    public void run() {
                        plugin.getStorage().save(preferences);
                    }
                });

                SkinDownloader skinDownloader = new SkinDownloader(plugin, sender, receiverPayer, uuid);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, skinDownloader);
            } else {
                sender.sendMessage(ChatColor.GOLD + "Queued Skin change");

                SkinDownloader skinDownloader = new SkinDownloader(plugin, sender, receiverPayer, uuid);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, skinDownloader);
            }
        } catch (IllegalArgumentException illegalArgumentException) {
            sender.sendMessage(ChatColor.DARK_RED + "Invalid uuid");
        }
    }
}
