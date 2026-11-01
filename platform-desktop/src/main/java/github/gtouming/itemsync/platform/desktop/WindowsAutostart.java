package github.gtouming.itemsync.platform.desktop;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        int exitCode = waitFor(process);
        return exitCode == 0 && output.contains(VALUE_NAME);
    }

    public static void enable(String launchCommand) throws IOException {
        Objects.requireNonNull(launchCommand, "launchCommand");
        if (launchCommand.isBlank()) {
            throw new IllegalArgumentException("launch command must not be blank");
        }
        Path file = Files.createTempFile("itemsync-autostart", ".reg");
        try {
            writeRegFile(file, launchCommand);
            run(List.of("reg", "import", file.toString()));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    static void writeRegFile(Path file, String launchCommand) throws IOException {
        String escaped = launchCommand.replace("\\", "\\\\").replace("\"", "\\\"");
        String content = "Windows Registry Editor Version 5.00\r\n\r\n"
                + "[" + RUN_KEY + "]\r\n"
                + "\"" + VALUE_NAME + "\"=\"" + escaped + "\"\r\n";
        byte[] body = content.getBytes(StandardCharsets.UTF_16LE);
        byte[] data = new byte[body.length + 2];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xFE;
        System.arraycopy(body, 0, data, 2, body.length);
        Files.write(file, data);
    }

    public static void disable() throws IOException {
        run(disableCommand());
    }

    static List<String> disableCommand() {
        return List.of("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f");
    }

    static List<String> queryCommand() {
        return List.of("reg", "query", RUN_KEY, "/v", VALUE_NAME);
    }

    private static void run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        int exitCode = waitFor(process);
        if (exitCode != 0) {
            throw new IOException("autostart command failed with exit code " + exitCode
                    + (output.isBlank() ? "" : ": " + output.trim()));
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
