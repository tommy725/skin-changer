package com.github.games647.changeskin.core.model.skin;

import com.github.games647.changeskin.core.model.UUIDTypeAdapter;
import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

public class SkinModel {

    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new UUIDTypeAdapter()).create();

    private transient int skinId;
    private transient String encodedValue;
    private transient String encodedSignature;

    //the order of these fields are relevant
    private final long timestamp;
    private final UUID profileId;
    private final String profileName;

    private final boolean signatureRequired = true;
    private final Map<TextureType, TextureModel> textures = Maps.newEnumMap(TextureType.class);

    public SkinModel(int skinId, long timestamp, UUID uuid, String name
            , boolean slimModel, String skinURL, String capeURL, byte[] signature) {
        this.skinId = skinId;

        this.timestamp = timestamp;
        this.profileId = uuid;
        this.profileName = name;
        
        if (skinURL != null && !skinURL.isEmpty()) {
            textures.put(TextureType.SKIN, new TextureModel(skinURL, slimModel));
        }

        if (capeURL != null && !capeURL.isEmpty()) {
            textures.put(TextureType.CAPE, new TextureModel(capeURL));
        }
        
        this.encodedSignature = Base64.getEncoder().encodeToString(signature);
        this.encodedValue = serializeData();
    }

    public static SkinModel createSkinFromEncoded(String encodedData, String signature) {
        byte[] data = Base64.getDecoder().decode(encodedData);
        String rawJson = new String(data, StandardCharsets.UTF_8);

        SkinModel skinModel = gson.fromJson(rawJson, SkinModel.class);
        skinModel.setSkinId(-1);
        skinModel.encodedSignature = signature;
        skinModel.encodedValue = encodedData;
        return skinModel;
    }

    public synchronized int getSkinId() {
        return skinId;
    }

    public synchronized void setSkinId(int skinId) {
        this.skinId = skinId;
    }

    public String getEncodedValue() {
        return encodedValue;
    }

    public String getSignature() {
        return encodedSignature;
    }

    private void setEncodedSignature(String encodedSignature) {
        this.encodedSignature = encodedSignature;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public String getProfileName() {
        return profileName;
    }

    public Map<TextureType, TextureModel> getTextures() {
        return textures;
    }

    private String serializeData() {
        String json = gson.toJson(this);
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("skinId", skinId)
                .add("encodedValue", encodedValue)
                .add("encodedSignature", encodedSignature)
                .add("timestamp", timestamp)
                .add("profileId", profileId)
                .add("profileName", profileName)
                .add("signatureRequired", signatureRequired)
                .add("textures", textures)
                .toString();
    }
}
