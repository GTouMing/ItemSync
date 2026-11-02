package github.gtouming.itemsync.desktop;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.GroupId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class TrustStore {

    private static final String FILE_NAME = "trust.properties";

    private final Path file;
    private final Properties entries = new Properties();

    public TrustStore(Path dataDir) throws IOException {
        this.file = dataDir.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                entries.load(reader);
            }
        }
    }

    public synchronized boolean isTrusted(GroupId group, String fingerprint) {
        return entries.getProperty(key(group, fingerprint)) != null;
    }

    public synchronized void trust(GroupId group, String fingerprint) throws IOException {
        entries.setProperty(key(group, fingerprint), "trusted");
        save();
    }

    public synchronized void untrust(GroupId group, String fingerprint) throws IOException {
        if (entries.remove(key(group, fingerprint)) != null) {
            save();
        }
    }

    public synchronized void retainOnly(GroupId group) throws IOException {
        String prefix = group.value() + "|";
        var stale = new java.util.ArrayList<String>();
        for (String key : entries.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                stale.add(key);
            }
        }
        if (!stale.isEmpty()) {
            stale.forEach(entries::remove);
            save();
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".trust-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                entries.store(writer, "ItemSync trust");
            }
            Compat.move(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String key(GroupId group, String fingerprint) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(fingerprint, "fingerprint");
        String value = fingerprint.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        return group.value() + "|" + value;
    }
}
