package github.gtouming.itemsync.android;

import android.content.Context;
import android.content.SharedPreferences;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.security.LocalPassword;
import github.gtouming.itemsync.domain.security.NodeUnlockKey;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AndroidSyncConfig {

    private static final String PREFS = "itemsync";
    private static final String GROUPS_COUNT = "group.count";

    /** ADR-0035: a device keeps at most five active groups. */
    public static final int MAX_GROUPS = 5;

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

    /**
     * The persisted groups in creation order. A legacy single-group installation is returned
     * as one group until the next save writes the group list.
     */
    public List<AndroidGroup> groups() {
        int count = prefs.getInt(GROUPS_COUNT, -1);
        if (count >= 0) {
            if (count > MAX_GROUPS) {
                throw new IllegalArgumentException(
                        GROUPS_COUNT + " out of range: " + count);
            }
            List<AndroidGroup> groups = new ArrayList<>(count);
            for (int index = 1; index <= count; index++) {
                String prefix = "group." + index + ".";
                String rawId = prefs.getString(prefix + "id", null);
                String rawRoot = prefs.getString(prefix + "root", null);
                if (rawId == null || rawId.trim().isEmpty()
                        || rawRoot == null || rawRoot.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "missing sync group entry: " + prefix);
                }
                groups.add(new AndroidGroup(GroupId.parse(rawId),
                        prefs.getString(prefix + "name", null), rawRoot));
            }
            return validate(groups);
        }
        String rawGroup = prefs.getString("group", null);
        String rawRoot = prefs.getString("root", null);
        if (rawGroup == null || rawGroup.trim().isEmpty()
                || rawRoot == null || rawRoot.trim().isEmpty()) {
            return List.of();
        }
        return List.of(new AndroidGroup(GroupId.parse(rawGroup), null, rawRoot));
    }

    public boolean hasGroups() {
        return !groups().isEmpty();
    }

    public AndroidGroup firstGroup() {
        List<AndroidGroup> groups = groups();
        return groups.isEmpty() ? null : groups.get(0);
    }

    public String rootPath() {
        AndroidGroup first = firstGroup();
        if (first != null) {
            return first.rootPath();
        }
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        return prefs.getString("root", new File(base, "root").getAbsolutePath());
    }

    /**
     * The first group id, kept for the single-group callers and the debug admin provider.
     * New code should use {@link #groups()} because an installation may have no group.
     */
    public String groupId() {
        AndroidGroup first = firstGroup();
        if (first != null) {
            return first.id().value();
        }
        String legacy = prefs.getString("group", null);
        return legacy == null || legacy.trim().isEmpty()
                ? GroupId.random().value()
                : legacy.trim();
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
        return !groups().isEmpty();
    }

    public void save(String root, String group, int port, int intervalSeconds) {
        List<AndroidGroup> current = new ArrayList<>(groups());
        AndroidGroup replacement = new AndroidGroup(GroupId.parse(group), null, root);
        if (current.isEmpty()) {
            current.add(replacement);
        } else {
            AndroidGroup first = current.get(0);
            current.set(0, new AndroidGroup(replacement.id(), first.name(),
                    replacement.rootPath()));
        }
        saveGroups(current, port, intervalSeconds);
    }

    public void saveGroups(List<AndroidGroup> groups) {
        saveGroups(groups, port(), intervalSeconds());
    }

    /**
     * Validates and persists the whole group list together with the shared port and interval.
     */
    public void saveGroups(List<AndroidGroup> groups, int port, int intervalSeconds) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (intervalSeconds < 0) {
            throw new IllegalArgumentException("interval must not be negative");
        }
        List<AndroidGroup> validated = validate(groups);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(GROUPS_COUNT, validated.size());
        for (int index = 0; index < validated.size(); index++) {
            AndroidGroup group = validated.get(index);
            String prefix = "group." + (index + 1) + ".";
            editor.putString(prefix + "id", group.id().value());
            editor.putString(prefix + "name", group.name());
            editor.putString(prefix + "root", group.rootPath());
        }
        for (int index = validated.size() + 1; index <= MAX_GROUPS; index++) {
            String prefix = "group." + index + ".";
            editor.remove(prefix + "id");
            editor.remove(prefix + "name");
            editor.remove(prefix + "root");
        }
        // Legacy peer pinning is replaced by the per-group trust store.
        editor.remove("root-id");
        editor.remove("peers");
        editor.remove("expect");
        if (validated.isEmpty()) {
            editor.remove("group");
            editor.remove("root");
        } else {
            // Legacy keys keep the CLI/admin provider and older builds working.
            editor.putString("group", validated.get(0).id().value());
            editor.putString("root", validated.get(0).rootPath());
        }
        editor.putInt("port", port);
        editor.putInt("interval", intervalSeconds);
        editor.putBoolean("configured", true);
        editor.commit();
    }

    public void addGroup(AndroidGroup group) {
        List<AndroidGroup> updated = new ArrayList<>(groups());
        updated.add(Objects.requireNonNull(group, "group"));
        saveGroups(updated);
    }

    public void removeGroup(GroupId id) {
        Objects.requireNonNull(id, "id");
        List<AndroidGroup> updated = new ArrayList<>(groups());
        boolean removed = updated.removeIf(group -> group.id().equals(id));
        if (!removed) {
            throw new IllegalArgumentException("unknown sync group: " + id.value());
        }
        saveGroups(updated);
    }

    /**
     * Replaces the sync root of one group. The caller stops the group and resets its metadata
     * first; only the persisted configuration is changed here.
     */
    public void changeRoot(GroupId id, String root) {
        Objects.requireNonNull(id, "id");
        List<AndroidGroup> updated = new ArrayList<>(groups().size());
        boolean found = false;
        for (AndroidGroup group : groups()) {
            if (group.id().equals(id)) {
                updated.add(new AndroidGroup(group.id(), group.name(), root));
                found = true;
            } else {
                updated.add(group);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("unknown sync group: " + id.value());
        }
        saveGroups(updated);
    }

    public void renameGroup(GroupId id, String name) {
        Objects.requireNonNull(id, "id");
        List<AndroidGroup> updated = new ArrayList<>(groups().size());
        boolean found = false;
        for (AndroidGroup group : groups()) {
            if (group.id().equals(id)) {
                updated.add(new AndroidGroup(group.id(), name, group.rootPath()));
                found = true;
            } else {
                updated.add(group);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("unknown sync group: " + id.value());
        }
        saveGroups(updated);
    }

    public void saveFingerprint(String fingerprint) {
        prefs.edit().putString("fingerprint", fingerprint).apply();
    }

    private static List<AndroidGroup> validate(List<AndroidGroup> groups) {
        Objects.requireNonNull(groups, "groups");
        if (groups.size() > MAX_GROUPS) {
            throw new IllegalArgumentException(
                    "at most " + MAX_GROUPS + " sync groups are supported, got " + groups.size());
        }
        List<AndroidGroup> copy = new ArrayList<>(groups.size());
        Set<GroupId> ids = new HashSet<>();
        for (AndroidGroup group : groups) {
            Objects.requireNonNull(group, "group");
            if (!ids.add(group.id())) {
                throw new IllegalArgumentException(
                        "duplicate sync group: " + group.id().value());
            }
            copy.add(group);
        }
        for (int left = 0; left < copy.size(); left++) {
            for (int right = left + 1; right < copy.size(); right++) {
                Path leftRoot = canonical(copy.get(left).root());
                Path rightRoot = canonical(copy.get(right).root());
                if (leftRoot.startsWith(rightRoot) || rightRoot.startsWith(leftRoot)) {
                    throw new IllegalArgumentException(
                            "sync roots must not overlap: " + copy.get(left).id().value()
                                    + " and " + copy.get(right).id().value());
                }
            }
        }
        return copy;
    }

    private static Path canonical(File root) {
        try {
            return root.getCanonicalFile().toPath();
        } catch (IOException e) {
            return root.getAbsoluteFile().toPath();
        }
    }
}
