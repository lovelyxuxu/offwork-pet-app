package com.offworkpet.receiver;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Settings implements RelayClient.Identity {
    final SharedPreferences prefs;
    private static final String KEY = "offwork-receiver-identity-v1";
    Settings(Context context) { prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE); }
    String code() { return prefs.getString("code", ""); }
    String nickname() { return prefs.getString("nickname", "手机"); }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    void enabled(boolean value) { prefs.edit().putBoolean("enabled", value).apply(); }
    void group(String code, String name) { prefs.edit().putString("code", code).putString("nickname", name).apply(); }
    String status() { return prefs.getString("status", "尚未开始接收"); }
    void status(String value) { prefs.edit().putString("status", value).apply(); }
    boolean muted() { return prefs.getBoolean("muted", false); }
    void muted(boolean value) { prefs.edit().putBoolean("muted", value).apply(); }
    synchronized void diagnostic(String event) {
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(new java.util.Date());
        String[] previous = prefs.getString("connection_log", "").split("\n");
        StringBuilder log = new StringBuilder();
        for (int i = Math.max(0, previous.length - 59); i < previous.length; i++)
            if (!previous[i].isEmpty()) log.append(previous[i]).append('\n');
        log.append(stamp).append(' ').append(event);
        prefs.edit().putString("connection_log", log.toString()).apply();
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY, null);
    }
    @Override public String load() throws Exception {
        String value = prefs.getString("identity", "");
        if (value.isEmpty()) return "";
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Invalid identity");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
    @Override public void save(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!prefs.edit().putString("identity", value).commit()) throw new IllegalStateException("Identity persistence failed");
    }
}
