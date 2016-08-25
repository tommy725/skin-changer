package com.github.games647.changeskin.sponge;

import com.github.games647.changeskin.core.ChangeSkinCore;
import com.github.games647.changeskin.core.SkinStorage;
import com.github.games647.changeskin.core.model.SkinData;
import com.github.games647.changeskin.sponge.commands.SelectCommand;
import com.github.games647.changeskin.sponge.commands.SetSkinCommand;
import com.github.games647.changeskin.sponge.commands.SkinInvalidateCommand;
import com.github.games647.changeskin.sponge.commands.SkinUploadCommand;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;

import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;

import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.command.CommandManager;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.network.ChannelBinding.RawDataChannel;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.profile.GameProfileCache;
import org.spongepowered.api.profile.property.ProfileProperty;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.serializer.TextSerializers;

@Plugin(id = "changeskin", name = "ChangeSkin", version = "2.3.2"
        , url = "https://github.com/games647/ChangeSkin"
        , description = "Sponge plugin to change your skin server side")
public class ChangeSkinSponge {

    @Inject
    @DefaultConfig(sharedRoot = false)
    //We will place more than one config there (i.e. H2/SQLite database)
    private File defaultConfigFile;

    private final Logger logger;
    private final Game game;
    private final PluginContainer pluginContainer;

    private ChangeSkinCore core;
    private ConfigurationNode rootNode;

    private RawDataChannel pluginChannel;

    @Inject
    public ChangeSkinSponge(Logger logger, Game game, PluginContainer pluginContainer) {
        this.logger = logger;
        this.game = game;
        this.pluginContainer = pluginContainer;
    }

