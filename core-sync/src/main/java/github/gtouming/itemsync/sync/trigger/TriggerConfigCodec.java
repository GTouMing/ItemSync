package github.gtouming.itemsync.sync.trigger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class TriggerConfigCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private TriggerConfigCodec() {
    }

    public static String encode(TriggerConfig config) {
        if (config instanceof TriggerConfig.Alarm alarm) {
            JsonObject json = new JsonObject();
            json.addProperty("type", "alarm");
            JsonArray days = new JsonArray();
            for (DayOfWeek day : alarm.schedule().days()) {
                days.add(day.name());
            }
            json.add("days", days);
            json.addProperty("time", TIME_FORMAT.format(alarm.schedule().time()));
            json.addProperty("zone", alarm.schedule().zone().getId());
            return GSON.toJson(json);
        }
        if (config instanceof TriggerConfig.DirectoryChange) {
            JsonObject json = new JsonObject();
            json.addProperty("type", "directory");
            return GSON.toJson(json);
        }
        if (config instanceof TriggerConfig.ProcessEvent process) {
            JsonObject json = new JsonObject();
            json.addProperty("type", "process");
            JsonArray executables = new JsonArray();
            for (String executable : process.executables()) {
                executables.add(executable);
            }
            json.add("executables", executables);
            json.addProperty("onStart", process.onStart());
            json.addProperty("onStop", process.onStop());
            return GSON.toJson(json);
        }
        throw new IllegalArgumentException("unknown trigger config: " + config);
    }

    public static TriggerConfig decode(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("invalid trigger config", e);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("trigger config must be a json object");
        }
        JsonObject object = parsed.getAsJsonObject();
        String type = string(object, "type");
        return switch (type) {
            case "alarm" -> new TriggerConfig.Alarm(decodeAlarm(object));
            case "directory" -> new TriggerConfig.DirectoryChange();
            case "process" -> new TriggerConfig.ProcessEvent(
                    decodeExecutables(object),
                    bool(object, "onStart"),
                    bool(object, "onStop"));
            default -> throw new IllegalArgumentException("unknown trigger type: " + type);
        };
    }

    private static AlarmSchedule decodeAlarm(JsonObject object) {
        JsonElement daysElement = object.get("days");
        if (daysElement == null || !daysElement.isJsonArray()) {
            throw new IllegalArgumentException("alarm trigger needs a days array");
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (JsonElement element : daysElement.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("alarm day must be a string");
            }
            days.add(DayOfWeek.valueOf(element.getAsString().toUpperCase(Locale.ROOT)));
        }
        LocalTime time;
        try {
            time = LocalTime.parse(string(object, "time"), TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid alarm time", e);
        }
        ZoneId zone = ZoneId.of(string(object, "zone"));
        return new AlarmSchedule(days, time, zone);
    }

    private static Set<String> decodeExecutables(JsonObject object) {
        JsonElement element = object.get("executables");
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("process trigger needs an executables array");
        }
        Set<String> executables = new LinkedHashSet<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("executable must be a string");
            }
            executables.add(item.getAsString().toLowerCase(Locale.ROOT));
        }
        return executables;
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing string field: " + name);
        }
        return element.getAsString();
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("missing boolean field: " + name);
        }
        return element.getAsBoolean();
    }
}
