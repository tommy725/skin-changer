package com.github.games647.changeskin.bungee;

import com.github.games647.changeskin.bungee.tasks.NameResolver;
import com.github.games647.changeskin.bungee.tasks.SkinDownloader;
import com.github.games647.changeskin.core.UserPreferences;

import java.util.UUID;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

public class SetSkinCommand extends Command {

    protected final ChangeSkinBungee plugin;

    public SetSkinCommand(ChangeSkinBungee plugin) {
        super("setskin", plugin.getDescription().getName().toLowerCase() + ".command.setskin"
                , "skin", plugin.getDescription().getName());

        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (sender instanceof ProxiedPlayer && plugin.isCooldown(((ProxiedPlayer) sender).getUniqueId())) {
            sender.sendMessage(ChatColor.DARK_RED + "Please wait. You cannot change the skin so fast");
            return;
        }

        if (args.length > 1) {
            if (!sender.hasPermission(plugin.getDescription().getName().toLowerCase() + ".command.setskin.other")) {
                sender.sendMessage(ChatColor.DARK_RED + "No permission to change other skins");
                return;
            }

            String targetPlayerName = args[0];
            String toSkin = args[1];

            ProxiedPlayer targetPlayer = ProxyServer.getInstance().getPlayer(targetPlayerName);
            if (targetPlayer == null) {
                sender.sendMessage(new ComponentBuilder("This player isn't online").color(ChatColor.DARK_RED).create());
            } else {
                setSkin(sender, targetPlayer, toSkin);
            }
        } else if (sender instanceof ProxiedPlayer) {
            if (args.length == 1) {
                if ("reset".equalsIgnoreCase(args[0])) {
                    setSkinUUID(sender, (ProxiedPlayer) sender, ((ProxiedPlayer) sender).getUniqueId().toString());
                    return;
                }

                setSkin(sender, (ProxiedPlayer) sender, args[0]);
            } else {
                sender.sendMessage(new ComponentBuilder("You have to provide the skin you want to change to")
                        .color(ChatColor.DARK_RED).create());
            }
        } else {
            sender.sendMessage(new ComponentBuilder("You have to be a player to set your own skin")
                        .color(ChatColor.DARK_RED).create());
        }
    }

    private void setSkin(CommandSender sender, ProxiedPlayer targetPlayer, String toSkin) {
        //minecraft player names has the max length of 16 characters so it could be the uuid
        if (toSkin.length() > 16) {
            setSkinUUID(sender, targetPlayer, toSkin);
        } else {
            sender.sendMessage(new ComponentBuilder("Queued name to uuid resolve").color(ChatColor.GOLD).create());
            NameResolver nameResolver = new NameResolver(plugin, sender, toSkin, targetPlayer);
            ProxyServer.getInstance().getScheduler().runAsync(plugin, nameResolver);
        }
    }

    private void setSkinUUID(CommandSender sender, ProxiedPlayer receiverPayer, String targetUUID) {
        try {
            UUID uuid = UUID.fromString(targetUUID);
            if (plugin.getConfiguration().getBoolean("skinPermission") && !plugin.checkPermission(sender, uuid)) {
                return;
            }

            if (receiverPayer.getUniqueId().equals(uuid)) {
                sender.sendMessage(new ComponentBuilder("Reseting preferences to the default value")
                        .color(ChatColor.DARK_GREEN).create());

                final UserPreferences preferences = plugin.getStorage().getPreferences(uuid, false);
                preferences.setTargetSkin(null);
                ProxyServer.getInstance().getScheduler().runAsync(plugin, new Runnable() {
                    @Override
                    public void run() {
                        plugin.getStorage().save(preferences);
                    }
                });

                SkinDownloader skinDownloader = new SkinDownloader(plugin, sender, receiverPayer, uuid);
                ProxyServer.getInstance().getScheduler().runAsync(plugin, skinDownloader);
            } else {
                sender.sendMessage(new ComponentBuilder("Queued Skin change").color(ChatColor.GOLD).create());

                SkinDownloader skinDownloader = new SkinDownloader(plugin, sender, receiverPayer, uuid);
                ProxyServer.getInstance().getScheduler().runAsync(plugin, skinDownloader);
            }
        } catch (IllegalArgumentException illegalArgumentException) {
            sender.sendMessage(new ComponentBuilder("Invalid uuid").color(ChatColor.DARK_RED).create());
        }
    }
}
