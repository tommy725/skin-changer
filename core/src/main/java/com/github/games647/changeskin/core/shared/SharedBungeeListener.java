package com.github.games647.changeskin.core.shared;

import com.github.games647.changeskin.core.PlatformPlugin;
import com.github.games647.changeskin.core.message.ChannelMessage;
import com.github.games647.changeskin.core.message.CheckPermMessage;
import com.github.games647.changeskin.core.message.PermResultMessage;
import com.github.games647.changeskin.core.message.SkinUpdateMessage;
import com.github.games647.changeskin.core.model.skin.SkinModel;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.util.UUID;

public abstract class SharedBungeeListener<P> {

    protected final PlatformPlugin<?> plugin;
    protected final String channelName;

    public SharedBungeeListener(PlatformPlugin<?> plugin) {
        this.plugin = plugin;
        this.channelName = plugin.getName();
    }

    protected void handlePayload(P player, byte[] data) {
        ByteArrayDataInput dataInput = ByteStreams.newDataInput(data);
        String subChannel = dataInput.readUTF();

        if ("UpdateSkin".equalsIgnoreCase(subChannel)) {
            updateSkin(player, dataInput);
        } else if ("PermissionsCheck".equalsIgnoreCase(subChannel)) {
            checkPermissions(player, dataInput);
        }
    }

    private void updateSkin(P sender, ByteArrayDataInput dataInput) throws IllegalArgumentException {
        SkinUpdateMessage message = new SkinUpdateMessage();
        message.readFrom(dataInput);

        String playerName = message.getPlayerName();
        P receiver = getPlayerExact(playerName);

        //unnecessary to send the skin, the properties will be send by BungeeCord
        plugin.getLog().info("Instant update for {}", playerName);
        runUpdater(sender, receiver, null);
    }

    private void checkPermissions(P player, ByteArrayDataInput dataInput) {
        CheckPermMessage message = new CheckPermMessage();
        message.readFrom(dataInput);

        UUID receiverUUID = message.getReceiverUUD();
        boolean op = message.isOp();
        SkinModel targetSkin = message.getTargetSkin();
        UUID skinProfile = targetSkin.getProfileId();

        boolean success = op || checkBungeePerms(player, receiverUUID, message.isSkinPerm(), skinProfile);
        sendMessage(player, new PermResultMessage(success, targetSkin, receiverUUID));
    }

    private boolean checkBungeePerms(P player, UUID receiverUUID, boolean skinPerm, UUID targetUUID) {
        if (getUUID(player).equals(receiverUUID)) {
            return checkPerm(player, "command.setskin", skinPerm, targetUUID);
        }

        return checkPerm(player, "command.setskin.other", skinPerm, targetUUID);
    }

    private boolean checkPerm(P invoker, String node, boolean skinPerm, UUID targetUUID) {
        String pluginName = plugin.getName().toLowerCase();
        boolean hasCommandPerm = hasPermission(invoker, pluginName +  '.' + node);
        if (skinPerm) {
            return hasCommandPerm && checkWhitelistPermission(invoker, targetUUID);
        }

        return hasCommandPerm;
    }

    protected abstract void sendMessage(P player, String channel, byte[] data);

    protected void sendMessage(P player, ChannelMessage message) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(message.getChannelName());

        message.writeTo(out);
        sendMessage(player, channelName, out.toByteArray());
    }

    protected abstract void runUpdater(P sender, P receiver, SkinModel targetSkin);

    protected abstract P getPlayerExact(String name);

    protected abstract UUID getUUID(P player);

    protected abstract boolean hasPermission(P player, String permission);

    protected abstract boolean checkWhitelistPermission(P player, UUID targetUUID);
}
