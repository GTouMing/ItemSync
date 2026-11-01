package github.gtouming.itemsync.platform.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class WindowsAutostart {

    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "ItemSync";

    private WindowsAutostart() {
    }

    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isEnabled() throws IOException {
        Process process = new ProcessBuilder(queryCommand()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = waitFor(process);
        return exitCode == 0 && output.contains(VALUE_NAME);
    }

    public static void enable(String launchCommand) throws IOException {
        Objects.requireNonNull(launchCommand, "launchCommand");
        if (launchCommand.isBlank()) {
            throw new IllegalArgumentException("launch command must not be blank");
        }
        run(enableCommand(launchCommand));
    }

    public static void disable() throws IOException {
        run(disableCommand());
    }

    static List<String> enableCommand(String launchCommand) {
        return List.of("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ",
                "/d", launchCommand, "/f");
    }

    static List<String> disableCommand() {
        return List.of("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f");
    }

    static List<String> queryCommand() {
        return List.of("reg", "query", RUN_KEY, "/v", VALUE_NAME);
    }

    private static void run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        int exitCode = waitFor(process);
        if (exitCode != 0) {
            throw new IOException("autostart command failed with exit code " + exitCode);
        }
    }

    private static int waitFor(Process process) throws IOException {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("autostart command interrupted", e);
        }
    }
}
