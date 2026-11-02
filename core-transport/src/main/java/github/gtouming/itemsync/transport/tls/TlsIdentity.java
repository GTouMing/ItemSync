package github.gtouming.itemsync.transport.tls;

import github.gtouming.itemsync.domain.compat.Compat;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Set;

public final class TlsIdentity {

    public static final String PROTOCOL = "TLSv1.3";

    private static final String ALIAS = "itemsync";
    private static final String DEFAULT_PASSWORD = "itemsync";

    private final KeyStore keyStore;
    private final X509Certificate certificate;
    private final char[] password;
    private final String fingerprint;

    private TlsIdentity(KeyStore keyStore, X509Certificate certificate, char[] password) {
        this.keyStore = keyStore;
        this.certificate = certificate;
        this.password = password.clone();
        this.fingerprint = fingerprint(certificate);
    }

    public static TlsIdentity generate(String commonName) {
        return generate(commonName, DEFAULT_PASSWORD.toCharArray());
    }

    public static TlsIdentity generate(String commonName, char[] password) {
        Objects.requireNonNull(commonName, "commonName");
        Objects.requireNonNull(password, "password");
        if (commonName.trim().isEmpty()) {
            throw new IllegalArgumentException("common name must not be blank");
        }
        if (password.length == 0) {
            throw new IllegalArgumentException("keystore password must not be empty");
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            X500Name subject = new X500Name("CN=" + commonName);
            BigInteger serial = new BigInteger(64, new SecureRandom()).abs();
            Instant now = Instant.now();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject,
                    serial,
                    Date.from(now.minus(1, ChronoUnit.MINUTES)),
                    Date.from(now.plus(3650, ChronoUnit.DAYS)),
                    subject,
                    keyPair.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), password, new Certificate[] {certificate});
            return new TlsIdentity(keyStore, certificate, password);
        } catch (GeneralSecurityException | IOException | OperatorCreationException e) {
            throw new IllegalStateException("cannot generate TLS identity", e);
        }
    }

    public static TlsIdentity load(Path file, char[] password) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(password, "password");
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, password);
            String alias = firstKeyAlias(keyStore);
            Key key = keyStore.getKey(alias, password);
            if (!(key instanceof PrivateKey)) {
                throw new IOException("identity keystore contains no private key");
            }
            Certificate certificate = keyStore.getCertificate(alias);
            if (!(certificate instanceof X509Certificate x509)) {
                throw new IOException("identity keystore contains no X.509 certificate");
            }
            return new TlsIdentity(keyStore, x509, password);
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot load TLS identity", e);
        }
    }

    public void save(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        Path temp = Files.createTempFile(parent, "identity-", ".p12");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                keyStore.store(out, password);
            }
            Compat.move(temp, absolute);
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot save TLS identity", e);
        } finally {
            Files.deleteIfExists(temp);
        }
        try {
            Files.setPosixFilePermissions(absolute, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX filesystems
        }
    }

    public String fingerprint() {
        return fingerprint;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public SSLContext sslContext(String expectedPeerFingerprint) {
        return sslContext(Compat.set(expectedPeerFingerprint));
    }

    public SSLContext sslContext(Set<String> expectedPeerFingerprints) {
        return sslContext(new PinnedTrustManager(expectedPeerFingerprints));
    }

    public SSLContext sslContext(PinnedTrustManager trustManager) {
        Objects.requireNonNull(trustManager, "trustManager");
        try {
            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(
                    keyManagerFactory.getKeyManagers(),
                    new TrustManager[] {trustManager},
                    new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot create TLS context", e);
        }
    }

    public static String fingerprint(X509Certificate certificate) {
        Objects.requireNonNull(certificate, "certificate");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return Compat.hex(digest);
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot compute certificate fingerprint", e);
        }
    }

    private static String firstKeyAlias(KeyStore keyStore) throws GeneralSecurityException, IOException {
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                return alias;
            }
        }
        throw new IOException("identity keystore contains no key entry");
    }
}
