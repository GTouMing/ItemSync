package github.gtouming.itemsync.transport.frame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.LogicalClock;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class FramePayloads {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");

    private FramePayloads() {
    }

    public static byte[] encodeHello(Hello hello) {
        JsonObject json = new JsonObject();
        json.addProperty("nodeId", hello.nodeId().value());
        json.addProperty("protocolVersion", hello.protocolVersion());
        json.addProperty("certificateFingerprint", hello.certificateFingerprint());
        return toBytes(json);
    }

    public static Hello decodeHello(byte[] payload) {
        JsonObject json = parseObject(payload);
        return new Hello(
                NodeId.of(string(json, "nodeId")),
                integer(json, "protocolVersion"),
                string(json, "certificateFingerprint"));
    }

    public static byte[] encodePing(Ping ping) {
        JsonObject json = new JsonObject();
        json.addProperty("nonce", ping.nonce());
        return toBytes(json);
    }

    public static Ping decodePing(byte[] payload) {
        return new Ping(number(parseObject(payload), "nonce"));
    }

    public static byte[] encodePong(Pong pong) {
        JsonObject json = new JsonObject();
        json.addProperty("nonce", pong.nonce());
        return toBytes(json);
    }

    public static Pong decodePong(byte[] payload) {
        return new Pong(number(parseObject(payload), "nonce"));
    }

    public static byte[] encodeBlobGet(BlobGet request) {
        JsonObject json = new JsonObject();
        json.addProperty("hash", request.hash().value());
        json.addProperty("offset", request.offset());
        return toBytes(json);
    }

    public static BlobGet decodeBlobGet(byte[] payload) {
        JsonObject json = parseObject(payload);
        return new BlobGet(BlobHash.parse(string(json, "hash")), number(json, "offset"));
    }

    public static byte[] encodeBlobResult(BlobResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("hash", result.hash().value());
        json.addProperty("ok", result.ok());
        json.addProperty("size", result.size());
        if (result.errorCode() != null) {
            json.addProperty("errorCode", result.errorCode().name());
        }
        return toBytes(json);
    }

    public static BlobResult decodeBlobResult(byte[] payload) {
        JsonObject json = parseObject(payload);
        String errorCode = optionalString(json, "errorCode");
        return new BlobResult(
                BlobHash.parse(string(json, "hash")),
                bool(json, "ok"),
                number(json, "size"),
                errorCode == null ? null : ErrorCode.valueOf(errorCode));
    }

    public static byte[] encodeError(ErrorMessage error) {
        JsonObject json = new JsonObject();
        json.addProperty("code", error.code().name());
        json.addProperty("message", error.message());
        return toBytes(json);
    }

    public static ErrorMessage decodeError(byte[] payload) {
        JsonObject json = parseObject(payload);
        return new ErrorMessage(ErrorCode.valueOf(string(json, "code")), string(json, "message"));
    }

    public static byte[] encodeNotify(Notify notify) {
        JsonObject json = new JsonObject();
        json.addProperty("runId", notify.runId().value());
        json.addProperty("groupId", notify.groupId().value());
        json.addProperty("logicalTime", notify.logicalTime().value());
        json.addProperty("rootId", notify.rootId().value());
        json.add("vector", vectorToJson(notify.vectorSnapshot()));
        return toBytes(json);
    }

    public static Notify decodeNotify(byte[] payload) {
        JsonObject json = parseObject(payload);
        return new Notify(
                SyncRunId.of(string(json, "runId")),
                GroupId.parse(string(json, "groupId")),
                LogicalClock.of(number(json, "logicalTime")),
                SyncRootId.of(string(json, "rootId")),
                vectorFromJson(object(json, "vector")));
    }

    public static byte[] encodeNotifyAck(NotifyAck ack) {
        JsonObject json = new JsonObject();
        json.addProperty("runId", ack.runId().value());
        json.addProperty("accepted", ack.accepted());
        json.addProperty("queuePosition", ack.queuePosition());
        return toBytes(json);
    }

    public static NotifyAck decodeNotifyAck(byte[] payload) {
        JsonObject json = parseObject(payload);
        return new NotifyAck(
                SyncRunId.of(string(json, "runId")),
                bool(json, "accepted"),
                integer(json, "queuePosition"));
    }

    public static byte[] encodeManifestRequest(ManifestRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("rootId", request.rootId().value());
        json.add("since", vectorToJson(request.since()));
        if (request.ignoreHash() != null) {
            json.addProperty("ignoreHash", request.ignoreHash());
        }
        return toBytes(json);
    }

    public static ManifestRequest decodeManifestRequest(byte[] payload) {
        JsonObject json = parseObject(payload);
        return new ManifestRequest(
                SyncRootId.of(string(json, "rootId")),
                vectorFromJson(object(json, "since")),
                optionalString(json, "ignoreHash"));
    }

    public static byte[] encodeManifestPage(ManifestPage page) {
        JsonObject json = new JsonObject();
        JsonArray entries = new JsonArray();
        for (ManifestEntry entry : page.entries()) {
            entries.add(entryToJson(entry));
        }
        json.add("entries", entries);
        if (page.nextCursor() != null) {
            json.addProperty("nextCursor", page.nextCursor());
        }
        return toBytes(json);
    }

    public static ManifestPage decodeManifestPage(byte[] payload) {
        JsonObject json = parseObject(payload);
        JsonArray entries = array(json, "entries");
        List<ManifestEntry> decoded = new ArrayList<>();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("manifest entry must be a json object");
            }
            decoded.add(entryFromJson(element.getAsJsonObject()));
        }
        return new ManifestPage(decoded, optionalString(json, "nextCursor"));
    }

    public static byte[] encodeManifestEnd(ManifestEnd end) {
        JsonObject json = new JsonObject();
        json.addProperty("totalEntries", end.totalEntries());
        return toBytes(json);
    }

    public static ManifestEnd decodeManifestEnd(byte[] payload) {
        return new ManifestEnd(integer(parseObject(payload), "totalEntries"));
    }

    private static byte[] toBytes(JsonObject json) {
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject parseObject(byte[] payload) {
        try {
            JsonElement element = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("control payload must be a json object");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("invalid control payload", e);
        }
    }

    private static JsonObject vectorToJson(VersionVector vector) {
        JsonObject json = new JsonObject();
        for (NodeId node : vector.nodes()) {
            json.addProperty(node.value(), vector.counterOf(node));
        }
        return json;
    }

    private static VersionVector vectorFromJson(JsonObject json) {
        Map<NodeId, Long> counters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonElement element = entry.getValue();
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("vector counter must be a number: " + entry.getKey());
            }
            String raw = element.getAsString();
            if (!INTEGER.matcher(raw).matches()) {
                throw new IllegalArgumentException("vector counter is not an integer: " + raw);
            }
            long counter;
            try {
                counter = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("vector counter is out of range: " + raw, e);
            }
            if (counter <= 0) {
                throw new IllegalArgumentException("vector counter must be positive: " + counter);
            }
            counters.put(NodeId.of(entry.getKey()), counter);
        }
        return counters.isEmpty() ? VersionVector.empty() : VersionVector.of(counters);
    }

    private static JsonObject entryToJson(ManifestEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("path", entry.path().value());
        json.addProperty("kind", entry.kind().name());
        if (entry.hash() != null) {
            json.addProperty("hash", entry.hash().value());
        }
        json.addProperty("size", entry.size());
        json.addProperty("mtime", entry.mtime().toEpochMilli());
        json.add("vector", vectorToJson(entry.vector()));
        json.addProperty("deleted", entry.deleted());
        return json;
    }

    private static ManifestEntry entryFromJson(JsonObject json) {
        String hash = optionalString(json, "hash");
        return new ManifestEntry(
                LogicalPath.of(string(json, "path")),
                ItemKind.valueOf(string(json, "kind")),
                hash == null ? null : BlobHash.parse(hash),
                number(json, "size"),
                Instant.ofEpochMilli(number(json, "mtime")),
                vectorFromJson(object(json, "vector")),
                bool(json, "deleted"));
    }

    private static JsonObject object(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("missing object field: " + name);
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("missing array field: " + name);
        }
        return element.getAsJsonArray();
    }

    private static String string(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing string field: " + name);
        }
        return element.getAsString();
    }

    private static String optionalString(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("field must be a string: " + name);
        }
        return element.getAsString();
    }

    private static long number(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing numeric field: " + name);
        }
        String raw = element.getAsString();
        if (!INTEGER.matcher(raw).matches()) {
            throw new IllegalArgumentException("field is not an integer: " + name + "=" + raw);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("field is out of range: " + name + "=" + raw, e);
        }
    }

    private static int integer(JsonObject json, String name) {
        long value = number(json, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("field is out of int range: " + name + "=" + value);
        }
        return (int) value;
    }

    private static boolean bool(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("missing boolean field: " + name);
        }
        return element.getAsBoolean();
    }
}