    @Listener //load config and database
    public void onPreInit(GamePreInitializationEvent preInitEvent) {
        //deploy default config
        if (!defaultConfigFile.exists()) {
            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(defaultConfigFile);
                Resources.copy(getClass().getResource("/config.yml"), fileOutputStream);
            } catch (IOException ioEx) {
                logger.error("Error deploying default config", ioEx);
            } finally {
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException ex) {
                        //ignore
                    }
                }
            }
        }

        YAMLConfigurationLoader configLoader = YAMLConfigurationLoader.builder().setFile(defaultConfigFile).build();
        try {
            rootNode = configLoader.load();

            ConfigurationNode storageNode = rootNode.getNode("storage");
            String driver = storageNode.getNode("driver").getString();
            String host = storageNode.getNode("host").getString();
            int port = storageNode.getNode("port").getInt();
            String database = storageNode.getNode("database").getString();
            String user = storageNode.getNode("username").getString();
            String pass = storageNode.getNode("password").getString();

            int rateLimit = storageNode.getNode("mojang-request-limit").getInt();
            boolean mojangDownload = storageNode.getNode("independent-skin-downloading").getBoolean();

            java.util.logging.Logger pluginLogger = java.util.logging.Logger.getLogger("ChangeSkin");

            int cooldown = rootNode.getNode("cooldown").getInt();
            File parentFolder = defaultConfigFile.getParentFile();
            int updateDiff = rootNode.getNode("auto-skin-update").getInt();
            core = new ChangeSkinCore(pluginLogger, parentFolder, rateLimit, mojangDownload, cooldown, updateDiff);

            String pluginName = "ChangeSkin";
            ThreadFactory threadFactory = new ThreadFactoryBuilder()
                    .setNameFormat(pluginName + " Database Pool Thread #%1$d")
                    //Hikari create daemons by default
                    .setDaemon(true)
                    .build();

            SkinStorage storage = new SkinStorage(core, threadFactory, driver, host, port, database, user, pass);
            core.setStorage(storage);

            try {
                storage.createTables();
            } catch (Exception ex) {
                logger.error("Failed to setup database. Disabling plugin...", ex);
                return;
            }

            List<String> defaultSkins = Lists.newArrayList();
            for (ConfigurationNode node : rootNode.getNode("default-skins").getChildrenMap().values()) {
                defaultSkins.add(node.getString());
            }

            core.loadDefaultSkins(defaultSkins);
            loadLocale();
        } catch (IOException ioEx) {
            logger.error("Failed to load config", ioEx);
        }
    }

    @Listener //command and event register
    public void onInit(GameInitializationEvent initEvent) {
        CommandManager commandManager = game.getCommandManager();
        commandManager.register(this, CommandSpec.builder()
                .executor(new SelectCommand(this))
                .arguments(GenericArguments.string(Text.of("skinName")))
                .build(), "skin-select");

        commandManager.register(this, CommandSpec.builder()
                .executor(new SkinUploadCommand(this))
                .arguments(GenericArguments.string(Text.of("url")))
                .build(), "skin-upload");

        commandManager.register(this, CommandSpec.builder()
                .executor(new SetSkinCommand(this))
                .arguments(GenericArguments.string(Text.of("skin"))
                        , GenericArguments.optional(GenericArguments.string(Text.of("keep"))))
                .build(), "changeskin", "setskin");

        commandManager.register(this, CommandSpec.builder()
                .executor(new SkinInvalidateCommand(this))
                .build(), "skininvalidate", "skin-invalidate");

        game.getEventManager().registerListeners(this, new LoginListener(this));
        pluginChannel = game.getChannelRegistrar().createRawChannel(this, pluginContainer.getId());
        pluginChannel.addListener(new BungeeCordListener(this));
    }

    private void loadLocale() {
        File messageFile = new File(defaultConfigFile.getParentFile(), "messages.yml");

        if (!messageFile.exists()) {
            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(messageFile);
                Resources.copy(getClass().getResource("/messages.yml"), fileOutputStream);
            } catch (IOException ioEx) {
                logger.error("Error deploying default message", ioEx);
            } finally {
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException ex) {
                        //ignore
                    }
                }
            }
        }

        YAMLConfigurationLoader messageLoader = YAMLConfigurationLoader.builder().setFile(messageFile).build();
        try {
            URL jarConfigFile = this.getClass().getResource("/messages.yml");
            YAMLConfigurationLoader defaultLoader = YAMLConfigurationLoader.builder().setURL(jarConfigFile).build();
            ConfigurationNode defaultRoot = defaultLoader.load();
            for (ConfigurationNode node : defaultRoot.getChildrenMap().values()) {
                core.addMessage((String) node.getKey(), node.getString());
            }

            //overwrite the defaults
            ConfigurationNode messageNode = messageLoader.load();
            for (ConfigurationNode node : messageNode.getChildrenMap().values()) {
                core.addMessage((String) node.getKey(), node.getString());
            }
        } catch (IOException ioEx) {
            logger.error("Failed to load locale", ioEx);
        }
    }

    public ChangeSkinCore getCore() {
        return core;
    }

    public ConfigurationNode getRootNode() {
        return rootNode;
    }

    public boolean checkPermission(CommandSource invoker, UUID uuid, boolean sendMessage) {
        if (invoker.hasPermission(pluginContainer.getId().toLowerCase() + ".skin.whitelist." + uuid.toString())) {
            return true;
        }

        //disallow - not whitelisted or blacklisted
        if (sendMessage) {
            sendMessage(invoker, "no-permission");
        }

        return false;
    }

    public void sendMessage(CommandSource sender, String key) {
        if (core == null) {
            return;
        }

        String message = core.getMessage(key);
        if (message != null && sender != null) {
            sender.sendMessage(TextSerializers.LEGACY_FORMATTING_CODE.deserialize(message.replace('&', '§')));
        }
    }

    public void sendMessage(CommandSource sender, String key, Object... arguments) {
        if (core == null) {
            return;
        }

        String message = core.getMessage(key);
        if (message != null && sender != null) {
            String formated = MessageFormat.format(message, arguments);
            sender.sendMessage(TextSerializers.LEGACY_FORMATTING_CODE.deserialize(formated.replace('&', '§')));
        }
    }

    public void cacheSponge(SkinData skin) {
        //cache the request for Sponge
        GameProfileCache profileCache = game.getServer().getGameProfileManager().getCache();

        GameProfile gameProfile = GameProfile.of(skin.getUuid(), skin.getName());

        Optional<GameProfile> cachedProfile = profileCache.getById(skin.getUuid());
        if (cachedProfile.isPresent()) {
            gameProfile = cachedProfile.get();
        } else {
            profileCache.add(gameProfile);
        }

        ProfileProperty skinProperty = ProfileProperty
                .of(ChangeSkinCore.SKIN_KEY, skin.getEncodedData(), skin.getEncodedSignature());
        gameProfile.getPropertyMap().put(ChangeSkinCore.SKIN_KEY, skinProperty);
    }

    public PluginContainer getPluginContainer() {
        return pluginContainer;
    }

    public RawDataChannel getPluginChannel() {
        return pluginChannel;
    }

    public Game getGame() {
        return game;
    }

    public Logger getLogger() {
        return logger;
    }
}
