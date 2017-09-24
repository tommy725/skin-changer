package com.github.games647.changeskin.sponge;

import com.github.games647.changeskin.core.ChangeSkinCore;
import com.github.games647.changeskin.core.NotPremiumException;
import com.github.games647.changeskin.core.RateLimitException;
import com.github.games647.changeskin.core.SkinStorage;
import com.github.games647.changeskin.core.model.SkinData;
import com.github.games647.changeskin.core.model.UserPreference;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.profile.GameProfileManager;
import org.spongepowered.api.profile.property.ProfileProperty;

public class LoginListener {

    private final ChangeSkinSponge plugin;
    private final Random random = new Random();

    public LoginListener(ChangeSkinSponge plugin) {
        this.plugin = plugin;
    }

    @Listener
    public void onPlayerPreLogin(ClientConnectionEvent.Auth preLoginEvent) {
        SkinStorage storage = plugin.getCore().getStorage();
        GameProfile profile = preLoginEvent.getProfile();
        UUID playerUUID = profile.getUniqueId();

        UserPreference preferences = storage.getPreferences(playerUUID);
        int autoUpdateDiff = plugin.getCore().getAutoUpdateDiff();
        SkinData targetSkin = preferences.getTargetSkin();
        if (targetSkin == null) {
            if (!plugin.getCore().getConfig().getBoolean("restoreSkins") || !refetch(preferences, profile)) {
                setDefaultSkin(preferences, profile);
            }
        } else {
            if (autoUpdateDiff > 0 && System.currentTimeMillis() - targetSkin.getTimestamp() > autoUpdateDiff) {
                Optional<SkinData> updatedSkin = plugin.getCore().getMojangSkinApi().downloadSkin(targetSkin.getUuid());
                if (updatedSkin.isPresent() && !Objects.equals(updatedSkin.get(), targetSkin)) {
                    plugin.cacheSponge(updatedSkin.get());
                    targetSkin = updatedSkin.get();
                }
            }

            applySkin(targetSkin, profile);
        }
    }

    private void applySkin(SkinData skinData, GameProfile profile) {
        GameProfileManager profileManager = plugin.getGame().getServer().getGameProfileManager();
        ProfileProperty profileProperty = profileManager.createProfileProperty(ChangeSkinCore.SKIN_KEY
                , skinData.getEncodedData(), skinData.getEncodedSignature());
        profile.getPropertyMap().clear();
        profile.getPropertyMap().put(ChangeSkinCore.SKIN_KEY, profileProperty);
    }

    private void setDefaultSkin(UserPreference preferences, GameProfile profile) {
        List<SkinData> defaultSkins = plugin.getCore().getDefaultSkins();
        if (!defaultSkins.isEmpty()) {
            int randomIndex = random.nextInt(defaultSkins.size());
            SkinData defaultSkin = defaultSkins.get(randomIndex);
            if (defaultSkin != null) {
                preferences.setTargetSkin(defaultSkin);
                save(defaultSkin, preferences);
                applySkin(defaultSkin, profile);
            }
        }
    }

    private boolean refetch(UserPreference preferences, GameProfile profile) {
        String playerName = profile.getName().get();
        UUID ownerUUID = plugin.getCore().getUuidCache().get(playerName);

        if (ownerUUID == null && !plugin.getCore().getCrackedNames().containsKey(playerName)) {
            try {
                Optional<UUID> optUUID = plugin.getCore().getMojangSkinApi().getUUID(playerName);
                if (optUUID.isPresent()) {
                    ownerUUID = optUUID.get();
                    plugin.getCore().getUuidCache().put(playerName, ownerUUID);
                }
            } catch (NotPremiumException ex) {
                plugin.getLogger().log(Level.FINE,"User is not premium", ex);
                plugin.getCore().getCrackedNames().put(playerName, new Object());
            } catch (RateLimitException ex) {
                plugin.getLogger().log(Level.FINE,"Rate limit reached on refetch", ex);
            }
        }

        if (ownerUUID != null) {
            SkinData storedSkin = plugin.getCore().getStorage().getSkin(ownerUUID);
            int updateDiff = plugin.getCore().getAutoUpdateDiff();
            if (storedSkin == null
                    || (updateDiff > 0 && System.currentTimeMillis() - storedSkin.getTimestamp() > updateDiff)) {
                Optional<SkinData> updatedSkin = plugin.getCore().getMojangSkinApi().downloadSkin(ownerUUID);
                if (updatedSkin.isPresent() && !Objects.equals(updatedSkin.get(), storedSkin)) {
                    plugin.cacheSponge(updatedSkin.get());
                    storedSkin = updatedSkin.get();
                }
            }

            preferences.setTargetSkin(storedSkin);
            save(storedSkin, preferences);
            applySkin(storedSkin, profile);
            return true;
        }

        return false;
    }

    private void save(final SkinData skinData, final UserPreference preferences) {
        plugin.getGame().getScheduler().createTaskBuilder()
                .async()
                .execute(() -> {
                    if (plugin.getCore().getStorage().save(skinData)) {
                        plugin.getCore().getStorage().save(preferences);
                    }
                }).submit(plugin);
    }
}
