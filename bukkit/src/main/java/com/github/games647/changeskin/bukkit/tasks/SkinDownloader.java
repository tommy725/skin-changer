package com.github.games647.changeskin.bukkit.tasks;

import com.github.games647.changeskin.bukkit.ChangeSkinBukkit;
import com.github.games647.changeskin.core.SkinData;
import com.github.games647.changeskin.core.UserPreferences;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SkinDownloader implements Runnable {

    protected final ChangeSkinBukkit plugin;
    private final CommandSender invoker;
    private final Player receiver;
    private final UUID targetUUID;

    public SkinDownloader(ChangeSkinBukkit plugin, CommandSender invoker, Player receiver, UUID targetSkin) {
        this.plugin = plugin;
        this.invoker = invoker;
        this.receiver = receiver;
        this.targetUUID = targetSkin;
    }

    @Override
    public void run() {
        SkinData skin = plugin.getStorage().getSkin(targetUUID);
        if (skin == null) {
            skin = plugin.getCore().downloadSkin(targetUUID);
        }

        //uuid was successfull resolved, we could now make a cooldown check
        if (invoker instanceof Player) {
            plugin.addCooldown(((Player) invoker).getUniqueId());
        }

        //Save the target uuid from the requesting player source
        final UserPreferences preferences = plugin.getStorage().getPreferences(receiver.getUniqueId());
        preferences.setTargetSkin(skin);

        final SkinData newSkin = skin;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                if (plugin.getStorage().save(newSkin)) {
                    plugin.getStorage().save(preferences);
                }
            }
        });

        if (targetUUID.equals(receiver.getUniqueId())) {
            plugin.sendMessage(invoker, "reset");
        }

        if (plugin.getConfig().getBoolean("instantSkinChange")) {
            plugin.getServer().getScheduler().runTask(plugin, new SkinUpdater(plugin, invoker, receiver, newSkin));
        } else if (invoker != null) {
            //if user is online notify the player
            plugin.sendMessage(invoker, "skin-changed-no-instant");
        }
    }
}
