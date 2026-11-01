package github.gtouming.itemsync.domain.replication;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class VersionVectorCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");

    private VersionVectorCodec() {
    }

    public static String encode(VersionVector vector) {
        Objects.requireNonNull(vector, "vector");
        JsonObject root = new JsonObject();
        for (NodeId node : vector.nodes()) {
            root.addProperty(node.value(), vector.counterOf(node));
        }
        return GSON.toJson(root);
    }

    public static VersionVector decode(String json) {
        Objects.requireNonNull(json, "json");
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("invalid version vector json", e);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("version vector must be a json object");
        }
        Map<NodeId, Long> counters = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
            counters.put(NodeId.of(entry.getKey()), parseCounter(entry.getKey(), entry.getValue()));
        }
        return VersionVector.of(counters);
    }

    private static long parseCounter(String node, JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("counter for '" + node + "' must be a positive integer");
        }
        String raw = value.getAsString();
        if (!INTEGER.matcher(raw).matches()) {
            throw new IllegalArgumentException("counter for '" + node + "' must be an integer, got " + raw);
        }
        long counter;
        try {
            counter = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("counter for '" + node + "' is out of range: " + raw, e);
        }
        if (counter <= 0) {
            throw new IllegalArgumentException("counter for '" + node + "' must be positive, got " + counter);
        }
        return counter;
    }
}
