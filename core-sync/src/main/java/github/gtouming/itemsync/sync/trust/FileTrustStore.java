package github.gtouming.itemsync.sync.trust;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.GroupId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public class FileTrustStore implements PeerTrustStore {

    public static final String FILE_NAME = "trust.properties";

    private final Path file;
    private final Properties entries = new Properties();

    public FileTrustStore(Path dataDir) throws IOException {
        this.file = dataDir.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                entries.load(reader);
            }
        }
    }

    @Override
    public synchronized boolean isTrusted(GroupId group, String fingerprint) {
        return entries.getProperty(key(group, fingerprint)) != null;
    }

    @Override
    public synchronized void trust(GroupId group, String fingerprint) throws IOException {
        entries.setProperty(key(group, fingerprint), "trusted");
        save();
    }

    @Override
    public synchronized void untrust(GroupId group, String fingerprint) throws IOException {
        if (entries.remove(key(group, fingerprint)) != null) {
            save();
        }
    }

    @Override
    public synchronized List<String> fingerprints(GroupId group) {
        String prefix = group.value() + "|";
        List<String> result = new ArrayList<>();
        for (String key : entries.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                result.add(key.substring(prefix.length()));
            }
        }
        Collections.sort(result);
        return Compat.copyList(result);
    }

    @Override
    public synchronized void retainOnly(GroupId group) throws IOException {
        retainOnly(List.of(group));
    }

    /**
     * Drops the trust of every group that is not in {@code groups}; a device that serves
     * several groups keeps the pairs of all of them.
     */
    public synchronized void retainOnly(Collection<GroupId> groups) throws IOException {
        Objects.requireNonNull(groups, "groups");
        List<String> prefixes = new ArrayList<>(groups.size());
        for (GroupId group : groups) {
            prefixes.add(Objects.requireNonNull(group, "group").value() + "|");
        }
        List<String> stale = new ArrayList<>();
        for (String key : entries.stringPropertyNames()) {
            if (prefixes.stream().noneMatch(key::startsWith)) {
                stale.add(key);
            }
        }
        if (!stale.isEmpty()) {
            stale.forEach(entries::remove);
            save();
        }
    }

    @Override
    public synchronized void clear() throws IOException {
        if (!entries.isEmpty()) {
            entries.clear();
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
