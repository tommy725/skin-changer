package com.github.games647.changeskin;

import com.comphenix.protocol.utility.SafeCacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.io.BaseEncoding;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class Storage {

    private static final String PREFERENCES_TABLE = "preferences";
    private static final String DATA_TABLE = "skinData";

    private final String driver;
    private final String jdbcUrl;
    private final String username;
    private final String pass;

    private final ChangeSkin plugin;

    //this is thread-safe in order to save and load from different threads like the skin download
    private final ConcurrentMap<UUID, UserPreferences> preferencesCache = buildCache();
    private final ConcurrentMap<Integer, SkinData> skinCache = buildCache();
    private final ConcurrentMap<UUID, SkinData> skinUUIDCache = buildCache();

    public Storage(ChangeSkin plugin) {
        this.plugin = plugin;

        this.driver = plugin.getConfig().getString("storage.driver");
        String host = plugin.getConfig().getString("storage.host", "");
        int port = plugin.getConfig().getInt("storage.port", 3306);
        String database = plugin.getConfig().getString("storage.database").replace("{pluginDir}"
                , plugin.getDataFolder().getAbsolutePath());

        String url = "jdbc:";
        if (driver.contains("sqlite")) {
            url += "sqlite" + "://" + database;
        } else {
            url += "mysql" + "://" + host + ':' + port + '/' + database;
        }

        this.jdbcUrl = url;

        this.username = plugin.getConfig().getString("storage.username", "");
        this.pass = plugin.getConfig().getString("storage.password", "");
    }

    public void createTables() throws ClassNotFoundException, SQLException {
        //load the driver
        Class.forName(driver);

        Connection con = null;
        try {
            con = DriverManager.getConnection(jdbcUrl, username, pass);
            Statement statement = con.createStatement();
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + PREFERENCES_TABLE + " ("
                    + "`UserID` INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "`UUID` CHAR(36) NOT NULL, "
                    + "`TargetSkin` Integer NOT NULL, "
                    + "UNIQUE (`UUID`), "
                    + "FOREIGN KEY (`TargetSkin`) "
                    + "     REFERENCES " + DATA_TABLE + "(`SkinID`) "
                    + "     ON DELETE CASCADE "
                    + ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + DATA_TABLE + " ("
                    + "`SkinID` INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "`Timestamp` TIMESTAMP NOT NULL, "
                    + "`UUID` CHAR(36) NOT NULL, "
                    + "`Name` VARCHAR(16) NOT NULL, "
                    + "`SkinURL` VARCHAR(255) NOT NULL, "
                    + "`CapeURL` VARCHAR(255), "
                    + "`Signature` BINARY(512) NOT NULL"
                    //SQLite doesn't support this on a create table statement
//                    + "INDEX(`UUID`)"
                    + ")");
        } finally {
            closeQuietly(con);
        }
    }

    public ConcurrentMap<Integer, SkinData> getSkinCache() {
        return skinCache;
    }

    public ConcurrentMap<UUID, SkinData> getSkinUUIDCache() {
        return skinUUIDCache;
    }

    public UserPreferences getPreferences(UUID uuid, boolean fetch) {
        if (preferencesCache.containsKey(uuid)) {
            return preferencesCache.get(uuid);
        } else if (fetch) {
            Connection con = null;
            try {
                con = DriverManager.getConnection(jdbcUrl, username, pass);

                PreparedStatement statement = con.prepareStatement("SELECT TargetSkin FROM " + PREFERENCES_TABLE
                        + " WHERE UUID=? LIMIT 1");

                statement.setString(1, uuid.toString().replace("-", ""));
                ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    int targetSkinId = resultSet.getInt(1);
                    SkinData skinData = getSkin(targetSkinId, true);
                    return new UserPreferences(uuid, skinData);
                } else {
                    UserPreferences userPreferences = new UserPreferences(uuid);
                    preferencesCache.put(uuid, userPreferences);
                    return userPreferences;
                }
            } catch (SQLException sqlEx) {
                plugin.getLogger().log(Level.SEVERE, "Failed to query preferences", sqlEx);
            } finally {
                closeQuietly(con);
            }
        }

        return null;
    }

    public SkinData getSkin(int targetSkinId, boolean fetch) {
        if (skinCache.containsKey(targetSkinId)) {
            return skinCache.get(targetSkinId);
        } else if (fetch) {
            Connection con = null;
            try {
                con = DriverManager.getConnection(jdbcUrl, username, pass);

                PreparedStatement statement = con.prepareStatement("SELECT * FROM " + DATA_TABLE
                        + " WHERE SkinID=? LIMIT 1");

                statement.setInt(1, targetSkinId);
                ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    long timestamp = resultSet.getTimestamp(1).getTime();
                    UUID uuid = ChangeSkin.parseId(resultSet.getString(2));
                    String name = resultSet.getString(3);
                    String skinUrl = resultSet.getString(4);
                    String capeUrl = resultSet.getString(5);

                    String signature = BaseEncoding.base64().encode(resultSet.getBytes(1));
                    SkinData skinData = new SkinData(targetSkinId, timestamp, uuid, name, skinUrl, capeUrl, signature);
                    skinCache.put(targetSkinId, skinData);
                    skinUUIDCache.put(uuid, skinData);
                    return skinData;
                }
            } catch (SQLException sqlEx) {
                plugin.getLogger().log(Level.SEVERE, "Failed to query skin data", sqlEx);
            } finally {
                closeQuietly(con);
            }
        }

        return null;
    }

    public SkinData getSkin(UUID skinUUID, boolean fetch) {
        if (skinUUIDCache.containsKey(skinUUID)) {
            return skinUUIDCache.get(skinUUID);
        } else if (fetch) {
            Connection con = null;
            try {
                con = DriverManager.getConnection(jdbcUrl, username, pass);

                PreparedStatement statement = con.prepareStatement("SELECT * FROM " + DATA_TABLE
                        + " WHERE UUID=? LIMIT 1");

                statement.setString(1, skinUUID.toString().replace("-", ""));
                ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    int skinId = resultSet.getInt(1);
                    long timestamp = resultSet.getTimestamp(1).getTime();
                    UUID uuid = ChangeSkin.parseId(resultSet.getString(2));
                    String name = resultSet.getString(3);
                    String skinUrl = resultSet.getString(4);
                    String capeUrl = resultSet.getString(5);

                    String signature = BaseEncoding.base64().encode(resultSet.getBytes(1));
                    SkinData skinData = new SkinData(skinId, timestamp, uuid, name, skinUrl, capeUrl, signature);
                    skinCache.put(skinId, skinData);
                    skinUUIDCache.put(uuid, skinData);
                    return skinData;
                }
            } catch (SQLException sqlEx) {
                plugin.getLogger().log(Level.SEVERE, "Failed to query skin data", sqlEx);
            } finally {
                closeQuietly(con);
            }
        }

        return null;
    }

    public void save(UserPreferences preferences) {
        SkinData targetSkin = preferences.getTargetSkin();
        if (targetSkin == null || targetSkin.getSkinId() == -1) {
            plugin.getLogger().warning("Tried saving preferences without target skin. "
                    + "Please report this to the author");
            return;
        }

        Connection con = null;
        try {
            con = DriverManager.getConnection(jdbcUrl, username, pass);

            PreparedStatement statement = con.prepareStatement("INSERT INTO " + PREFERENCES_TABLE + " WHERE UUID=? "
                    + "(UUID, TargetSkin) VALUES (?, ?) ON DUPLICATE KEY UPDATE TargetSkin=?");
            statement.setString(1, preferences.getUuid().toString().replace("-", ""));
            statement.setInt(2, targetSkin.getSkinId());
            statement.setInt(3, targetSkin.getSkinId());

            statement.executeUpdate();
        } catch (SQLException sqlEx) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save preferences", sqlEx);
        } finally {
            closeQuietly(con);
        }
    }

    public void save(SkinData skinData) {
        if (skinData.getSkinId() != -1) {
            plugin.getLogger().warning("SkinId already set");
            return;
        }

        Connection con = null;
        try {
            con = DriverManager.getConnection(jdbcUrl, username, pass);

            PreparedStatement statement = con.prepareStatement("INSERT INTO " + DATA_TABLE
                    + " (Timestamp, UUID, Name, SkinURL, CapeURL, Signature) VALUES"
                    + " (?, ?, ?, ?, ?, ?)");

            statement.setTimestamp(1, new Timestamp(skinData.getTimestamp()));
            statement.setString(2, skinData.getUuid().toString().replace("-", ""));
            statement.setString(3, skinData.getName());
            statement.setString(4, skinData.getSkinURL());
            statement.setString(5, skinData.getCapeURL());
            statement.setBytes(6, BaseEncoding.base64().decode(skinData.getEncodedSignature()));
            statement.executeUpdate();
        } catch (SQLException sqlEx) {
            plugin.getLogger().log(Level.SEVERE, "Failed to query skin data", sqlEx);
        } finally {
            closeQuietly(con);
        }
    }

    public void close() {
        skinCache.clear();
        skinUUIDCache.clear();
        preferencesCache.clear();
    }

    private void closeQuietly(Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException sqlEx) {
                plugin.getLogger().log(Level.SEVERE, "Failed to close connection", sqlEx);
            }
        }
    }

    private <K, V> ConcurrentMap<K, V> buildCache() {
        return SafeCacheBuilder
                .<K, V>newBuilder()
                .maximumSize(1024 * 5)
                .expireAfterAccess(3, TimeUnit.HOURS)
                .build(new CacheLoader<K, V>() {
                    @Override
                    public V load(K key) throws Exception {
                        //A key should be inserted manually
                        throw new UnsupportedOperationException("Not supported yet.");
                    }
                });
    }
}
