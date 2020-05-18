package com.github.games647.changeskin.core;

import com.github.games647.changeskin.core.model.UUIDTypeAdapter;
import com.github.games647.changeskin.core.model.UserPreference;
import com.github.games647.changeskin.core.model.skin.SkinModel;
import com.github.games647.changeskin.core.model.skin.TextureModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;

import static com.github.games647.changeskin.core.model.skin.TextureType.CAPE;
import static com.github.games647.changeskin.core.model.skin.TextureType.SKIN;
import static java.sql.Statement.RETURN_GENERATED_KEYS;

public class SkinStorage {

    private static final String USER_TABLE = "preferences";
    private static final String DATA_TABLE = "skinData";

    private final Logger logger;
    private final HikariDataSource dataSource;

    public SkinStorage(ChangeSkinCore core, String driver, String host, int port, String database
            , String user, String pass, boolean useSSL) {
        this.logger = core.getLogger();

        HikariConfig config = new HikariConfig();
        config.setPoolName(core.getPlugin().getName());

        config.setUsername(user);
        config.setPassword(pass);
        config.setDriverClassName(driver);

        ThreadFactory threadFactory = core.getPlugin().getThreadFactory();
        if (threadFactory != null) {
            config.setThreadFactory(threadFactory);
        }

        Properties properties = new Properties();

        String jdbcUrl = "jdbc:";
        if (driver.contains("sqlite")) {
            String folderPath = core.getPlugin().getPluginFolder().toAbsolutePath().toString();
            database = database.replace("{pluginDir}", folderPath);

            jdbcUrl += "sqlite://" + database;
            config.setConnectionTestQuery("SELECT 1");
            config.setMaximumPoolSize(1);

            //a try to fix https://www.spigotmc.org/threads/fastlogin.101192/page-26#post-1874647
            properties.setProperty("date_string_format", "yyyy-MM-dd HH:mm:ss");
        } else {
            jdbcUrl += "mysql://" + host + ':' + port + '/' + database;
            properties.setProperty("useSSL", String.valueOf(useSSL));
            // enable MySQL specific optimizations
            // default prepStmtCacheSize 25 - amount of cached statements - enough for us
            // default prepStmtCacheSqlLimit 256 - length of SQL - our queries are not longer
            // disabled by default - will return the same prepared statement instance
            config.addDataSourceProperty("cachePrepStmts", true);
            // default false - available in newer versions caches the statements server-side
            config.addDataSourceProperty("useServerPrepStmts", true);
        }

        config.setJdbcUrl(jdbcUrl);
        config.setDataSourceProperties(properties);
        this.dataSource = new HikariDataSource(config);
    }

