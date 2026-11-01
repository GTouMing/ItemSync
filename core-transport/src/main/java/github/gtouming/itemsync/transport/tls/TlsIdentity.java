package github.gtouming.itemsync.transport.tls;

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
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.Objects;

public final class TlsIdentity {

    public static final String PROTOCOL = "TLSv1.3";

    private static final String ALIAS = "itemsync";
    private static final char[] PASSWORD = "itemsync".toCharArray();

    private final KeyStore keyStore;
    private final X509Certificate certificate;
    private final String fingerprint;

    private TlsIdentity(KeyStore keyStore, X509Certificate certificate) {
        this.keyStore = keyStore;
        this.certificate = certificate;
        this.fingerprint = fingerprint(certificate);
    }

    public static TlsIdentity generate(String commonName) {
        Objects.requireNonNull(commonName, "commonName");
        if (commonName.isBlank()) {
            throw new IllegalArgumentException("common name must not be blank");
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
            keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), PASSWORD, new Certificate[] {certificate});
            return new TlsIdentity(keyStore, certificate);
        } catch (GeneralSecurityException | IOException | OperatorCreationException e) {
            throw new IllegalStateException("cannot generate TLS identity", e);
        }
    }

    public String fingerprint() {
        return fingerprint;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public SSLContext sslContext(String expectedPeerFingerprint) {
        Objects.requireNonNull(expectedPeerFingerprint, "expectedPeerFingerprint");
        try {
            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, PASSWORD);
            SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(
                    keyManagerFactory.getKeyManagers(),
                    new TrustManager[] {new PinnedTrustManager(expectedPeerFingerprint)},
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
            return HexFormat.of().formatHex(digest);
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot compute certificate fingerprint", e);
        }
    }
}
