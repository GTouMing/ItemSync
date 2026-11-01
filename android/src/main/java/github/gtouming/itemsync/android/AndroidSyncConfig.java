package github.gtouming.itemsync.android;

import android.content.Context;
import android.content.SharedPreferences;

import github.gtouming.itemsync.domain.identity.GroupId;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AndroidSyncConfig {

    public record Peer(String host, int port, String fingerprint) {

        public static Peer parse(String value) {
            int fingerprintSeparator = value.lastIndexOf(':');
            int portSeparator = value.lastIndexOf(':', fingerprintSeparator - 1);
            if (portSeparator <= 0 || fingerprintSeparator <= portSeparator + 1
                    || fingerprintSeparator == value.length() - 1) {
                throw new IllegalArgumentException("peer must be host:port:fingerprint: " + value);
            }
            String host = value.substring(0, portSeparator);
            int port = Integer.parseInt(value.substring(portSeparator + 1, fingerprintSeparator));
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("peer port out of range: " + port);
            }
            return new Peer(host, port, value.substring(fingerprintSeparator + 1));
        }
    }

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

    public List<Peer> peers() {
        List<Peer> peers = new ArrayList<>();
        String raw = prefs.getString("peers", "");
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                peers.add(Peer.parse(value));
            }
        }
        return peers;
    }

    public Set<String> expectedClients() {
        Set<String> clients = new LinkedHashSet<>();
        for (String part : prefs.getString("expect", "").split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                clients.add(value);
            }
        }
        for (Peer peer : peers()) {
            clients.add(peer.fingerprint());
        }
        return clients;
    }

    public char[] password() {
        String encrypted = prefs.getString("password", "");
        if (encrypted.isEmpty()) {
            return new char[0];
        }
        try {
            return secrets.decrypt(encrypted).toCharArray();
        } catch (Exception e) {
            return new char[0];
        }
    }

    public boolean isConfigured() {
        return !groupId().trim().isEmpty()
                && !rootPath().trim().isEmpty()
                && password().length > 0;
    }

    public void save(String root, String group, String password, String peers, int port,
                     int intervalSeconds) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("root", root.trim());
        editor.putString("group", group.trim());
        editor.putString("peers", peers.trim());
        editor.putInt("port", port);
        editor.putInt("interval", intervalSeconds);
        if (password != null && !password.isEmpty()) {
            try {
                editor.putString("password", secrets.encrypt(password));
            } catch (Exception e) {
                throw new IllegalStateException("无法加密口令", e);
            }
        }
        editor.commit();
    }

    public void saveFingerprint(String fingerprint) {
        prefs.edit().putString("fingerprint", fingerprint).apply();
    }
}
