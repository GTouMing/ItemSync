package github.gtouming.itemsync.platform.desktop;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.identity.GroupId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * The persisted desktop installation: the data directory, the list of sync groups, the
 * shared sync port and the local launch/debounce preferences.
 *
 * <p>The group list lives in {@value #GROUPS_FILE_NAME} as {@code group.count} plus
 * {@code group.<n>.id/name/root}; {@value #FILE_NAME} keeps the port, the launch command,
 * the debounce and - for older builds and the CLI - the legacy {@code group}/{@code root}
 * keys of the first group. {@link #group()} and {@link #root()} return that first group so
 * the single-group callers (desktop UI, CLI) keep working against a multi-group file.
 */
public record DesktopConfig(Path dataDir, List<DesktopGroup> groups,
                            int port, String launchCommand, Duration debounce) {

    public static final String FILE_NAME = "desktop.properties";
    public static final String GROUPS_FILE_NAME = "desktop-groups.properties";

    /** ADR-0035: a device keeps at most five active groups. */
    public static final int MAX_GROUPS = 5;

    private static final String GROUPS_COUNT_KEY = "group.count";
    private static final String GROUPS_COMMENT = "ItemSync desktop sync groups";

    public DesktopConfig {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(launchCommand, "launchCommand");
        Objects.requireNonNull(debounce, "debounce");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must not be negative");
        }
        groups = validateGroups(groups);
        for (DesktopGroup group : groups) {
            PathSupport.requireDataDirOutsideRoot(dataDir, group.root());
        }
    }

    /**
     * The id of the first (and, for a single-group installation, only) group.
     *
     * @throws IllegalStateException when the installation has no group yet
     */
    public GroupId group() {
        return firstGroup().id();
    }

    /**
     * The sync root of the first group.
     *
     * @throws IllegalStateException when the installation has no group yet
     */
    public Path root() {
        return firstGroup().root();
    }

    public boolean hasGroups() {
        return !groups.isEmpty();
    }

    /**
     * @throws IllegalStateException when the installation has no group yet
     */
    public DesktopGroup firstGroup() {
        if (groups.isEmpty()) {
            throw new IllegalStateException("desktop configuration has no sync group");
        }
        return groups.get(0);
    }

    public Optional<DesktopGroup> findGroup(GroupId id) {
        Objects.requireNonNull(id, "id");
        return groups.stream().filter(group -> group.id().equals(id)).findFirst();
    }

    public static DesktopConfig load(Path dataDir) throws IOException {
        Objects.requireNonNull(dataDir, "dataDir");
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("missing config: " + file);
        }
        Properties properties = readProperties(file);

        int port = parsePort(properties.getProperty("port", "47800").trim());
        String launchCommand = properties.getProperty("launch-command", "").trim();
        int debounceSeconds = Integer.parseInt(
                properties.getProperty("debounce-seconds", "30").trim());
        if (debounceSeconds < 0) {
            throw new IllegalArgumentException("debounce-seconds must not be negative");
        }
        // Legacy "expect"/"peers" keys are intentionally ignored; trust is only granted
        // through the device list after pairing.
        List<DesktopGroup> groups;
        Path groupsFile = dataDir.resolve(GROUPS_FILE_NAME);
        if (Files.isRegularFile(groupsFile)) {
            groups = readGroups(dataDir, groupsFile);
        } else {
            groups = migrateLegacyGroup(dataDir, properties);
            writeGroups(dataDir, groups);
        }
        return new DesktopConfig(dataDir, groups, port, launchCommand,
                Duration.ofSeconds(debounceSeconds));
    }

    /**
     * Replaces the group id of the first group, keeping its root, the transport settings and
     * every other group. Trust is not touched here; callers clear the trust store.
     */
    public static DesktopConfig changeGroup(Path dataDir, GroupId group) throws IOException {
        Objects.requireNonNull(group, "group");
        DesktopConfig current = load(dataDir);
        DesktopGroup first = current.firstGroup();
        String name = first.name().equals(first.id().value()) ? group.value() : first.name();
        List<DesktopGroup> updated = new ArrayList<>(current.groups());
        updated.set(0, new DesktopGroup(group, name, first.root()));
        return current.withGroups(updated);
    }

    public static DesktopConfig create(Path dataDir, Path root, GroupId group,
                                       int port, String launchCommand, Duration debounce)
            throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(group, "group");
        return create(dataDir, List.of(new DesktopGroup(group, null, root)), port,
                launchCommand, debounce);
    }

    public static DesktopConfig create(Path dataDir, List<DesktopGroup> groups, int port,
                                       String launchCommand, Duration debounce)
            throws IOException {
        DesktopConfig created = new DesktopConfig(dataDir, groups, port, launchCommand, debounce);
        persist(created);
        return created;
    }

    /**
     * Adds one group. The id must be unused and the new root must not overlap any other
     * group's root (ADR-0035: one root per group, no shared or nested roots).
     */
    public DesktopConfig addGroup(DesktopGroup group) throws IOException {
        Objects.requireNonNull(group, "group");
        if (findGroup(group.id()).isPresent()) {
            throw new IllegalArgumentException("duplicate sync group: " + group.id().value());
        }
        List<DesktopGroup> updated = new ArrayList<>(groups);
        updated.add(group);
        return withGroups(updated);
    }

    /**
     * Renames a group. Root changes are not supported by this slice, so a group keeps the
     * root it was created with.
     */
    public DesktopConfig renameGroup(GroupId id, String name) throws IOException {
        Objects.requireNonNull(id, "id");
        List<DesktopGroup> updated = new ArrayList<>(groups.size());
        boolean found = false;
        for (DesktopGroup existing : groups) {
            if (existing.id().equals(id)) {
                updated.add(new DesktopGroup(existing.id(), name, existing.root()));
                found = true;
            } else {
                updated.add(existing);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("unknown sync group: " + id.value());
        }
        return withGroups(updated);
    }

    public DesktopConfig removeGroup(GroupId id) throws IOException {
        Objects.requireNonNull(id, "id");
        List<DesktopGroup> updated = new ArrayList<>(groups.size());
        boolean found = false;
        for (DesktopGroup existing : groups) {
            if (existing.id().equals(id)) {
                found = true;
            } else {
                updated.add(existing);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("unknown sync group: " + id.value());
        }
        return withGroups(updated);
    }

    /**
     * Validates {@code updatedGroups} as a whole and persists it together with the transport
     * settings of this installation.
     */
    public DesktopConfig withGroups(List<DesktopGroup> updatedGroups) throws IOException {
        DesktopConfig updated = new DesktopConfig(dataDir, updatedGroups, port, launchCommand,
                debounce);
        persist(updated);
        return updated;
    }

    private static void persist(DesktopConfig config) throws IOException {
        // The legacy single-group keys are written first: a crash between the two writes then
        // leaves a desktop.properties that still migrates back to a sane group list.
        writeProperties(config);
        writeGroups(config.dataDir(), config.groups());
    }

    private static void writeProperties(DesktopConfig config) throws IOException {
        Properties properties = new Properties();
        if (config.hasGroups()) {
            DesktopGroup first = config.firstGroup();
            properties.setProperty("root", first.root().toString());
            properties.setProperty("group", first.id().value());
        }
        properties.setProperty("port", Integer.toString(config.port()));
        properties.setProperty("launch-command", config.launchCommand());
        properties.setProperty("debounce-seconds", Long.toString(config.debounce().toSeconds()));
        writeAtomic(config.dataDir(), FILE_NAME, properties, "ItemSync desktop configuration");
    }

    private static void writeGroups(Path dataDir, List<DesktopGroup> groups) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(GROUPS_COUNT_KEY, Integer.toString(groups.size()));
        for (int index = 0; index < groups.size(); index++) {
            DesktopGroup group = groups.get(index);
            String prefix = "group." + (index + 1) + ".";
            properties.setProperty(prefix + "id", group.id().value());
            properties.setProperty(prefix + "name", group.name());
            properties.setProperty(prefix + "root", group.root().toString());
        }
        writeAtomic(dataDir, GROUPS_FILE_NAME, properties, GROUPS_COMMENT);
    }

    private static void writeAtomic(Path dataDir, String fileName, Properties properties,
                                    String comment) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(fileName);
        Path tmp = Files.createTempFile(dataDir, ".desktop", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                properties.store(writer, comment);
            }
            Compat.move(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static List<DesktopGroup> migrateLegacyGroup(Path dataDir, Properties properties) {
        String rawGroup = properties.getProperty("group");
        String rawRoot = properties.getProperty("root");
        boolean hasGroup = rawGroup != null && !rawGroup.isBlank();
        boolean hasRoot = rawRoot != null && !rawRoot.isBlank();
        if (!hasGroup && !hasRoot) {
            // A fresh installation starts with an empty group list (ADR-0035).
            return List.of();
        }
        if (!hasGroup) {
            throw new IllegalArgumentException("missing config key: group");
        }
        if (!hasRoot) {
            throw new IllegalArgumentException("missing config key: root");
        }
        Path root = normalizedRoot(dataDir, rawRoot.trim());
        return List.of(new DesktopGroup(GroupId.parse(rawGroup), null, root));
    }

    private static List<DesktopGroup> readGroups(Path dataDir, Path file) throws IOException {
        Properties properties = readProperties(file);
        String rawCount = required(properties, GROUPS_COUNT_KEY);
        int count;
        try {
            count = Integer.parseInt(rawCount);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + GROUPS_COUNT_KEY + ": " + rawCount, e);
        }
        if (count < 0 || count > MAX_GROUPS) {
            throw new IllegalArgumentException(
                    GROUPS_COUNT_KEY + " out of range: " + count);
        }
        List<DesktopGroup> groups = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            String prefix = "group." + index + ".";
            GroupId id = GroupId.parse(required(properties, prefix + "id"));
            String name = properties.getProperty(prefix + "name");
            Path root = normalizedRoot(dataDir, required(properties, prefix + "root"));
            groups.add(new DesktopGroup(id, name, root));
        }
        return groups;
    }

    private static List<DesktopGroup> validateGroups(List<DesktopGroup> groups) {
        Objects.requireNonNull(groups, "groups");
        List<DesktopGroup> copy = Compat.copyList(groups);
        if (copy.size() > MAX_GROUPS) {
            throw new IllegalArgumentException(
                    "at most " + MAX_GROUPS + " sync groups are supported, got " + copy.size());
        }
        Set<GroupId> ids = new LinkedHashSet<>();
        for (DesktopGroup group : copy) {
            if (!ids.add(group.id())) {
                throw new IllegalArgumentException("duplicate sync group: " + group.id().value());
            }
        }
        for (int left = 0; left < copy.size(); left++) {
            for (int right = left + 1; right < copy.size(); right++) {
                requireDisjointRoots(copy.get(left), copy.get(right));
            }
        }
        return copy;
    }

    private static void requireDisjointRoots(DesktopGroup left, DesktopGroup right) {
        Path leftRoot = canonical(left.root());
        Path rightRoot = canonical(right.root());
        if (!leftRoot.startsWith(rightRoot) && !rightRoot.startsWith(leftRoot)) {
            return;
        }
        String relation = leftRoot.startsWith(rightRoot) && rightRoot.startsWith(leftRoot)
                ? "is the same directory as"
                : "is nested with";
        throw new IllegalArgumentException("sync roots must not overlap: the root of group "
                + left.id().value() + " (" + left.root() + ") " + relation
                + " the root of group " + right.id().value() + " (" + right.root() + ")");
    }

    private static Path canonical(Path root) {
        if (Files.exists(root)) {
            try {
                return root.toRealPath();
            } catch (IOException ignored) {
                // fall back to the normalized path; the comparison below is still exact
            }
        }
        return root;
    }

    private static Path normalizedRoot(Path dataDir, String raw) {
        Path root = PathSupport.expandUser(raw).toAbsolutePath().normalize();
        PathSupport.requireDataDirOutsideRoot(dataDir, root);
        return root;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing config key: " + key);
        }
        return value.trim();
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + raw, e);
        }
    }
}
