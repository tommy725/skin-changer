package com.github.games647.changeskin.core;

import com.github.games647.changeskin.core.model.SkinData;
import com.github.games647.changeskin.core.model.mojang.auth.Account;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import org.slf4j.Logger;

public class ChangeSkinCore {

    public static final String SKIN_KEY = "textures";

    private final Map<String, String> localeMessages = Maps.newConcurrentMap();

    //this is thread-safe in order to save and load from different threads like the skin download
    private final Map<String, UUID> uuidCache = CommonUtil.buildCache(3 * 60 * 60, 1024 * 5);

    private final Map<String, Object> crackedNames = CommonUtil.buildCache(3 * 60 * 60, 1024 * 5);

    private final PlatformPlugin<?> plugin;
    private final List<SkinData> defaultSkins = Lists.newArrayList();
    private final List<Account> uploadAccounts = Lists.newArrayList();
    private final MojangAuthApi authApi;

    private MojangSkinApi skinApi;
    private Configuration config;
    private SkinStorage storage;
    private Map<UUID, Object> cooldowns;
    private int autoUpdateDiff;

    public ChangeSkinCore(PlatformPlugin<?> plugin) {
        this.plugin = plugin;
        this.authApi = new MojangAuthApi(plugin.getLog());
    }

    public void load() {
        saveDefaultFile("messages.yml");
        saveDefaultFile("config.yml");

        try {
            config = loadFile("config.yml");
            int rateLimit = config.getInt("mojang-request-limit");

            int cooldown = config.getInt("cooldown");
            if (cooldown <= 0) {
                cooldown = 1;
            }

            cooldowns = CommonUtil.buildCache(cooldown, -1);
            autoUpdateDiff = config.getInt("auto-skin-update") * 60 * 1_000;
            List<HostAndPort> proxies = config.getStringList("proxies")
                    .stream().map(HostAndPort::fromString).collect(Collectors.toList());
            skinApi = new MojangSkinApi(plugin.getLog(), rateLimit, proxies);

            loadDefaultSkins(config.getStringList("default-skins"));
            loadAccounts(config.getStringList("upload-accounts"));

            Configuration configStorage = config.getSection("storage");

            String driver = configStorage.getString("driver");
            String host = configStorage.get("host", "");
            int port = configStorage.get("port", 3306);
            String database = configStorage.getString("database");

            String user = configStorage.get("username", "");
            String password = configStorage.get("password", "");

            boolean useSSL = configStorage.get("useSSL", false);
            this.storage = new SkinStorage(this, driver, host, port, database, user, password, useSSL);
            try {
                this.storage.createTables();
            } catch (Exception ex) {
                getLogger().error("Failed to setup database. ", ex);
                return;
            }

            Configuration messages = loadFile("messages.yml");

            messages.getKeys()
                    .stream()
                    .filter(key -> messages.get(key) != null)
                    .collect(Collectors.toMap(Function.identity(), messages::get))
                    .forEach((key, message) -> {
                        String colored = CommonUtil.translateColorCodes((String) message);
                        if (!colored.isEmpty()) {
                            localeMessages.put(key, colored);
                        }
                    });
        } catch (IOException ioEx) {
            plugin.getLog().info("Failed to load yaml files", ioEx);
        }
    }

    public Logger getLogger() {
        return plugin.getLog();
    }

    public PlatformPlugin<?> getPlugin() {
        return plugin;
    }

    public Map<String, UUID> getUuidCache() {
        return uuidCache;
    }

    public Map<String, Object> getCrackedNames() {
        return crackedNames;
    }

    public String getMessage(String key) {
        return localeMessages.get(key);
    }

    public List<SkinData> getDefaultSkins() {
        return defaultSkins;
    }

    public SkinData checkAutoUpdate(SkinData oldSkin) {
        if (oldSkin == null) {
            return null;
        }

        if (autoUpdateDiff > 0 && System.currentTimeMillis() - oldSkin.getTimestamp() > autoUpdateDiff) {
            Optional<SkinData> updatedSkin = skinApi.downloadSkin(oldSkin.getUuid());
            if (updatedSkin.isPresent() && !Objects.equals(updatedSkin.get(), oldSkin)) {
                return updatedSkin.get();
            }
        }

        return oldSkin;
    }

    private Configuration loadFile(String fileName) throws IOException {
        Configuration defaults;

        ConfigurationProvider configProvider = ConfigurationProvider.getProvider(YamlConfiguration.class);
        try (InputStream defaultStream = getClass().getClassLoader().getResourceAsStream(fileName)) {
            defaults = configProvider.load(defaultStream);
        }

        File file = new File(plugin.getDataFolder(), fileName);
        return configProvider.load(file, defaults);
    }

    private void saveDefaultFile(String fileName) {
        Path dataFolder = plugin.getDataFolder().toPath();

        try {
            Files.createDirectories(dataFolder);

            Path configFile = dataFolder.resolve(fileName);
            if (Files.notExists(configFile)) {
                try (InputStream defaultStream = getClass().getClassLoader().getResourceAsStream(fileName)) {
                    Files.copy(defaultStream, configFile);
                }
            }
        } catch (IOException ioExc) {
            plugin.getLog().error("Cannot create plugin folder " + dataFolder, ioExc);
        }
    }

    private void loadDefaultSkins(Iterable<String> defaults) {
        for (String uuidString : defaults) {
            UUID ownerUUID = UUID.fromString(uuidString);
            SkinData skinData = storage.getSkin(ownerUUID);
            if (skinData == null) {
                Optional<SkinData> optSkin = skinApi.downloadSkin(ownerUUID);
                if (optSkin.isPresent()) {
                    skinData = optSkin.get();
                    uuidCache.put(skinData.getName(), skinData.getUuid());
                    storage.save(skinData);
                }
            }

            defaultSkins.add(skinData);
        }
    }

    private void loadAccounts(Iterable<String> accounts) {
        for (String line : accounts) {
            String email = line.split(":")[0];
            String password = line.split(":")[1];

            authApi.authenticate(email, password).ifPresent(account -> {
                plugin.getLog().info("Authenticated user {}", account.getProfile().getId());
                uploadAccounts.add(account);
            });
        }
    }

    public void onDisable() {
        defaultSkins.clear();
        uuidCache.clear();
    }

    public MojangSkinApi getSkinApi() {
        return skinApi;
    }

    public MojangAuthApi getAuthApi() {
        return authApi;
    }

    public SkinStorage getStorage() {
        return storage;
    }

    public Configuration getConfig() {
        return config;
    }

    public void addCooldown(UUID invoker) {
        cooldowns.put(invoker, new Object());
    }

    public boolean isCooldown(UUID invoker) {
        return cooldowns.containsKey(invoker);
    }

    public List<Account> getUploadAccounts() {
        return uploadAccounts;
    }
}
