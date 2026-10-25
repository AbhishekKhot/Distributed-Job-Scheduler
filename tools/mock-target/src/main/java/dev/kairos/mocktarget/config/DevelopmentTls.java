package dev.kairos.mocktarget.config;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslStoreBundle;

/** A fresh, in-memory development CA and leaf. Private keys are never exported or persisted. */
public final class DevelopmentTls {
    private final SslBundle bundle;
    private final String caPem;

    public DevelopmentTls(Clock clock) {
        try {
            var random = new SecureRandom();
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), random);
            var ca = generator.generateKeyPair();
            var leaf = generator.generateKeyPair();
            var subject = new X500Name("CN=Kairos Mock Development CA");
            var start = Date.from(clock.instant().minus(Duration.ofMinutes(5)));
            var end = Date.from(clock.instant().plus(Duration.ofDays(7)));
            var issuer = new JcaContentSignerBuilder("SHA256withECDSA").build(ca.getPrivate());
            var builder = new JcaX509v3CertificateBuilder(
                    subject, new BigInteger(159, random).add(BigInteger.ONE), start, end, subject, ca.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            var converter = new JcaX509CertificateConverter();
            X509Certificate root = converter.getCertificate(builder.build(issuer));
            var server = new JcaX509v3CertificateBuilder(
                    subject,
                    new BigInteger(159, random).add(BigInteger.ONE),
                    start,
                    end,
                    new X500Name("CN=mock-target"),
                    leaf.getPublic());
            server.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            server.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            server.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            server.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
                new GeneralName(GeneralName.dNSName, "mock-target"), new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1"), new GeneralName(GeneralName.iPAddress, "::1")
            }));
            X509Certificate cert = converter.getCertificate(server.build(issuer));
            cert.verify(root.getPublicKey());
            root.verify(root.getPublicKey());
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            String password = Base64.getUrlEncoder().encodeToString(random.generateSeed(32));
            store.setKeyEntry(
                    "mock-target", leaf.getPrivate(), password.toCharArray(), new java.security.cert.Certificate[] {
                        cert, root
                    });
            bundle = SslBundle.of(SslStoreBundle.of(store, password, null), SslBundleKey.of(password, "mock-target"));
            caPem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(root.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create mock development TLS material.", failure);
        }
    }

    public SslBundle bundle() {
        return bundle;
    }

    public String caPem() {
        return caPem;
    }

    @Override
    public String toString() {
        return "DevelopmentTls[privateMaterial=<redacted>]";
    }
}
