package com.github.games647.changeskin;

import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import com.google.common.collect.Multimap;

import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import javafx.util.Pair;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

public class PlayerLoginListener implements Listener {

    private static final String SKIN_KEY = "textures";

    private final ChangeSkin plugin;
    private final Random random = new Random();

    public PlayerLoginListener(ChangeSkin plugin) {
        this.plugin = plugin;
    }

    //we are making an blocking request it might be better to ignore it if normal priority events cancelled it
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent preLoginEvent) {
        if (preLoginEvent.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            //in this event isCancelled option in the annotation doesn't work
            return;
        }

        String name = preLoginEvent.getName();
        UUID playerUuid = preLoginEvent.getUniqueId();

        UUID targetUuid = plugin.getUserPreferences().get(playerUuid);
        if (targetUuid != null && !playerUuid.equals(targetUuid) && !plugin.getSkinCache().containsKey(targetUuid)) {
            //player selected a custom skin which isn't in the cache. Try to redownload it
            WrappedSignedProperty downloadedSkin = plugin.downloadSkin(targetUuid);
            if (downloadedSkin != null) {
                //run it blocking because we don't know how it takes, so it won't end into a race condition
                plugin.getSkinCache().put(targetUuid, downloadedSkin);
            }
        }
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent loginEvent) {
        if (loginEvent.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            //in this event isCancelled option in the annotation doesn't work
            return;
        }

        Player player = loginEvent.getPlayer();

        boolean skinFound = false;

        //try to use the existing and put it in the cache so we use it for others
        WrappedGameProfile gameProfile = WrappedGameProfile.fromPlayer(player);
        Multimap<String, WrappedSignedProperty> properties = gameProfile.getProperties();
        Collection<WrappedSignedProperty> values = properties.get(SKIN_KEY);
        for (WrappedSignedProperty value : values) {
            if (value.hasSignature()) {
                //found a skin
                plugin.getSkinCache().put(player.getUniqueId(), value);
                skinFound = true;
                break;
            }
        }

        //updates to the chosen one
        UUID targetUUID = plugin.getUserPreferences().get(player.getUniqueId());
        if (targetUUID != null) {
            WrappedSignedProperty cachedSkin = plugin.getSkinCache().get(targetUUID);
            if (cachedSkin != null) {
                properties.put(SKIN_KEY, cachedSkin);
            }
        } else if (!skinFound) {
            //skin wasn't found and there are no preferences so set a default skin
            List<Pair<UUID, WrappedSignedProperty>> defaultSkins = plugin.getDefaultSkins();
            if (!defaultSkins.isEmpty()) {
                int randomIndex = random.nextInt(defaultSkins.size());

                Pair<UUID, WrappedSignedProperty> targetSkin = defaultSkins.get(randomIndex);
                if (targetSkin != null) {
                    plugin.getUserPreferences().put(player.getUniqueId(), targetSkin.getKey());
                    plugin.getSkinCache().put(targetSkin.getKey(), targetSkin.getValue());
                    properties.put(SKIN_KEY, targetSkin.getValue());
                }
            }
        }
    }
}
