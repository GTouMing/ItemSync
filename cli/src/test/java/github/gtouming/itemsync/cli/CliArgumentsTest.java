package github.gtouming.itemsync.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliArgumentsTest {

    @Test
    void parsesCommandAndOptions() {
        CliArguments arguments = CliArguments.parse(
                new String[] {"serve", "--data", "/tmp/itemsync", "--port", "47800"});

        assertEquals("serve", arguments.command());
        assertEquals("/tmp/itemsync", arguments.required("data"));
        assertEquals("47800", arguments.optional("port", "0"));
    }

    @Test
    void parsesInlineValues() {
        CliArguments arguments = CliArguments.parse(
                new String[] {"sync", "--data=/tmp/itemsync", "--root=/tmp/root"});

        assertEquals("/tmp/itemsync", arguments.required("data"));
        assertEquals("/tmp/root", arguments.required("root"));
    }

    @Test
    void keepsRepeatedOptions() {
        CliArguments arguments = CliArguments.parse(new String[] {
                "serve", "--peer", "host-a:1:fingerprint-a", "--peer", "host-b:2:fingerprint-b"});

        assertEquals(List.of("host-a:1:fingerprint-a", "host-b:2:fingerprint-b"),
                arguments.all("peer"));
        assertEquals("host-b:2:fingerprint-b", arguments.required("peer"));
    }

    @Test
    void rejectsMissingValue() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[] {"serve", "--data"}));
    }

    @Test
    void requiresOptions() {
        CliArguments arguments = CliArguments.parse(new String[] {"serve"});

        assertThrows(IllegalArgumentException.class, () -> arguments.required("data"));
    }
}
