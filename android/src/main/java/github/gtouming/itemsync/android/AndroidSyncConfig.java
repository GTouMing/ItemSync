package github.gtouming.itemsync.android;

import android.content.Context;
import android.content.SharedPreferences;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.security.LocalPassword;
import github.gtouming.itemsync.domain.security.NodeUnlockKey;

import java.io.File;

public final class AndroidSyncConfig {

    private static final String PREFS = "itemsync";

    private final Context context;
    private final SharedPreferences prefs;
    private final AndroidSecretStore secrets = new AndroidSecretStore();

    private AndroidSyncConfig(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static AndroidSyncConfig of(Context context) {
        return new AndroidSyncConfig(context);
    }

    public boolean hasLocalPassword() {
        return !prefs.getString("local-password", "").isEmpty();
    }

    public void setLocalPasswordHash(String hash) {
        prefs.edit().putString("local-password", hash).commit();
    }

    public boolean verifyLocalPassword(char[] password) {
        String hash = prefs.getString("local-password", "");
        if (hash.isEmpty() || password.length == 0) {
            return false;
        }
        try {
            return LocalPassword.verify(password, hash);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String rootPath() {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        return prefs.getString("root", new File(base, "root").getAbsolutePath());
    }

    public String groupId() {
        String value = prefs.getString("group", null);
        if (value == null || value.trim().isEmpty()) {
            value = GroupId.random().value();
            prefs.edit().putString("group", value).apply();
        }
        return value;
    }

    public String rootId() {
        return prefs.getString("root-id", "root-1");
    }

    public int port() {
        return prefs.getInt("port", 47800);
    }

    public int intervalSeconds() {
        return prefs.getInt("interval", 300);
    }

    public String fingerprint() {
        return prefs.getString("fingerprint", "");
    }

    /**
     * NUK for the TLS identity. Legacy installs reuse the old encrypted identity
     * password; fresh installs get a random 32-byte NUK. The GUI password is
     * never used here.
     */
    public char[] identitySecret() {
        String encrypted = prefs.getString("nuk", "");
        if (encrypted.isEmpty()) {
            encrypted = prefs.getString("password", "");
        }
        if (!encrypted.isEmpty()) {
            try {
                return secrets.decrypt(encrypted).toCharArray();
            } catch (Exception e) {
                return new char[0];
            }
        }
        if (new File(context.getFilesDir(), "identity.p12").isFile()) {
            return new char[0];
        }
        NodeUnlockKey key = NodeUnlockKey.generate();
        try {
            prefs.edit().putString("nuk", secrets.encrypt(key.base64())).apply();
        } catch (Exception e) {
            return new char[0];
        }
        return key.keystorePassword();
    }

    public boolean isConfigured() {
        if (prefs.getBoolean("configured", false)) {
            return true;
        }
        return prefs.contains("group") && prefs.contains("root")
                && (prefs.contains("nuk") || prefs.contains("password"));
    }

    public void save(String root, String group, int port, int intervalSeconds) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("root", root.trim());
        editor.putString("group", group.trim());
        editor.remove("peers");
        editor.remove("expect");
        editor.putInt("port", port);
        editor.putInt("interval", intervalSeconds);
        editor.putBoolean("configured", true);
        editor.commit();
    }

    public void saveFingerprint(String fingerprint) {
        prefs.edit().putString("fingerprint", fingerprint).apply();
    }
}
