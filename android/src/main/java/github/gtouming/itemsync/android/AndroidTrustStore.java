package github.gtouming.itemsync.android;

import android.content.Context;
import android.content.SharedPreferences;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.sync.trust.PeerTrustStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class AndroidTrustStore implements PeerTrustStore {

    private static final String PREFS = "itemsync-trust";
    private static final String TRUSTED = "trusted";

    private final SharedPreferences prefs;

    public AndroidTrustStore(Context context) {
        this.prefs = Objects.requireNonNull(context, "context")
                .getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public synchronized boolean isTrusted(GroupId group, String fingerprint) {
        return prefs.getString(key(group, fingerprint), null) != null;
    }

    @Override
    public synchronized void trust(GroupId group, String fingerprint) throws IOException {
        prefs.edit().putString(key(group, fingerprint), TRUSTED).commit();
    }

    @Override
    public synchronized void untrust(GroupId group, String fingerprint) throws IOException {
        prefs.edit().remove(key(group, fingerprint)).commit();
    }

    @Override
    public synchronized void retainOnly(GroupId group) throws IOException {
        String prefix = keyPrefix(group);
        List<String> stale = new ArrayList<>();
        for (String existing : prefs.getAll().keySet()) {
            if (!existing.startsWith(prefix)) {
                stale.add(existing);
            }
        }
        if (!stale.isEmpty()) {
            SharedPreferences.Editor editor = prefs.edit();
            for (String existing : stale) {
                editor.remove(existing);
            }
            editor.commit();
        }
    }

    @Override
    public synchronized void clear() throws IOException {
        prefs.edit().clear().commit();
    }

    private static String key(GroupId group, String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        String value = fingerprint.trim().toLowerCase(Locale.ROOT);
        if (Compat.isBlank(value)) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        return keyPrefix(group) + value;
    }

    private static String keyPrefix(GroupId group) {
        Objects.requireNonNull(group, "group");
        return group.value() + "|";
    }
}
