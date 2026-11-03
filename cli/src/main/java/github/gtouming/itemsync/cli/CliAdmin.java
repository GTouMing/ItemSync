package github.gtouming.itemsync.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.platform.desktop.DesktopConfig;
import github.gtouming.itemsync.platform.desktop.PathSupport;
import github.gtouming.itemsync.sync.trust.FileTrustStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CliAdmin {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RESULT = Pattern.compile("result=([A-Za-z0-9_-]+)");
    private static final String ANDROID_PACKAGE = "github.gtouming.itemsync";
    private static final String ANDROID_AUTHORITY =
            "github.gtouming.itemsync.debug.admin";

    private CliAdmin() {
    }

    static void config(CliArguments arguments) throws Exception {
        requirePositionals(arguments, 1);
        String operation = arguments.positionals().get(0);
        String platform = platform(arguments);
        if ("get".equals(operation)) {
            configGet(arguments, platform);
        } else if ("set".equals(operation)) {
            configSet(arguments, platform);
        } else {
            throw new AdminException(2, "config expects get or set");
        }
    }

    static void trust(CliArguments arguments) throws Exception {
        requirePositionals(arguments, 1);
        String operation = arguments.positionals().get(0);
        String platform = platform(arguments);
        if ("desktop".equals(platform)) {
            desktopTrust(arguments, operation);
        } else {
            androidTrust(arguments, operation);
        }
    }

    static void status(CliArguments arguments) throws Exception {
        String platform = platform(arguments);
        JsonObject data = "desktop".equals(platform)
                ? desktopStatus(arguments)
                : androidCall(arguments, "status", new JsonObject());
        output(arguments, "status", platform, data);
    }

    private static void configGet(CliArguments arguments, String platform) throws Exception {
        JsonObject data;
        if ("desktop".equals(platform)) {
            DesktopConfig config = desktopConfig(arguments);
            String key = arguments.positionals().size() > 1
                    ? arguments.positionals().get(1) : null;
            data = desktopConfigJson(config, key);
        } else {
            JsonObject request = new JsonObject();
            if (arguments.positionals().size() > 1) {
                request.addProperty("key", arguments.positionals().get(1));
            }
            data = androidCall(arguments, "config.get", request);
        }
        output(arguments, "config.get", platform, data);
    }

    private static void configSet(CliArguments arguments, String platform) throws Exception {
        if (arguments.positionals().size() != 3) {
            throw new AdminException(2, "usage: config set <key> <value>");
        }
        String key = arguments.positionals().get(1);
        String value = arguments.positionals().get(2);
        if ("desktop".equals(platform)) {
            desktopConfigSet(arguments, key, value);
        } else {
            if (("group".equals(key) || "root".equals(key)) && !arguments.flag("yes")) {
                throw new AdminException(2, "config set " + key + " requires --yes");
            }
            JsonObject request = new JsonObject();
            request.addProperty("key", key);
            request.addProperty("value", value);
            request.addProperty("confirm", arguments.flag("yes"));
            JsonObject data = androidCall(arguments, "config.set", request);
            output(arguments, "config.set", platform, data);
        }
    }

    private static void desktopConfigSet(CliArguments arguments, String key, String value)
            throws Exception {
        Path dataDir = desktopDataDir(arguments);
        DesktopConfig current = DesktopConfig.load(dataDir);
        requireDesktopStopped(current.port());
        DesktopConfig updated;
        switch (key) {
            case "group" -> {
                if (!arguments.flag("yes")) {
                    throw new AdminException(2, "config set group requires --yes");
                }
                updated = DesktopConfig.changeGroup(dataDir, GroupId.parse(value));
                new FileTrustStore(dataDir).clear();
            }
            case "root" -> {
                if (!arguments.flag("yes")) {
                    throw new AdminException(2, "config set root requires --yes");
                }
                updated = DesktopConfig.create(dataDir, PathSupport.expandUser(value),
                        current.group(), current.port(),
                        current.launchCommand(), current.debounce());
            }
            case "port" -> updated = DesktopConfig.create(dataDir, current.root(),
                    current.group(), parsePort(value),
                    current.launchCommand(), current.debounce());
            case "launch-command" -> updated = DesktopConfig.create(dataDir, current.root(),
                    current.group(), current.port(), value,
                    current.debounce());
            case "debounce-seconds" -> updated = DesktopConfig.create(dataDir, current.root(),
                    current.group(), current.port(),
                    current.launchCommand(), Duration.ofSeconds(parseNonNegative(value)));
            default -> throw new AdminException(2, "unknown desktop config key: " + key);
        }
        output(arguments, "config.set", "desktop", desktopConfigJson(updated, null));
    }

    private static void desktopTrust(CliArguments arguments, String operation) throws Exception {
        Path dataDir = desktopDataDir(arguments);
        DesktopConfig config = DesktopConfig.load(dataDir);
        FileTrustStore trust = new FileTrustStore(dataDir);
        if ("list".equals(operation)) {
            output(arguments, "trust.list", "desktop", trustJson(config.group(), trust));
            return;
        }
        requireDesktopStopped(config.port());
        if ("add".equals(operation)) {
            requirePositionals(arguments, 2);
            String fingerprint = fingerprint(arguments.positionals().get(1));
            trust.trust(config.group(), fingerprint);
            output(arguments, "trust.add", "desktop", trustJson(config.group(), trust));
        } else if ("remove".equals(operation)) {
            requirePositionals(arguments, 2);
            String fingerprint = fingerprint(arguments.positionals().get(1));
            trust.untrust(config.group(), fingerprint);
            output(arguments, "trust.remove", "desktop", trustJson(config.group(), trust));
        } else if ("clear".equals(operation)) {
            if (!arguments.flag("yes")) {
                throw new AdminException(2, "trust clear requires --yes");
            }
            trust.clear();
            output(arguments, "trust.clear", "desktop", trustJson(config.group(), trust));
        } else {
            throw new AdminException(2, "trust expects list, add, remove or clear");
        }
    }

    private static void androidTrust(CliArguments arguments, String operation) throws Exception {
        if (!"list".equals(operation)) {
            requireAndroidStopped(arguments);
        }
        JsonObject request = new JsonObject();
        switch (operation) {
            case "list" -> {
            }
            case "add", "remove" -> {
                requirePositionals(arguments, 2);
                request.addProperty("fingerprint", fingerprint(arguments.positionals().get(1)));
            }
            case "clear" -> {
                if (!arguments.flag("yes")) {
                    throw new AdminException(2, "trust clear requires --yes");
                }
                request.addProperty("confirm", true);
            }
            default -> throw new AdminException(2, "trust expects list, add, remove or clear");
        }
        JsonObject data = androidCall(arguments, "trust." + operation, request);
        output(arguments, "trust." + operation, "android", data);
    }

    private static JsonObject desktopStatus(CliArguments arguments) throws Exception {
        DesktopConfig config = desktopConfig(arguments);
        JsonObject data = desktopConfigJson(config, null);
        data.addProperty("running", desktopRunning(config.port()));
        return data;
    }

    private static JsonObject desktopConfigJson(DesktopConfig config, String key) {
        JsonObject json = new JsonObject();
        json.addProperty("dataDir", config.dataDir().toString());
        json.addProperty("group", config.group().value());
        json.addProperty("root", config.root().toString());
        json.addProperty("port", config.port());
        json.addProperty("launchCommand", config.launchCommand());
        json.addProperty("debounceSeconds", config.debounce().toSeconds());
        try {
            json.addProperty("trustCount",
                    new FileTrustStore(config.dataDir()).fingerprints(config.group()).size());
        } catch (IOException e) {
            throw new AdminException(4, "cannot read trust store: " + e.getMessage());
        }
        if (key == null) {
            return json;
        }
        JsonElement selected = json.get(key);
        if (selected == null) {
            throw new AdminException(2, "unknown config key: " + key);
        }
        JsonObject result = new JsonObject();
        result.add(key, selected);
        return result;
    }

    private static JsonObject trustJson(GroupId group, FileTrustStore trust) {
        JsonObject json = new JsonObject();
        json.addProperty("group", group.value());
        JsonArray fingerprints = new JsonArray();
        for (String fingerprint : trust.fingerprints(group)) {
            fingerprints.add(fingerprint);
        }
        json.add("fingerprints", fingerprints);
        json.addProperty("count", fingerprints.size());
        return json;
    }

    private static DesktopConfig desktopConfig(CliArguments arguments) throws Exception {
        return DesktopConfig.load(desktopDataDir(arguments));
    }

    private static Path desktopDataDir(CliArguments arguments) {
        return PathSupport.expandUser(arguments.required("data")).toAbsolutePath();
    }

    private static void requireDesktopStopped(int port) {
        if (desktopRunning(port)) {
            throw new AdminException(3,
                    "ItemSync appears to be running; stop it before changing data");
        }
    }

    private static boolean desktopRunning(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static JsonObject androidCall(CliArguments arguments, String method,
                                          JsonObject request) throws Exception {
        String adb = arguments.optional("adb", "adb");
        String serial = androidSerial(adb, arguments.optional("serial", null));
        String encodedArgs = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(GSON.toJson(request).getBytes(StandardCharsets.UTF_8));
        List<String> command = new ArrayList<>();
        command.add(adb);
        command.add("-s");
        command.add(serial);
        command.add("shell");
        command.add("content");
        command.add("call");
        command.add("--uri");
        command.add("content://" + ANDROID_AUTHORITY + "/admin");
        command.add("--method");
        command.add(method);
        command.add("--arg");
        command.add(encodedArgs);
        String output = run(command);
        Matcher matcher = RESULT.matcher(output);
        if (!matcher.find()) {
            throw new AdminException(4,
                    "debug APK/provider not available or returned no result: " + output.trim());
        }
        String json = new String(Base64.getUrlDecoder().decode(matcher.group(1)),
                StandardCharsets.UTF_8);
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        if (result.has("ok") && !result.get("ok").getAsBoolean()) {
            JsonObject error = result.getAsJsonObject("error");
            throw new AdminException(error == null ? 4 : error.get("code").getAsInt(),
                    error == null ? "Android command failed" : error.get("message").getAsString());
        }
        return result.has("data") ? result.getAsJsonObject("data") : result;
    }

    private static String androidSerial(String adb, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        String output;
        try {
            output = run(List.of(adb, "devices"));
        } catch (Exception e) {
            throw new AdminException(4, "cannot run adb: " + e.getMessage());
        }
        List<String> devices = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2 && "device".equals(parts[1])) {
                devices.add(parts[0]);
            }
        }
        if (devices.isEmpty()) {
            throw new AdminException(4, "no adb device connected");
        }
        if (devices.size() > 1) {
            throw new AdminException(2, "multiple adb devices; pass --serial");
        }
        return devices.get(0);
    }

    private static void requireAndroidStopped(CliArguments arguments) {
        String adb = arguments.optional("adb", "adb");
        String serial = androidSerial(adb, arguments.optional("serial", null));
        String output = run(List.of(adb, "-s", serial, "shell", "dumpsys",
                "activity", "services", ANDROID_PACKAGE));
        if (output.contains("SyncForegroundService")) {
            throw new AdminException(3,
                    "Android sync service is running; stop it before changing data");
        }
    }

    private static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AdminException(4, "adb command timed out");
            }
            if (process.exitValue() != 0) {
                throw new AdminException(4, "adb command failed: " + output.trim());
            }
            return output;
        } catch (IOException e) {
            throw new AdminException(4, "cannot execute " + command.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdminException(4, "adb command interrupted");
        }
    }

    private static String platform(CliArguments arguments) {
        String platform = arguments.optional("platform", "desktop").toLowerCase(Locale.ROOT);
        if (!"desktop".equals(platform) && !"android".equals(platform)) {
            throw new AdminException(2, "unknown platform: " + platform);
        }
        return platform;
    }

    private static void requirePositionals(CliArguments arguments, int count) {
        if (arguments.positionals().size() < count) {
            throw new AdminException(2, "missing command arguments");
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new AdminException(2, "invalid port: " + value);
        }
    }

    private static long parseNonNegative(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new AdminException(2, "expected a non-negative integer: " + value);
        }
    }

    private static String fingerprint(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new AdminException(2,
                    "fingerprint must be 64 lowercase hexadecimal characters");
        }
        return normalized;
    }

    private static void output(CliArguments arguments, String command, String platform,
                               JsonObject data) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        envelope.addProperty("command", command);
        envelope.addProperty("platform", platform);
        envelope.add("data", data);
        if (arguments.flag("json")) {
            System.out.println(GSON.toJson(envelope));
            return;
        }
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            System.out.println(entry.getKey() + ": " + human(entry.getValue()));
        }
    }

    private static String human(JsonElement element) {
        if (element.isJsonArray()) {
            StringBuilder out = new StringBuilder();
            for (JsonElement value : element.getAsJsonArray()) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(human(value));
            }
            return out.toString();
        }
        if (element.isJsonObject()) {
            return GSON.toJson(element);
        }
        return element.getAsString();
    }

    static void printError(String[] rawArgs, AdminException failure) {
        String command = rawArgs.length == 0 ? "" : rawArgs[0];
        boolean json = false;
        for (String argument : rawArgs) {
            if ("--json".equals(argument) || "--json=true".equals(argument)) {
                json = true;
                break;
            }
        }
        if (json) {
            JsonObject error = new JsonObject();
            error.addProperty("code", failure.code);
            error.addProperty("message", failure.getMessage());
            JsonObject envelope = new JsonObject();
            envelope.addProperty("ok", false);
            envelope.addProperty("command", command);
            envelope.add("error", error);
            System.out.println(GSON.toJson(envelope));
        } else {
            System.err.println("error: " + failure.getMessage());
        }
    }

    static final class AdminException extends RuntimeException {

        private final int code;

        AdminException(int code, String message) {
            super(message);
            this.code = code;
        }

        int code() {
            return code;
        }
    }
}
