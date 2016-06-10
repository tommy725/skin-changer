package com.github.games647.changeskin.bukkit.tasks;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.FieldAccessException;
import com.comphenix.protocol.wrappers.EnumWrappers.Difficulty;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.github.games647.changeskin.bukkit.ChangeSkinBukkit;
import com.github.games647.changeskin.core.ChangeSkinCore;
import com.github.games647.changeskin.core.SkinData;
import com.github.games647.changeskin.core.UserPreferences;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SkinUpdater implements Runnable {

    private final ChangeSkinBukkit plugin;
    private final CommandSender invoker;
    private final Player receiver;

    public SkinUpdater(ChangeSkinBukkit plugin, CommandSender invoker, Player receiver) {
        this.plugin = plugin;
        this.invoker = invoker;
        this.receiver = receiver;
    }

    @Override
    public void run() {
        if (!receiver.isOnline()) {
            return;
        }

        WrappedGameProfile gameProfile = WrappedGameProfile.fromPlayer(receiver);
        if (plugin.getStorage() != null) {
            UserPreferences preferences = plugin.getStorage().getPreferences(receiver.getUniqueId(), false);
            SkinData targetSkin = preferences.getTargetSkin();

            //remove existing skins
            gameProfile.getProperties().clear();
            if (targetSkin != null) {
                gameProfile.getProperties().put(ChangeSkinCore.SKIN_KEY, plugin.convertToProperty(targetSkin));
            }
        }

        sendUpdate(gameProfile);
        if (invoker != null) {
            plugin.sendMessage(invoker, "skin-changed");
        }
    }

    private void sendUpdate(WrappedGameProfile gameProfile) throws FieldAccessException {
        sendUpdateSelf(gameProfile);

        //triggers an update for others player to see the new skin
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.equals(receiver) || !onlinePlayer.canSee(receiver)) {
                continue;
            }

            //removes the entity and display the new skin
            onlinePlayer.hidePlayer(receiver);
            onlinePlayer.showPlayer(receiver);
        }
    }

    private void sendUpdateSelf(WrappedGameProfile gameProfile) throws FieldAccessException {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        NativeGameMode gamemode = NativeGameMode.fromBukkit(receiver.getGameMode());

        //remove info
        PacketContainer removeInfo = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
        removeInfo.getPlayerInfoAction().write(0, PlayerInfoAction.REMOVE_PLAYER);

        WrappedChatComponent displayName = WrappedChatComponent.fromText(receiver.getDisplayName());
        PlayerInfoData playerInfoData = new PlayerInfoData(gameProfile, 0, gamemode, displayName);
        removeInfo.getPlayerInfoDataLists().write(0, Arrays.asList(playerInfoData));

        //add info containing the skin data
        PacketContainer addInfo = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
        addInfo.getPlayerInfoAction().write(0, PlayerInfoAction.ADD_PLAYER);
        addInfo.getPlayerInfoDataLists().write(0, Arrays.asList(playerInfoData));

        //Respawn packet
        PacketContainer respawn = protocolManager.createPacket(PacketType.Play.Server.RESPAWN);
        respawn.getIntegers().write(0, receiver.getWorld().getEnvironment().getId());
        respawn.getDifficulties().write(0, Difficulty.valueOf(receiver.getWorld().getDifficulty().toString()));
        respawn.getGameModes().write(0, gamemode);
        respawn.getWorldTypeModifier().write(0, receiver.getWorld().getWorldType());

        try {
            //remove the old skin - client updates it only on a complete remove and add
            protocolManager.sendServerPacket(receiver, removeInfo);
            //adds the skin
            protocolManager.sendServerPacket(receiver, addInfo);
            //notify the client that it should update the own skin
            protocolManager.sendServerPacket(receiver, respawn);

            //prevent the moved too quickly message
            receiver.teleport(receiver);

            //send the current inventory - otherwise player would have an empty inventory
            receiver.updateInventory();
        } catch (InvocationTargetException ex) {
            plugin.getLogger().log(Level.SEVERE, "Exception sending instant skin change packet", ex);
        }
    }
}
