package com.github.games647.changeskin.bungee.tasks;

import com.github.games647.changeskin.bungee.ChangeSkinBungee;
import com.github.games647.changeskin.core.SkinData;

import java.util.UUID;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

public class SkinDownloader implements Runnable {

    protected final ChangeSkinBungee plugin;
    private final CommandSender invoker;
    private final ProxiedPlayer receiver;
    private final UUID targetUUID;

    public SkinDownloader(ChangeSkinBungee plugin, CommandSender invoker, ProxiedPlayer receiver, UUID targetUUID) {
        this.plugin = plugin;
        this.invoker = invoker;
        this.receiver = receiver;
        this.targetUUID = targetUUID;
    }

    @Override
    public void run() {
        SkinData newSkin = plugin.getStorage().getSkin(targetUUID);
        if (newSkin == null) {
            newSkin = plugin.getCore().downloadSkin(targetUUID);
        }

        if (targetUUID.equals(receiver.getUniqueId())) {
            plugin.sendMessage(invoker, "reset");
        }

        SkinUpdater skinUpdater = new SkinUpdater(plugin, invoker, receiver, newSkin);
        ProxyServer.getInstance().getScheduler().runAsync(plugin, skinUpdater);
    }
}
