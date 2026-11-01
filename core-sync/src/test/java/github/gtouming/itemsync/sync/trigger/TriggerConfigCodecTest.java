package github.gtouming.itemsync.sync.trigger;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TriggerConfigCodecTest {

    @Test
    void roundTripsAlarm() {
        TriggerConfig.Alarm alarm = new TriggerConfig.Alarm(new AlarmSchedule(
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                LocalTime.of(23, 30),
                ZoneId.of("Asia/Shanghai")));

        TriggerConfig decoded = TriggerConfigCodec.decode(TriggerConfigCodec.encode(alarm));

        assertEquals(alarm, decoded);
    }

    @Test
    void roundTripsDirectoryChange() {
        TriggerConfig config = new TriggerConfig.DirectoryChange();

        assertEquals(config, TriggerConfigCodec.decode(TriggerConfigCodec.encode(config)));
    }

    @Test
    void roundTripsProcessEventAndNormalizesExecutables() {
        TriggerConfig.ProcessEvent process = new TriggerConfig.ProcessEvent(
                Set.of("Code.exe"), true, false);

        TriggerConfig decoded = TriggerConfigCodec.decode(TriggerConfigCodec.encode(process));

        TriggerConfig.ProcessEvent event = assertInstanceOf(TriggerConfig.ProcessEvent.class, decoded);
        assertEquals(Set.of("code.exe"), event.executables());
        assertEquals(true, event.onStart());
        assertEquals(false, event.onStop());
    }

    @Test
    void rejectsUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> TriggerConfigCodec.decode("{\"type\":\"nope\"}"));
    }

    @Test
    void rejectsIncompleteAlarm() {
        assertThrows(IllegalArgumentException.class,
                () -> TriggerConfigCodec.decode("{\"type\":\"alarm\",\"time\":\"23:00\"}"));
    }
}
