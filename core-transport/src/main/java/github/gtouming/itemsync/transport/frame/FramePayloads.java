package github.gtouming.itemsync.transport.frame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.nio.charset.StandardCharsets;
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
