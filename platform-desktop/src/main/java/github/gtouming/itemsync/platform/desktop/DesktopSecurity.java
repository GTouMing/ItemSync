package github.gtouming.itemsync.platform.desktop;

import github.gtouming.itemsync.domain.compat.Compat;
import github.gtouming.itemsync.domain.security.LocalPassword;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class DesktopSecurity {

    private final Path dataDir;

    public DesktopSecurity(Path dataDir) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath();
    }

    public boolean hasNuk() {
        return Files.isRegularFile(nukFile());
    }

    public char[] loadNuk() throws IOException {
        if (!hasNuk()) {
            return null;
        }
        try {
            byte[] protectedBytes = Files.readAllBytes(nukFile());
            byte[] plaintext = WindowsDpapi.unprotect(protectedBytes);
            return new String(plaintext, StandardCharsets.UTF_8).toCharArray();
        } catch (RuntimeException e) {
            throw new IOException("cannot unprotect the node unlock key", e);
        }
    }

    public void saveNuk(char[] secret) throws IOException {
        Objects.requireNonNull(secret, "secret");
        byte[] plaintext = new String(secret).getBytes(StandardCharsets.UTF_8);
        byte[] protectedBytes;
        try {
            protectedBytes = WindowsDpapi.protect(plaintext);
        } catch (RuntimeException e) {
            throw new IOException("cannot protect the node unlock key", e);
        }
        writeAtomically(nukFile(), protectedBytes);
    }

    public char[] identitySecret(char[] fallback) throws IOException {
        char[] nuk = loadNuk();
        return nuk != null ? nuk : fallback;
    }

    public boolean hasLocalPassword() {
        return Files.isRegularFile(localPasswordFile())
                && localPasswordFile().toFile().length() > 0;
    }

    public void setLocalPassword(char[] password) throws IOException {
        byte[] hash = LocalPassword.hash(password).getBytes(StandardCharsets.UTF_8);
        writeAtomically(localPasswordFile(), hash);
    }

    public boolean verifyLocalPassword(char[] password) throws IOException {
        if (!hasLocalPassword()) {
            return false;
        }
        String hash = new String(Files.readAllBytes(localPasswordFile()),
                StandardCharsets.UTF_8).trim();
        try {
            return LocalPassword.verify(password, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Path nukFile() {
        return dataDir.resolve("nuk.bin");
    }

    private Path localPasswordFile() {
        return dataDir.resolve("local-password");
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".itemsync-", ".tmp");
        try {
            Files.write(temp, content);
            Compat.move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
