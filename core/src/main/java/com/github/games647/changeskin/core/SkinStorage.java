package com.github.games647.changeskin.core;

import com.github.games647.changeskin.core.model.UserPreference;
import com.github.games647.changeskin.core.model.skin.MetadataModel;
import com.github.games647.changeskin.core.model.skin.SkinModel;
import com.github.games647.changeskin.core.model.skin.TextureModel;
import com.github.games647.changeskin.core.model.skin.TextureType;
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

public class SkinStorage {

    private static final String PREFERENCES_TABLE = "preferences";
    private static final String DATA_TABLE = "skinData";

    private final ChangeSkinCore core;
    private final HikariDataSource dataSource;

    public SkinStorage(ChangeSkinCore core, String driver, String host, int port, String database
            , String user, String pass, boolean useSSL) {
        this.core = core;

        HikariConfig config = new HikariConfig();
        config.setUsername(user);
        config.setPassword(pass);
        config.setDriverClassName(driver);

        ThreadFactory threadFactory = core.getPlugin().getThreadFactory();
        if (threadFactory != null) {
            config.setThreadFactory(threadFactory);
        }

        //a try to fix https://www.spigotmc.org/threads/fastlogin.101192/page-26#post-1874647
        Properties properties = new Properties();
        properties.setProperty("date_string_format", "yyyy-MM-dd HH:mm:ss");
        properties.setProperty("useSSL", String.valueOf(useSSL));
        config.setDataSourceProperties(properties);

        String jdbcUrl = "jdbc:";
        if (driver.contains("sqlite")) {
            String folderPath = core.getPlugin().getPluginFolder().toAbsolutePath().toString();
            database = database.replace("{pluginDir}", folderPath);

            jdbcUrl += "sqlite://" + database;
            config.setConnectionTestQuery("SELECT 1");
            config.setMaximumPoolSize(1);
        } else {
            jdbcUrl += "mysql://" + host + ':' + port + '/' + database;
        }

        config.setJdbcUrl(jdbcUrl);
        this.dataSource = new HikariDataSource(config);
    }

    public void createTables() throws SQLException {
        try (InputStream in = getClass().getResourceAsStream("/create.sql");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             Connection con = dataSource.getConnection();
             Statement stmt = con.createStatement()) {
            StringBuilder builder = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;

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
        } catch (IOException ioEx) {
            core.getLogger().error("Failed to load migration file", ioEx);
        }
    }

    public UserPreference getPreferences(UUID uuid) {
        try (Connection con = dataSource.getConnection();
             PreparedStatement stmt = con.prepareStatement("SELECT SkinId, Timestamp, "
                     + DATA_TABLE + ".UUID, Name, SlimModel, SkinUrl, CapeUrl, Signature, " + PREFERENCES_TABLE + ".*"
                     + " FROM " + PREFERENCES_TABLE
                     + " LEFT JOIN " + DATA_TABLE + " ON " + PREFERENCES_TABLE + ".TargetSkin=" + DATA_TABLE + ".SkinID"
                     + " WHERE " + PREFERENCES_TABLE + ".UUID=? LIMIT 1")) {
            stmt.setString(1, CommonUtil.toMojangId(uuid));

            try (ResultSet resultSet = stmt.executeQuery()) {
                if (resultSet.next()) {
                    int prefId = resultSet.getInt(8);

                    SkinModel skinData = null;
                    if (resultSet.getObject(1)  != null) {
                        skinData = parseSkinData(resultSet);
                    }

                    boolean keepSkin = resultSet.getBoolean(12);
                    return new UserPreference(prefId, uuid, skinData, keepSkin);
                } else {
                    return new UserPreference(uuid);
                }
            }
        } catch (SQLException sqlEx) {
            core.getLogger().error("Failed to query preferences", sqlEx);
        }

        return null;
    }

    public SkinModel getSkin(int targetSkinId) {
        try (Connection con = dataSource.getConnection();
             PreparedStatement stmt = con.prepareStatement("SELECT SkinId, Timestamp, UUID, Name, " +
                     "SlimModel, SkinUrl, CapeUrl, Signature FROM " + DATA_TABLE + " WHERE SkinID=? LIMIT 1")) {
            stmt.setInt(1, targetSkinId);

            try (ResultSet resultSet = stmt.executeQuery()) {
                if (resultSet.next()) {
                    return parseSkinData(resultSet);
                }
            }
        } catch (SQLException sqlEx) {
            core.getLogger().error("Failed to query skin data from row id", sqlEx);
        }

        return null;
    }

