package github.gtouming.itemsync.desktop;

import java.nio.file.Path;
import java.util.Locale;

final class AutostartCommand {

    private AutostartCommand() {
    }

    static String resolve(String configured, String executable, Path dataDir) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (executable == null || executable.isBlank() || dataDir == null) {
            return null;
        }
        String exe = executable.trim();
        if (exe.startsWith("\"") && exe.endsWith("\"") && exe.length() > 1) {
            exe = exe.substring(1, exe.length() - 1);
        }
        String fileName;
        try {
            fileName = Path.of(exe).getFileName().toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
        if (!fileName.endsWith(".exe") || fileName.equals("java.exe") || fileName.equals("javaw.exe")) {
            return null;
        }
        return "\"" + exe + "\" --data \"" + dataDir.toAbsolutePath() + "\"";
    }

    static String currentExecutable() {
        String packaged = System.getProperty("jpackage.app-path");
        if (packaged != null && !packaged.isBlank()) {
            return packaged;
        }
        try {
            return ProcessHandle.current().info().command().orElse(null);
        } catch (SecurityException e) {
            return null;
        }
    }
}
