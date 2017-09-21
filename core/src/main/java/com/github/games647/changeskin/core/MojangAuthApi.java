package com.github.games647.changeskin.core;

import com.github.games647.changeskin.core.model.mojang.auth.Account;
import com.github.games647.changeskin.core.model.mojang.auth.AuthenticationRequest;
import com.github.games647.changeskin.core.model.mojang.auth.AuthenticationResponse;
import com.google.common.base.Charsets;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MojangAuthApi {

    private static final String CHANGE_SKIN_URL = "https://api.mojang.com/user/profile/<uuid>/skin";
    private static final String AUTH_URL = "https://authserver.mojang.com/authenticate";
    private static final String OLD_SKIN_URL = "https://skins.minecraft.net/MinecraftSkins/<playerName>.png";

    private final Gson gson = new Gson();
    private final Logger logger;

    public MojangAuthApi(Logger logger) {
        this.logger = logger;
    }

    public Account authenticate(String email, String password) {
        try {
            HttpURLConnection httpConnection = ChangeSkinCore.getConnection(AUTH_URL);
            httpConnection.setRequestMethod("POST");
            httpConnection.setDoOutput(true);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(httpConnection.getOutputStream()))) {
                writer.append(gson.toJson(new AuthenticationRequest(email, password)));
                writer.flush();
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(httpConnection.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !"null".equals(line)) {
                    AuthenticationResponse authResponse = gson.fromJson(line, AuthenticationResponse.class);
                    return new Account(authResponse.getSelectedProfile(), authResponse.getAccessToken());
                }
            }
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Tried converting player name to uuid", ex);
        }

        return null;
    }

    public void changeSkin(UUID ownerId, UUID accessToken, String sourceUrl, boolean slimModel) {
        String url = CHANGE_SKIN_URL.replace("<uuid>", ownerId.toString().replace("-", ""));

        try {
            HttpURLConnection httpConnection = ChangeSkinCore.getConnection(url);
            httpConnection.setRequestMethod("POST");
            httpConnection.setDoOutput(true);

            httpConnection.addRequestProperty("Authorization", "Bearer " + accessToken.toString().replace("-", ""));

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(httpConnection.getOutputStream()))) {
                if (slimModel) {
                    writer.write("model=" + URLEncoder.encode("slim", Charsets.UTF_8.name()));
                } else {
                    writer.write("model=");
                }

                writer.write("&url=" + URLEncoder.encode(sourceUrl, Charsets.UTF_8.name()));
                writer.flush();
            }

            httpConnection.connect();
            logger.log(Level.FINE, "Response code for uploading {0}", httpConnection.getResponseCode());

        } catch (IOException ioEx) {
            logger.log(Level.SEVERE, "Tried downloading skin data from Mojang", ioEx);
        }

    }

    public String getSkinUrl(String playerName) {
        String url = OLD_SKIN_URL.replace("<playerName>", playerName);

        try {
            HttpURLConnection httpConnection = ChangeSkinCore.getConnection(url);
            //we only need the new url not the actual content
            httpConnection.setInstanceFollowRedirects(false);
            httpConnection.connect();

            if (httpConnection.getResponseCode() != 301) {
                logger.log(Level.SEVERE,
                        "Invalid response from the skin server Response Code: {0}", httpConnection.getResponseCode());
                return "";
            }

            //contains the actual skin storage url which will never be deleted and is unique
            return httpConnection.getHeaderField("Location");
        } catch (IOException ioEx) {
            logger.log(Level.SEVERE, "Tried looking for the old skin url", ioEx);
        }

        return "";
    }
}
