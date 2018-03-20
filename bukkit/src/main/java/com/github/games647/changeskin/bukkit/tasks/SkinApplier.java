package com.github.games647.changeskin.bukkit.tasks;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.FieldAccessException;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.EnumWrappers.Difficulty;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.github.games647.changeskin.bukkit.ChangeSkinBukkit;
import com.github.games647.changeskin.core.model.UserPreference;
import com.github.games647.changeskin.core.model.skin.SkinModel;
import com.github.games647.changeskin.core.shared.SharedApplier;
import com.nametagedit.plugin.NametagEdit;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import static com.comphenix.protocol.PacketType.Play.Server.PLAYER_INFO;
import static com.comphenix.protocol.PacketType.Play.Server.POSITION;
import static com.comphenix.protocol.PacketType.Play.Server.RESPAWN;
import static com.comphenix.protocol.PacketType.Play.Server.UPDATE_HEALTH;

public class SkinApplier extends SharedApplier {

    protected final ChangeSkinBukkit plugin;
    private final CommandSender invoker;
    private final Player receiver;
    private final SkinModel targetSkin;
    private final boolean keepSkin;

    public SkinApplier(ChangeSkinBukkit plugin, CommandSender invoker, Player receiver
            , SkinModel targetSkin, boolean keepSkin) {
        super(plugin.getCore(), targetSkin, keepSkin);

        this.plugin = plugin;
        this.invoker = invoker;
        this.receiver = receiver;
        this.targetSkin = targetSkin;
        this.keepSkin = keepSkin;
    }

    @Override
    public void run() {
        if (!isConnected()) {
            return;
        }

        //uuid was successful resolved, we could now make a cooldown check
        if (invoker instanceof Player && core != null) {
            UUID uniqueId = ((Player) invoker).getUniqueId();
            core.getCooldownService().trackPlayer(uniqueId);
        }

        if (plugin.getStorage() != null) {
            UserPreference preferences = plugin.getStorage().getPreferences(receiver.getUniqueId());
            save(preferences);
        }

        applySkin();
    }

    @Override
    protected boolean isConnected() {
        return receiver != null && receiver.isOnline();
    }

    @Override
    protected void applyInstantUpdate() {
        plugin.getApi().applySkin(receiver, targetSkin);

        sendUpdateSelf(WrappedGameProfile.fromPlayer(receiver));
        sendUpdateOthers();

        if (receiver.equals(invoker)) {
            plugin.sendMessage(receiver, "skin-changed");
        } else {
            plugin.sendMessage(invoker, "skin-updated");
        }
    }

    @Override
    protected void sendMessage(String key) {
        plugin.sendMessage(invoker, key);
    }

