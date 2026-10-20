package dev.kairos.http.client;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates a throwaway PKI at test time: a CA and server certificates signed by it. Nothing is written to disk and
 * no private key is committed.
 */
final class TestPki {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestPki() {}

    /** A key pair and its certificate. */
    record Issued(KeyPair keys, X509Certificate certificate) {}

    static Issued ca(String commonName) {
        try {
            KeyPair keys = keyPair();
            X500Name name = new X500Name("CN=" + commonName);
            X509v3CertificateBuilder builder = builder(name, name, keys);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            return new Issued(keys, sign(builder, keys));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Issued server(Issued ca, String... dnsNames) {
        try {
            KeyPair keys = keyPair();
            X509v3CertificateBuilder builder = builder(
                    X500Name.getInstance(
                            ca.certificate().getSubjectX500Principal().getEncoded()),
                    new X500Name("CN=" + dnsNames[0]),
                    keys);
            GeneralName[] names = new GeneralName[dnsNames.length];
            for (int i = 0; i < dnsNames.length; i++) {
                names[i] = new GeneralName(GeneralName.dNSName, dnsNames[i]);
            }
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(
                    Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            return new Issued(keys, sign(builder, ca.keys()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static SSLContext serverContext(Issued server, Issued ca) {
        try {
            char[] password = Long.toHexString(RANDOM.nextLong()).toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("server", server.keys().getPrivate(), password, new X509Certificate[] {
                server.certificate(), ca.certificate()
            });
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(factory.getKeyManagers(), null, null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String pem(X509Certificate certificate) {
        try {
            StringWriter out = new StringWriter();
            out.write("-----BEGIN CERTIFICATE-----\n");
            out.write(Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded()));
            out.write("\n-----END CERTIFICATE-----\n");
            return out.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    private static X509v3CertificateBuilder builder(X500Name issuer, X500Name subject, KeyPair keys) {
        Instant now = Instant.now();
        return new JcaX509v3CertificateBuilder(
                issuer,
                new BigInteger(64, RANDOM),
                Date.from(now.minus(Duration.ofHours(1))),
                Date.from(now.plus(Duration.ofDays(1))),
                subject,
                keys.getPublic());
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, KeyPair signer) throws Exception {
        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(contentSigner));
    }
}