    public SkinModel getSkin(UUID skinUUID) {
        try (Connection con = dataSource.getConnection();
             PreparedStatement stmt = con.prepareStatement("SELECT SkinId, Timestamp, UUID, Name, " +
                     "SlimModel, SkinUrl, CapeUrl, Signature FROM " + DATA_TABLE
                     + " WHERE UUID=? ORDER BY Timestamp DESC LIMIT 1")) {
            stmt.setString(1, CommonUtil.toMojangId(skinUUID));

            try (ResultSet resultSet = stmt.executeQuery()) {
                if (resultSet.next()) {
                    return parseSkinData(resultSet);
                }
            }
        } catch (SQLException sqlEx) {
            core.getLogger().error("Failed to query skin data from uuid", sqlEx);
        }

        return null;
    }

    public void save(UserPreference preferences) {
        SkinModel targetSkin = preferences.getTargetSkin();
        if (targetSkin != null && targetSkin.getSkinId() == -1) {
            throw new IllegalArgumentException("Tried saving preferences without skin");
        }

        try (Connection con = dataSource.getConnection()) {
            if (preferences.getId() == -1) {
                String insertQuery = "INSERT INTO " + PREFERENCES_TABLE + " (UUID, TargetSkin, KeepSkin) " +
                        "VALUES (?, ?, ?)";

                try (PreparedStatement stmt = con.prepareStatement(insertQuery)) {
                    stmt.setString(1, CommonUtil.toMojangId(preferences.getUuid()));
                    stmt.setInt(2, targetSkin == null ? -1 : targetSkin.getSkinId());
                    stmt.setBoolean(3, preferences.isKeepSkin());

                    stmt.executeUpdate();
                }
            } else {
                try (PreparedStatement stmt = con.prepareStatement("UPDATE " + PREFERENCES_TABLE
                        + " SET TargetSkin=? WHERE UUID=?")) {
                    stmt.setInt(1, targetSkin == null ? -1 : targetSkin.getSkinId());
                    stmt.setString(2, CommonUtil.toMojangId(preferences.getUuid()));
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException sqlEx) {
            core.getLogger().error("Failed to save preferences", sqlEx);
        }
    }

    public boolean save(SkinModel skinData) {
        if (skinData == null) {
            return false;
        }

        if (skinData.getSkinId() != -1) {
            //skin already saved
            return true;
        }

        TextureModel skinTexture = skinData.getTextures().get(TextureType.SKIN);
        String skinUrl = "";
        boolean slimModel = false;
        if (skinTexture != null) {
            skinUrl = skinTexture.getShortUrl();
            MetadataModel metadata = skinTexture.getMetadata();
            if (metadata != null) {
                slimModel = true;
            }
        }

        TextureModel capeTexture = skinData.getTextures().get(TextureType.CAPE);
        String capeUrl = "";
        if (capeTexture != null) {
            capeUrl = capeTexture.getShortUrl();
        }

        try (Connection con = dataSource.getConnection();
             PreparedStatement stmt = con.prepareStatement("INSERT INTO " + DATA_TABLE
                     + " (Timestamp, UUID, Name, SlimModel, SkinURL, CapeURL, Signature) VALUES"
                     + " (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {

            stmt.setLong(1, skinData.getTimestamp());
            stmt.setString(2, CommonUtil.toMojangId(skinData.getProfileId()));
            stmt.setString(3, skinData.getProfileName());
            stmt.setBoolean(4, slimModel);
            stmt.setString(5, skinUrl);
            stmt.setString(6, capeUrl);
            stmt.setBytes(7, Base64.getDecoder().decode(skinData.getSignature()));

            stmt.executeUpdate();

            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys != null && generatedKeys.next()) {
                    skinData.setSkinId(generatedKeys.getInt(1));
                    return true;
                }
            }
        } catch (SQLException sqlEx) {
            core.getLogger().error("Failed to query skin data", sqlEx);
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
        UUID uuid = CommonUtil.parseId(resultSet.getString(3));
        String name = resultSet.getString(4);

        boolean slimModel = resultSet.getBoolean(5);

        String skinUrl = resultSet.getString(6);
        String capeUrl = resultSet.getString(7);

        byte[] signature = resultSet.getBytes(8);
        return new SkinModel(skinId, timestamp, uuid, name, slimModel, skinUrl, capeUrl, signature);
    }
}
