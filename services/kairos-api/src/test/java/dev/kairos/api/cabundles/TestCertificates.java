package dev.kairos.api.cabundles;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Generates public test certificate PEM; private keys stay in memory and are never returned. */
public final class TestCertificates {
    private TestCertificates() {}

    public static String pem(boolean ca, boolean canSignCertificates, Instant from, Instant to) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = generator.generateKeyPair();
            X500Name name = new X500Name("CN=Kairos test CA");
            var builder = new JcaX509v3CertificateBuilder(
                    name,
                    new BigInteger(120, new SecureRandom()),
                    Date.from(from),
                    Date.from(to),
                    name,
                    pair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            builder.addExtension(
                    Extension.keyUsage,
                    true,
                    new KeyUsage(
                            canSignCertificates ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
            byte[] der = builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate()))
                    .getEncoded();
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                    + "\n-----END CERTIFICATE-----\n";
        } catch (Exception failure) {
            throw new IllegalStateException("could not generate test certificate", failure);
        }
    }
}
