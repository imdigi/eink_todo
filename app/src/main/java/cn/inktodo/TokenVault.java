package cn.inktodo;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TokenVault {
    private static final String ALIAS = "inktodo-personal-token-v1";
    private final Context context;
    TokenVault(Context context) { this.context = context; }
    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }
    void save(String token) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        String iv = Base64.encodeToString(c.getIV(), Base64.NO_WRAP);
        String data = Base64.encodeToString(c.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!context.getSharedPreferences("vault", 0).edit().putString("iv", iv).putString("data", data).commit())
            throw new java.io.IOException("无法保存凭据");
    }
    String load() throws Exception {
        android.content.SharedPreferences p = context.getSharedPreferences("vault", 0);
        String data = p.getString("data", ""); if (data.isEmpty()) return "";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p.getString("iv", ""), Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
    void clear() throws Exception {
        if (!context.getSharedPreferences("vault", 0).edit().clear().commit()) throw new java.io.IOException("无法移除凭据");
    }
}