    @Override
    protected void runAsync(Runnable runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    private void sendUpdateOthers() throws FieldAccessException {
        //triggers an update for others player to see the new skin
        Bukkit.getOnlinePlayers().stream()
                .filter(onlinePlayer -> !onlinePlayer.equals(receiver))
                .filter(onlinePlayer -> onlinePlayer.canSee(receiver))
                .forEach(this::hideAndShow);
    }

    private void sendUpdateSelf(WrappedGameProfile gameProfile) throws FieldAccessException {
        Entity vehicle = receiver.getVehicle();
        if (vehicle != null) {
            vehicle.eject();
        }

        sendPacketsSelf(gameProfile);

        //send the current inventory - otherwise player would have an empty inventory
        receiver.updateInventory();

        PlayerInventory inventory = receiver.getInventory();
        inventory.setHeldItemSlot(inventory.getHeldItemSlot());

        //this is sync so should be safe to call

        //exp
        float experience = receiver.getExp();
        int totalExperience = receiver.getTotalExperience();
        receiver.setExp(experience);
        receiver.setTotalExperience(totalExperience);

        //set to the correct hand position
        setItemInHand();

        //triggers updateAbilities
        receiver.setWalkSpeed(receiver.getWalkSpeed());

        if (Bukkit.getPluginManager().isPluginEnabled("NametagEdit")) {
            NametagEdit.getApi().reloadNametag(receiver);
        }
    }

    private void sendPacketsSelf(WrappedGameProfile gameProfile) {
        NativeGameMode gamemode = NativeGameMode.fromBukkit(receiver.getGameMode());

        //remove the old skin - client updates it only on a complete remove and add
        PacketContainer removeInfo = new PacketContainer(PLAYER_INFO);
        removeInfo.getPlayerInfoAction().write(0, PlayerInfoAction.REMOVE_PLAYER);

        WrappedChatComponent displayName = WrappedChatComponent.fromText(receiver.getPlayerListName());
        PlayerInfoData playerInfoData = new PlayerInfoData(gameProfile, 0, gamemode, displayName);
        removeInfo.getPlayerInfoDataLists().write(0, Collections.singletonList(playerInfoData));

        //add info containing the skin data
        PacketContainer addInfo = new PacketContainer(PLAYER_INFO);
        addInfo.getPlayerInfoAction().write(0, PlayerInfoAction.ADD_PLAYER);
        addInfo.getPlayerInfoDataLists().write(0, Collections.singletonList(playerInfoData));

        //Respawn packet
        // notify the client that it should update the own skin
        Difficulty difficulty = EnumWrappers.getDifficultyConverter().getSpecific(receiver.getWorld().getDifficulty());

        PacketContainer respawn = new PacketContainer(RESPAWN);
        respawn.getIntegers().write(0, receiver.getWorld().getEnvironment().getId());
        respawn.getDifficulties().write(0, difficulty);
        respawn.getGameModes().write(0, gamemode);
        respawn.getWorldTypeModifier().write(0, receiver.getWorld().getWorldType());

        Location location = receiver.getLocation().clone();

        //prevent the moved too quickly message
        PacketContainer teleport = new PacketContainer(POSITION);
        teleport.getModifier().writeDefaults();
        teleport.getDoubles().write(0, location.getX());
        teleport.getDoubles().write(1, location.getY());
        teleport.getDoubles().write(2, location.getZ());
        teleport.getFloat().write(0, location.getYaw());
        teleport.getFloat().write(1, location.getPitch());
        //send an invalid teleport id in order to let Bukkit ignore the incoming confirm packet
        teleport.getIntegers().writeSafely(0, -1337);

        sendPackets(removeInfo, addInfo, respawn, teleport);

        //trigger update attributes like health modifier for generic.maxHealth
        try {
            receiver.getClass().getDeclaredMethod("updateScaledHealth").invoke(receiver);
        } catch (ReflectiveOperationException reflectiveEx) {
            plugin.getLog().error("Failed to invoke updateScaledHealth for attributes", reflectiveEx);
        }

        PacketContainer health = new PacketContainer(UPDATE_HEALTH);
        health.getFloat().write(0, (float) receiver.getHealth());
        health.getFloat().write(1, receiver.getSaturation());
        health.getIntegers().write(0, receiver.getFoodLevel());
        sendPackets(health);
    }

    @SuppressWarnings("deprecation")
    private void hideAndShow(Player other) {
        //removes the entity and display the new skin
        try {
            other.getClass().getDeclaredMethod("hidePlayer", Plugin.class, Player.class);

            other.hidePlayer(plugin, receiver);
            other.showPlayer(plugin, receiver);
        } catch (NoSuchMethodException noSuckMethodEx) {
            other.hidePlayer(receiver);
            other.showPlayer(receiver);
        }
    }

    private void sendPackets(PacketContainer... packets) {
        try {
            ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
            for (PacketContainer packet : packets) {
                protocolManager.sendServerPacket(receiver, packet);
            }
        } catch (InvocationTargetException ex) {
            plugin.getLog().error("Exception sending instant skin change packet for: {}", receiver, ex);
        }
    }

    @SuppressWarnings("deprecation")
    private void setItemInHand() {
        if (MinecraftVersion.getCurrentVersion().compareTo(MinecraftVersion.COMBAT_UPDATE) >= 0) {
            receiver.getInventory().setItemInMainHand(receiver.getInventory().getItemInMainHand());
            receiver.getInventory().setItemInOffHand(receiver.getInventory().getItemInOffHand());
            return;
        }

        receiver.getInventory().setItemInHand(receiver.getItemInHand());
    }
}
