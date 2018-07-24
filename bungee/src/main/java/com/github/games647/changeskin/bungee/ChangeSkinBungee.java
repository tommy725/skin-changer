package com.github.games647.changeskin.bungee;

import com.github.games647.changeskin.bungee.command.InfoCommand;
import com.github.games647.changeskin.bungee.command.InvalidateCommand;
import com.github.games647.changeskin.bungee.command.SelectCommand;
import com.github.games647.changeskin.bungee.command.SetCommand;
import com.github.games647.changeskin.bungee.command.UploadCommand;
import com.github.games647.changeskin.bungee.listener.ConnectListener;
import com.github.games647.changeskin.bungee.listener.PluginMessageListener;
import com.github.games647.changeskin.bungee.listener.ServerSwitchListener;
import com.github.games647.changeskin.core.ChangeSkinCore;
import com.github.games647.changeskin.core.CommonUtil;
import com.github.games647.changeskin.core.message.NamespaceKey;
import com.github.games647.changeskin.core.PlatformPlugin;
import com.github.games647.changeskin.core.SkinStorage;
import com.github.games647.changeskin.core.message.ChannelMessage;
import com.github.games647.changeskin.core.model.UserPreference;
import com.google.common.collect.MapMaker;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.api.scheduler.GroupedThreadFactory;

import org.slf4j.Logger;

import static com.github.games647.changeskin.core.message.ForwardMessage.FORWARD_COMMAND_CHANNEL;
import static com.github.games647.changeskin.core.message.PermResultMessage.PERMISSION_RESULT_CHANNEL;

public class ChangeSkinBungee extends Plugin implements PlatformPlugin<CommandSender> {

    private final ConcurrentMap<PendingConnection, UserPreference> loginSessions = new MapMaker().weakKeys().makeMap();
    private final BungeeSkinAPI api = new BungeeSkinAPI(this);

    private ChangeSkinCore core;
    private Logger logger;

    @Override
    public void onEnable() {
        logger = CommonUtil.createLoggerFromJDK(getLogger());

        core = new ChangeSkinCore(this);
        try {
            core.load(true);
        } catch (Exception ioExc) {
            logger.error("Error initializing plugin. Disabling...", ioExc);
            return;
        }

        PluginManager pluginManager = getProxy().getPluginManager();
        pluginManager.registerListener(this, new ConnectListener(this));
        pluginManager.registerListener(this, new ServerSwitchListener(this));

        //this is required to listen to incoming messages from the server
        getProxy().registerChannel(new NamespaceKey(getName(), PERMISSION_RESULT_CHANNEL).getCombinedName());
        getProxy().registerChannel(new NamespaceKey(getName(), FORWARD_COMMAND_CHANNEL).getCombinedName());
        pluginManager.registerListener(this, new PluginMessageListener(this));

        //register commands
        pluginManager.registerCommand(this, new SetCommand(this));
        pluginManager.registerCommand(this, new InvalidateCommand(this));
        pluginManager.registerCommand(this, new UploadCommand(this));
        pluginManager.registerCommand(this, new SelectCommand(this));
        pluginManager.registerCommand(this, new InfoCommand(this));
    }

    @Override
    public void onDisable() {
        Collection<PendingConnection> toSave = new HashSet<>(loginSessions.keySet());
        toSave.parallelStream().map(loginSessions::remove).filter(Objects::nonNull).forEach(core.getStorage()::save);

        if (core != null) {
            core.close();
        }
    }

    @Override
    public String getName() {
        return getDescription().getName();
    }

    public BungeeSkinAPI getApi() {
        return api;
    }

    @Override
    public Logger getLog() {
        return logger;
    }

    @Override
    public Path getPluginFolder() {
        return getDataFolder().toPath();
    }

    @Override
    public void sendMessage(CommandSender receiver, String key) {
        String message = core.getMessage(key);
        if (message != null && receiver != null) {
            receiver.sendMessage(TextComponent.fromLegacyText(message));
        }
    }

    @Override
    public ThreadFactory getThreadFactory() {
        return new ThreadFactoryBuilder()
                .setNameFormat(getName() + " Database Pool Thread #%1$d")
                //Hikari create daemons by default
                .setDaemon(true)
                .setThreadFactory(new GroupedThreadFactory(this, getName()))
                .build();
    }

    public void sendPluginMessage(Server server, ChannelMessage message) {
        if (server != null) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            message.writeTo(out);

            NamespaceKey channel = new NamespaceKey(getName(), message.getChannelName());
            server.sendData(channel.getCombinedName(), out.toByteArray());
        }
    }

    public UserPreference getLoginSession(PendingConnection id) {
        return loginSessions.get(id);
    }

    public void startSession(PendingConnection id, UserPreference preferences) {
        loginSessions.put(id, preferences);
    }

    public UserPreference endSession(PendingConnection id) {
        return loginSessions.remove(id);
    }

    public SkinStorage getStorage() {
        return core.getStorage();
    }

    public ChangeSkinCore getCore() {
        return core;
    }

    @Override
    public boolean hasSkinPermission(CommandSender invoker, UUID uuid, boolean sendMessage) {
        if (invoker.hasPermission(getName().toLowerCase() + ".skin.whitelist." + uuid)) {
            return true;
        } else if (invoker.hasPermission(getName().toLowerCase() + ".skin.whitelist.*")) {
            if (invoker.hasPermission('-' + getName().toLowerCase() + ".skin.whitelist." + uuid)) {
                //blacklisted explicit
                if (sendMessage) {
                    sendMessage(invoker, "no-permission");
                }

                return false;
            }

            return true;
        }

        //disallow - not whitelisted or blacklisted
        if (sendMessage) {
            sendMessage(invoker, "no-permission");
        }

        return false;
    }
}
