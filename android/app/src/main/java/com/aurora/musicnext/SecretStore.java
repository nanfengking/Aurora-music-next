package com.aurora.musicnext;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecretStore {
    private static final Object LOCK = new Object();
    static final class Connection {
        final String root, authorization, revision;
        Connection(String root, String authorization) { this(root, authorization, ""); }
        Connection(String root, String authorization, String revision) { this.root = root; this.authorization = authorization; this.revision = revision; }
        String authorizationFor(String address) {
            ServerScope.requireInside(root, address);
            return authorization;
        }
    }
    private static final String ALIAS = "aurora_webdav_v1";
    private final SharedPreferences preferences;
    private final NativePreferences nativePreferences;

    SecretStore(Context context) {
        preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        nativePreferences = NativePreferences.forApplication(context);
    }

    String url() { return preferences.getString("url", ""); }
    String username() { return preferences.getString("username", ""); }
    boolean wifiOnly() { return (Boolean) nativePreferences.requireReady().values.get("wifiOnly"); }
    com.google.common.util.concurrent.ListenableFuture<NativePreferenceRecord> wifiOnly(boolean enabled) {
        try { return nativePreferences.update(new org.json.JSONObject().put("wifiOnly", enabled)); }
        catch (org.json.JSONException invalid) { return com.google.common.util.concurrent.Futures.immediateFailedFuture(invalid); }
    }

    void save(String url, String username, String password) throws Exception {
        synchronized (LOCK) {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        if (!preferences.edit().putString("url", url).putString("username", username)
            .putString("revision", java.util.UUID.randomUUID().toString())
            .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString("password", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
            throw new java.io.IOException("无法保存 WebDAV 连接");
        }
    }

    String authorization() throws Exception {
        synchronized (LOCK) {
        String encrypted = preferences.getString("password", "");
        if (encrypted.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
            Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)));
        String password = new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        return "Basic " + Base64.encodeToString((username() + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
    }

    Connection connection() throws Exception {
        synchronized (LOCK) {
            String revision = preferences.getString("revision", "");
            if (revision.isEmpty()) {
                revision = java.util.UUID.randomUUID().toString();
                if (!preferences.edit().putString("revision", revision).commit()) throw new java.io.IOException("Cannot save connection scope");
            }
            return new Connection(url(), authorization(), revision);
        }
    }

    /** Small private settings payloads, authenticated and encrypted with a fresh GCM nonce. */
    String protectedValue(String name) throws Exception {
        synchronized (LOCK) {
            String value = preferences.getString("protected." + name, "");
            if (value.isEmpty()) return "";
            String[] parts = value.split("\\.", 2);
            if (parts.length != 2) throw new java.io.IOException("Encrypted settings are invalid");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        }
    }
    void saveProtected(String name, String value) throws Exception {
        synchronized (LOCK) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            String envelope = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            if (!preferences.edit().putString("protected." + name, envelope).commit())
                throw new java.io.IOException("Cannot save encrypted settings");
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }
}
