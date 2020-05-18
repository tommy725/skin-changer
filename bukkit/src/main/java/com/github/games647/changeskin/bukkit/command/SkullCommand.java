package com.github.games647.changeskin.bukkit.command;

import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.github.games647.changeskin.bukkit.ChangeSkinBukkit;
import com.github.games647.changeskin.bukkit.ServerVersion;
import com.github.games647.changeskin.core.model.skin.SkinModel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Shynixn
 */
public class SkullCommand implements CommandExecutor {

    //MethodHandle is only faster for static final fields and invokeExact
    private static final MethodHandle skullProfileSetter;

    static {
        MethodHandle methodHandle = null;
        try {
            ServerVersion version = new ServerVersion(Bukkit.getServer().getClass());

            Class<?> clazz = Class.forName(version.getOBCPackage() + ".inventory.CraftMetaSkull");
            Field profileField = clazz.getDeclaredField("profile");
            profileField.setAccessible(true);

            methodHandle = MethodHandles.lookup().unreflectSetter(profileField)
                    .asType(MethodType.methodType(Void.class, SkullMeta.class, Object.class));
        } catch (ReflectiveOperationException ex) {
            Logger logger = LoggerFactory.getLogger(SkullCommand.class);
            logger.info("Cannot find profile field for setting skulls", ex);
        }

        skullProfileSetter = methodHandle;
    }

    private final ChangeSkinBukkit plugin;

    public SkullCommand(ChangeSkinBukkit plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.sendMessage(sender, "no-console");
            return true;
        }

        if (args.length == 0) {
            plugin.sendMessage(sender, "select-noargs");
        } else {
            String targetName = args[0].toLowerCase().replace("skin-", "");
            try {
                Player player = (Player) sender;
                int targetId = Integer.parseInt(targetName);

                BukkitScheduler scheduler = Bukkit.getScheduler();
                scheduler.runTaskAsynchronously(plugin, () -> applySkin(player, plugin.getStorage().getSkin(targetId)));
            } catch (NumberFormatException numberFormatException) {
                plugin.sendMessage(sender, "invalid-skin-name");
            }
        }

        return true;
    }

    private void applySkin(Player player, SkinModel skinData) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (skinData == null) {
                player.sendMessage(ChatColor.DARK_RED + "Skin not found");
                return;
            }

            ItemStack itemInHand = player.getInventory().getItem(player.getInventory().getHeldItemSlot());
            if (itemInHand == null || itemInHand.getType() != Material.PLAYER_HEAD) {
                player.sendMessage(ChatColor.DARK_RED + "Player head item not in hand");
                return;
            }

            setSkullSkin(itemInHand, skinData);
            player.updateInventory();
            player.sendMessage(ChatColor.DARK_RED + "Skin updated");
        });
    }

    private void setSkullSkin(ItemStack itemStack, SkinModel skinData) {
        try {
            SkullMeta skullMeta = (SkullMeta) itemStack.getItemMeta();

            WrappedGameProfile gameProfile = new WrappedGameProfile(UUID.randomUUID(), null);
            plugin.getApi().applyProperties(gameProfile, skinData);

            skullProfileSetter.invokeExact(skullMeta, gameProfile.getHandle());
            itemStack.setItemMeta(skullMeta);
        } catch (Exception ex) {
            plugin.getLog().info("Failed to set skull item {} to {}", itemStack, skinData, ex);
        } catch (Throwable throwable) {
            //rethrow errors we shouldn't silence them like OutOfMemory
            throw (Error) throwable;
        }
    }
}
