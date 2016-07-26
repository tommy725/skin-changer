package com.github.games647.changeskin.core;

import com.github.games647.changeskin.core.model.ApiPropertiesModel;
import com.github.games647.changeskin.core.model.McApiProfile;
import com.github.games647.changeskin.core.model.PlayerProfile;
import com.github.games647.changeskin.core.model.RawPropertiesModel;
import com.github.games647.changeskin.core.model.SkinData;
import com.github.games647.changeskin.core.model.mojang.PropertiesModel;
import com.github.games647.changeskin.core.model.mojang.TexturesModel;
import com.google.common.io.CharStreams;
import com.google.common.io.Closeables;
import com.google.common.io.Closer;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MojangSkinApi {

    private static final String SKIN_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String MCAPI_SKIN_URL = "https://mcapi.de/api/user/";
    private static final String CHANGE_SKIN_URL = "https://api.mojang.com/user/profile/<uuid>/skin";

    private static final String UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String MCAPI_UUID_URL = "https://mcapi.ca/uuid/player/";

    private static final String VALID_USERNAME = "^\\w{2,16}$";

    private static final int RATE_LIMIT_ID = 429;
    private static final int TIMEOUT = 3000;
    private static final String USER_AGENT = "ChangeSkin-Bukkit-Plugin";

    private final Gson gson = new Gson();

    private final ConcurrentMap<Object, Object> requests;
    private final Logger logger;
    private final int rateLimit;
    private final boolean mojangDownload;

    private long lastRateLimit;

    public MojangSkinApi(ConcurrentMap<Object, Object> requests, Logger logger, int rateLimit, boolean mojangDownload) {
        this.requests = requests;
        this.rateLimit = rateLimit;
        this.logger = logger;
        this.mojangDownload = mojangDownload;
    }

    public UUID getUUID(String playerName) throws NotPremiumException, RateLimitException {
        logger.log(Level.FINE, "Making UUID->Name request for {0}", playerName);
        if (!playerName.matches(VALID_USERNAME)) {
            return null;
        }

        if (requests.size() >= rateLimit || System.currentTimeMillis() - lastRateLimit < 1_000 * 60 * 10) {
//            logger.fine("STILL WAITING FOR RATE_LIMIT - TRYING SECOND API");
            return getUUIDFromAPI(playerName);
        }

        Closer closer = Closer.create();
        try {
            requests.put(new Object(), new Object());
            HttpURLConnection httpConnection = getConnection(UUID_URL + playerName);
            
            if (httpConnection.getResponseCode() == HttpURLConnection.HTTP_NO_CONTENT) {
                throw new NotPremiumException(playerName);
            } else if (httpConnection.getResponseCode() == RATE_LIMIT_ID) {
                logger.info("RATE_LIMIT REACHED - TRYING THIRD-PARTY API");
                lastRateLimit = System.currentTimeMillis();
                return getUUIDFromAPI(playerName);
            }

            InputStreamReader inputReader = closer.register(new InputStreamReader(httpConnection.getInputStream()));
            BufferedReader reader = closer.register(new BufferedReader(inputReader));
            String line = reader.readLine();
            if (line != null && !line.equals("null")) {
                PlayerProfile playerProfile = gson.fromJson(line, PlayerProfile.class);
                String id = playerProfile.getId();
                return ChangeSkinCore.parseId(id);
            }
        } catch (IOException | JsonParseException ex) {
            logger.log(Level.SEVERE, "Tried converting player name to uuid", ex);
        } finally {
            try {
                closer.close();
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Error closing connection", ex);
            }
        }

        return null;
    }

    public UUID getUUIDFromAPI(String playerName) throws NotPremiumException, RateLimitException {
        InputStreamReader inputReader = null;
        try {
            HttpURLConnection httpConnection = getConnection(MCAPI_UUID_URL + playerName);

            inputReader = new InputStreamReader(httpConnection.getInputStream());
            String line = CharStreams.toString(inputReader);
            if (line != null && !line.equals("null")) {
                PlayerProfile playerProfile = gson.fromJson(line, PlayerProfile[].class)[0];
                String id = playerProfile.getId();
                return ChangeSkinCore.parseId(id);
            }
        } catch (IOException | JsonParseException ex) {
            logger.log(Level.SEVERE, "Tried converting player name to uuid from third-party api", ex);
        } finally {
            Closeables.closeQuietly(inputReader);
        }

        return null;
    }

    public SkinData downloadSkin(UUID ownerUUID) {
        if (mojangDownload) {
            return downloadSkinFromApi(ownerUUID);
        }

        //unsigned is needed in order to receive the signature
        String uuidString = ownerUUID.toString().replace("-", "") + "?unsigned=false";
        try {
            HttpURLConnection httpConnection = getConnection(SKIN_URL + uuidString);

            BufferedReader reader = new BufferedReader(new InputStreamReader(httpConnection.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.equals("null")) {
                TexturesModel texturesModel = gson.fromJson(line, TexturesModel.class);

                PropertiesModel[] properties = texturesModel.getProperties();
                if (properties != null && properties.length > 0) {
                    PropertiesModel propertiesModel = properties[0];

                    //base64 encoded skin data
                    String encodedSkin = propertiesModel.getValue();
                    String signature = propertiesModel.getSignature();

                    SkinData skinData = new SkinData(encodedSkin, signature);
                    return skinData;
                }
            }
        } catch (IOException | JsonParseException ex) {
            logger.log(Level.SEVERE, "Tried downloading skin data from Mojang", ex);
        }

        return null;
    }

    public SkinData downloadSkinFromApi(UUID ownerUUID) {
        //unsigned is needed in order to receive the signature
        String uuidStrip = ownerUUID.toString().replace("-", "");
        try {
            HttpURLConnection httpConnection = getConnection(MCAPI_SKIN_URL + uuidStrip);

            BufferedReader reader = new BufferedReader(new InputStreamReader(httpConnection.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.equals("null")) {
                McApiProfile profile = gson.fromJson(line, McApiProfile.class);

                ApiPropertiesModel properties = profile.getProperties();
                if (properties != null && properties.getRaw().length > 0) {
                    RawPropertiesModel propertiesModel = properties.getRaw()[0];

                    //base64 encoded skin data
                    String encodedSkin = propertiesModel.getValue();
                    String signature = propertiesModel.getSignature();

                    SkinData skinData = new SkinData(encodedSkin, signature);
                    return skinData;
                }
            }
        } catch (IOException | JsonParseException ex) {
            logger.log(Level.SEVERE, "Tried downloading skin data from Mojang", ex);
        }

        return null;
    }

    private HttpURLConnection getConnection(String url) throws IOException {
        HttpURLConnection httpConnection = (HttpURLConnection) new URL(url).openConnection();
        httpConnection.setConnectTimeout(TIMEOUT);
        httpConnection.setReadTimeout(2 * TIMEOUT);
        httpConnection.setRequestProperty("Content-Type", "application/json");
        httpConnection.setRequestProperty("User-Agent", USER_AGENT);
        return httpConnection;
    }
}
