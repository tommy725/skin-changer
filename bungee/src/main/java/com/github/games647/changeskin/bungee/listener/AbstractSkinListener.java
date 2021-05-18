package com.github.games647.changeskin.bungee.listener;

import com.github.games647.changeskin.bungee.ChangeSkinBungee;
import com.github.games647.changeskin.core.model.UserPreference;
import com.github.games647.changeskin.core.model.skin.SkinModel;
import com.github.games647.changeskin.core.shared.SharedListener;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Listener;

public abstract class AbstractSkinListener extends SharedListener implements Listener {

    protected final ChangeSkinBungee plugin;

    public AbstractSkinListener(ChangeSkinBungee plugin) {
        super(plugin.getCore());

        this.plugin = plugin;
    }

    @Override
    public void save(final UserPreference preferences) {
        //this can run in the background
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            Optional<SkinModel> optSkin = preferences.getTargetSkin();
            if (optSkin.isPresent()) {
                if (core.getStorage().save(optSkin.get())) {
                    core.getStorage().save(preferences);
                }
            } else {
                core.getStorage().save(preferences);
            }
        });
    }

    protected UserPreference initializeProfile(UUID uniqueId, String playerName) {
        UserPreference preferences = plugin.getStorage().getPreferences(uniqueId);

        Optional<SkinModel> optSkin = preferences.getTargetSkin();
        if (optSkin.isPresent()) {
            SkinModel targetSkin = optSkin.get();
            if (!preferences.isKeepSkin()) {
                targetSkin = core.checkAutoUpdate(targetSkin);
            }

            preferences.setTargetSkin(targetSkin);
        } else if (core.getConfig().getBoolean("restoreSkins")) {
            refetchSkin(playerName, preferences);
            if (!preferences.getTargetSkin().isPresent()) {
                //still no skin
                getRandomSkin().ifPresent(preferences::setTargetSkin);
            }
        }

        return preferences;
    }

    protected boolean isBlacklistEnabled() {
        List<String> blacklist = core.getConfig().getStringList("server-blacklist");
        return !blacklist.isEmpty();
    }
}