    public void createTables() throws SQLException {
        // TODO: Add transaction mode to prevent half table setup
        try (
                InputStream in = getClass().getResourceAsStream("/create.sql");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
        ) {
            try (
                    Connection con = dataSource.getConnection();
                    Statement stmt = con.createStatement()
            ) {
                StringBuilder builder = new StringBuilder();

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.startsWith("-- ")) continue;

                    builder.append(line);
                    if (line.endsWith(";")) {
                        String sql = builder.toString();
                        if (dataSource.getJdbcUrl().contains("sqlite")) {
                            sql = sql.replace("AUTO_INCREMENT", "AUTOINCREMENT");
                        }

                        stmt.addBatch(sql);
                        builder = new StringBuilder();
                    }
                }

                stmt.executeBatch();
            }
        } catch (IOException ioEx) {
            logger.error("Failed to load migration file", ioEx);
        }
    }

    public UserPreference getPreferences(UUID uuid) {
        try (Connection con = dataSource.getConnection();
             PreparedStatement stmt = con.prepareStatement("SELECT `SkinId`, `Timestamp`, "
                     + DATA_TABLE + ".UUID, `Name`, `SlimModel`, `SkinUrl`, `CapeUrl`, `Signature`, " + USER_TABLE + ".*"
                     + " FROM " + USER_TABLE
                     + " LEFT JOIN " + DATA_TABLE + " ON " + USER_TABLE + ".TargetSkin=" + DATA_TABLE + ".SkinID"
                     + " WHERE " + USER_TABLE + ".UUID=? LIMIT 1")) {
            stmt.setString(1, UUIDTypeAdapter.toMojangId(uuid));

            try (ResultSet resultSet = stmt.executeQuery()) {
                if (resultSet.next()) {
                    int prefId = resultSet.getInt(9);

                    SkinModel skinData = null;
                    if (resultSet.getObject(1) != null) {
                        skinData = parseSkinData(resultSet);
                    }

                    boolean keepSkin = resultSet.getBoolean(12);
                    return new UserPreference(prefId, uuid, skinData, keepSkin);
                } else {
                    return new UserPreference(uuid);
                }
            }
        } catch (SQLException sqlEx) {
            logger.error("Failed to query preferences {}", uuid, sqlEx);
        }

        return null;
    }

    public SkinModel getSkin(int targetSkinId) {
        try (Connection con = dataSource.getConnection();
             PreparedStatement stmt = con.prepareStatement("SELECT SkinId, Timestamp, UUID, Name, " +
                     "SlimModel, SkinUrl, CapeUrl, Signature FROM " + DATA_TABLE + " WHERE SkinID=?")) {
            stmt.setInt(1, targetSkinId);

            try (ResultSet resultSet = stmt.executeQuery()) {
                if (resultSet.next()) {
                    return parseSkinData(resultSet);
                }
            }
        } catch (SQLException sqlEx) {
            logger.error("Failed to query skin data from row id: {}", targetSkinId, sqlEx);
        }

        return null;
    }

    public SkinModel getSkin(UUID skinUUID) {
        try (Connection con = dataSource.getConnection();
             PreparedStatement stmt = con.prepareStatement("SELECT SkinId, Timestamp, UUID, Name, " +
                     "SlimModel, SkinUrl, CapeUrl, Signature FROM " + DATA_TABLE
                     + " WHERE UUID=? ORDER BY Timestamp DESC LIMIT 1")) {
            stmt.setString(1, UUIDTypeAdapter.toMojangId(skinUUID));

            try (ResultSet resultSet = stmt.executeQuery()) {
                if (resultSet.next()) {
                    return parseSkinData(resultSet);
                }
            }
        } catch (SQLException sqlEx) {
            logger.error("Failed to query skin data from uuid: {}", skinUUID, sqlEx);
        }

        return null;
    }

    public void save(UserPreference preferences) {
        SkinModel targetSkin = preferences.getTargetSkin().orElse(null);
        if (targetSkin != null && !targetSkin.isSaved()) {
            throw new IllegalArgumentException("Tried saving preferences without skin");
        }

        preferences.getSaveLock().lock();
        try (Connection con = dataSource.getConnection()) {
            if (preferences.isSaved()) {
                if (targetSkin == null) {
                    try (PreparedStatement stmt = con.prepareStatement("DELETE FROM " + USER_TABLE
                            + " WHERE UserID=?")) {
                        stmt.setInt(1, preferences.getRowId());
                        stmt.executeUpdate();
                    }
                } else {
                    try (PreparedStatement stmt = con.prepareStatement("UPDATE " + USER_TABLE
                            + " SET TargetSkin=? WHERE UserID=?")) {
                        stmt.setInt(1, targetSkin.getRowId());
                        stmt.setInt(2, preferences.getRowId());
                        stmt.executeUpdate();
                    }
                }
            } else {
                if (targetSkin == null) {
                    //don't save empty preferences
                    return;
                }

                String insertQuery = "INSERT INTO " + USER_TABLE + " (UUID, TargetSkin, KeepSkin) " +
                        "VALUES (?, ?, ?)";

                try (PreparedStatement stmt = con.prepareStatement(insertQuery, RETURN_GENERATED_KEYS)) {
                    stmt.setString(1, UUIDTypeAdapter.toMojangId(preferences.getUuid()));
                    stmt.setInt(2, targetSkin.getRowId());
                    stmt.setBoolean(3, preferences.isKeepSkin());

                    stmt.executeUpdate();

                    try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                        if (generatedKeys != null && generatedKeys.next()) {
                            preferences.setRowId(generatedKeys.getInt(1));
                        }
                    }
                }
            }
        } catch (SQLException sqlEx) {
            logger.error("Failed to save preferences for: {}", preferences, sqlEx);
        } finally {
            preferences.getSaveLock().unlock();
        }
    }

    public boolean save(SkinModel skinData) {
        if (skinData == null) {
            return false;
        }

        skinData.getSaveLock().lock();
        try {
            if (skinData.isSaved()) {
                //skin already saved
                return true;
            }

            TextureModel skinTexture = skinData.getTextures().get(SKIN);
            String skinUrl = "";
            boolean slimModel = false;
            if (skinTexture != null) {
                skinUrl = skinTexture.getShortUrl();
                slimModel = skinTexture.isSlim();
            }

            TextureModel capeTexture = skinData.getTextures().get(CAPE);
            String capeUrl = "";
            if (capeTexture != null) {
                capeUrl = capeTexture.getShortUrl();
            }

            try (Connection con = dataSource.getConnection();
                 PreparedStatement stmt = con.prepareStatement("INSERT INTO " + DATA_TABLE
                         + " (`Timestamp`, `UUID`, `Name`, `SlimModel`, `SkinURL`, `CapeURL`, `Signature`) VALUES"
                         + " (?, ?, ?, ?, ?, ?, ?)", RETURN_GENERATED_KEYS)) {

                stmt.setLong(1, skinData.getTimestamp());
                stmt.setString(2, UUIDTypeAdapter.toMojangId(skinData.getProfileId()));
                stmt.setString(3, skinData.getProfileName());
                stmt.setBoolean(4, slimModel);
                stmt.setString(5, skinUrl);
                stmt.setString(6, capeUrl);
                stmt.setBytes(7, Base64.getDecoder().decode(skinData.getSignature()));

                stmt.executeUpdate();

                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys != null && generatedKeys.next()) {
                        skinData.setRowId(generatedKeys.getInt(1));
                        return true;
                    }
                }
            }
        } catch (SQLException sqlEx) {
            logger.error("Failed to query skin data: {}", skinData, sqlEx);
        } finally {
            skinData.getSaveLock().unlock();
        }

        return false;
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private SkinModel parseSkinData(ResultSet resultSet) throws SQLException {
        int skinId = resultSet.getInt(1);
        long timestamp = resultSet.getLong(2);
        UUID uuid = UUIDTypeAdapter.parseId(resultSet.getString(3));
        String name = resultSet.getString(4);

        boolean slimModel = resultSet.getBoolean(5);

        String skinUrl = resultSet.getString(6);
        String capeUrl = resultSet.getString(7);

        byte[] signature = resultSet.getBytes(8);
        return new SkinModel(skinId, timestamp, uuid, name, slimModel, skinUrl, capeUrl, signature);
    }
}
