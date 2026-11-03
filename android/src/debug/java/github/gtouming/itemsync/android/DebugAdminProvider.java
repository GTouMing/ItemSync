package github.gtouming.itemsync.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.sync.trust.PeerTrustCoordinator;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

public final class DebugAdminProvider extends ContentProvider {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            JsonObject args = decodeArgs(arg);
            JsonObject data;
            switch (method) {
                case "status" -> data = status();
                case "peers" -> data = peers();
                case "config.get" -> data = configGet(args);
                case "config.set" -> data = configSet(args);
                case "trust.list" -> data = trustList();
                case "trust.add" -> data = trustAdd(args);
                case "trust.remove" -> data = trustRemove(args);
                case "trust.clear" -> data = trustClear(args);
                default -> throw new DebugAdminException(2, "unknown method: " + method);
            }
            return result(data);
        } catch (DebugAdminException e) {
            return error(e.code, e.getMessage());
        } catch (Exception e) {
            return error(4, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private JsonObject status() {
        AndroidSyncConfig config = AndroidSyncConfig.of(getContext());
        JsonObject data = configJson(config);
        data.addProperty("package", getContext().getPackageName());
        data.addProperty("debuggable", (getContext().getApplicationInfo().flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        data.addProperty("running", SyncForegroundService.isRunning());
        return data;
    }

    private JsonObject peers() {
        JsonObject data = new JsonObject();
        SyncForegroundService service = SyncForegroundService.instance();
        data.addProperty("running", service != null && service.isReady());
        JsonArray peers = new JsonArray();
        if (service != null) {
            for (PeerTrustCoordinator.PeerView view : service.peerViews()) {
                JsonObject peer = new JsonObject();
                peer.addProperty("deviceId", view.presence().peer().deviceId().value());
                peer.addProperty("nodeId", view.presence().peer().nodeId().value());
                JsonArray addresses = new JsonArray();
                for (java.net.InetAddress address : view.presence().peer().addresses()) {
                    addresses.add(address.getHostAddress());
                }
                peer.add("addresses", addresses);
                peer.addProperty("port", view.presence().peer().port());
                peer.addProperty("pairingPort", view.presence().peer().pairingPort());
                peer.addProperty("fingerprint", view.presence().peer().fingerprint());
                peer.addProperty("locallyTrusted", view.locallyTrusted());
                peer.addProperty("remoteTrust", view.remoteTrust().name());
                peer.addProperty("connected", view.connected());
                peer.addProperty("remoteTrustFailure",
                        view.remoteTrustFailure() == null ? "" : view.remoteTrustFailure().name());
                peer.addProperty("remoteTrustErrorDetail",
                        view.remoteTrustErrorDetail() == null
                                ? ""
                                : view.remoteTrustErrorDetail());
                peer.addProperty("lastSeen", view.presence().lastSeen().toString());
                peers.add(peer);
            }
        }
        data.add("peers", peers);
        return data;
    }

    private JsonObject configGet(JsonObject args) {
        JsonObject data = configJson(AndroidSyncConfig.of(getContext()));
        String key = string(args, "key");
        if (key == null) {
            return data;
        }
        JsonElement value = data.get(key);
        if (value == null) {
            throw new DebugAdminException(2, "unknown config key: " + key);
        }
        JsonObject selected = new JsonObject();
        selected.add(key, value);
        return selected;
    }

    private JsonObject configSet(JsonObject args) throws Exception {
        requireStopped();
        String key = requireString(args, "key");
        String value = requireString(args, "value");
        boolean confirm = bool(args, "confirm");
        AndroidSyncConfig config = AndroidSyncConfig.of(getContext());
        String root = config.rootPath();
        GroupId group = GroupId.parse(config.groupId());
        int port = config.port();
        int interval = config.intervalSeconds();

        switch (key) {
            case "group" -> {
                GroupId next = GroupId.parse(value);
                if (!next.equals(group)) {
                    if (!confirm) {
                        throw new DebugAdminException(2, "config set group requires --yes");
                    }
                    new AndroidTrustStore(getContext()).clear();
                    group = next;
                }
            }
            case "root" -> {
                if (value.trim().isEmpty()) {
                    throw new DebugAdminException(2, "root must not be blank");
                }
                if (!value.trim().equals(root) && !confirm) {
                    throw new DebugAdminException(2, "config set root requires --yes");
                }
                root = value.trim();
            }
            case "port" -> port = parsePort(value);
            case "interval-seconds" -> interval = parseNonNegative(value);
            default -> throw new DebugAdminException(2, "unknown or unsupported Android config key: " + key);
        }
        config.save(root, group.value(), port, interval);
        return configJson(AndroidSyncConfig.of(getContext()));
    }

    private JsonObject trustList() {
        AndroidSyncConfig config = AndroidSyncConfig.of(getContext());
        GroupId group = GroupId.parse(config.groupId());
        return trustJson(group, new AndroidTrustStore(getContext()));
    }

    private JsonObject trustAdd(JsonObject args) throws Exception {
        requireStopped();
        AndroidSyncConfig config = AndroidSyncConfig.of(getContext());
        GroupId group = GroupId.parse(config.groupId());
        String fingerprint = fingerprint(requireString(args, "fingerprint"));
        AndroidTrustStore trust = new AndroidTrustStore(getContext());
        trust.trust(group, fingerprint);
        return trustJson(group, trust);
    }

    private JsonObject trustRemove(JsonObject args) throws Exception {
        requireStopped();
        AndroidSyncConfig config = AndroidSyncConfig.of(getContext());
        GroupId group = GroupId.parse(config.groupId());
        String fingerprint = fingerprint(requireString(args, "fingerprint"));
        AndroidTrustStore trust = new AndroidTrustStore(getContext());
        trust.untrust(group, fingerprint);
        return trustJson(group, trust);
    }

    private JsonObject trustClear(JsonObject args) throws Exception {
        requireStopped();
        if (!bool(args, "confirm")) {
            throw new DebugAdminException(2, "trust clear requires --yes");
        }
        AndroidSyncConfig config = AndroidSyncConfig.of(getContext());
        GroupId group = GroupId.parse(config.groupId());
        AndroidTrustStore trust = new AndroidTrustStore(getContext());
        trust.clear();
        return trustJson(group, trust);
    }

    private static void requireStopped() {
        if (SyncForegroundService.isRunning()) {
            throw new DebugAdminException(3,
                    "Android sync service is running; stop it before changing data");
        }
    }

    private JsonObject configJson(AndroidSyncConfig config) {
        JsonObject data = new JsonObject();
        data.addProperty("group", config.groupId());
        data.addProperty("root", config.rootPath());
        data.addProperty("port", config.port());
        data.addProperty("intervalSeconds", config.intervalSeconds());
        GroupId group = GroupId.parse(config.groupId());
        List<String> fingerprints = new AndroidTrustStore(getContext()).fingerprints(group);
        data.addProperty("trustCount", fingerprints.size());
        return data;
    }

    private static JsonObject trustJson(GroupId group, AndroidTrustStore trust) {
        JsonObject data = new JsonObject();
        data.addProperty("group", group.value());
        JsonArray fingerprints = new JsonArray();
        for (String fingerprint : trust.fingerprints(group)) {
            fingerprints.add(fingerprint);
        }
        data.add("fingerprints", fingerprints);
        data.addProperty("count", fingerprints.size());
        return data;
    }

    private static JsonObject decodeArgs(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            return new JsonObject();
        }
        String json = new String(Base64.getUrlDecoder().decode(arg), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static Bundle result(JsonObject data) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        envelope.add("data", data);
        return bundle(envelope);
    }

    private static Bundle error(int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message == null ? "unknown error" : message);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", false);
        envelope.add("error", error);
        return bundle(envelope);
    }

    private static Bundle bundle(JsonObject envelope) {
        Bundle bundle = new Bundle();
        bundle.putString("result", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8)));
        return bundle;
    }

    private static String string(JsonObject args, String key) {
        JsonElement value = args.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String requireString(JsonObject args, String key) {
        String value = string(args, key);
        if (value == null || value.trim().isEmpty()) {
            throw new DebugAdminException(2, "missing argument: " + key);
        }
        return value;
    }

    private static boolean bool(JsonObject args, String key) {
        JsonElement value = args.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static String fingerprint(String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new DebugAdminException(2,
                    "fingerprint must be 64 lowercase hexadecimal characters");
        }
        return normalized;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new DebugAdminException(2, "invalid port: " + value);
        }
    }

    private static int parseNonNegative(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new DebugAdminException(2, "expected a non-negative integer: " + value);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }

    private static final class DebugAdminException extends RuntimeException {

        private final int code;

        private DebugAdminException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
