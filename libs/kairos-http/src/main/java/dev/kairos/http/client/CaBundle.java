package dev.kairos.http.client;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A team's CA bundle ({@code team_ca_bundles.pem}, at most 64 KB): extra trust anchors for that team's targets.
 *
 * <p>The certificates are added to the default trust store for the clients built from this bundle only; hostname
 * verification stays on. The {@link #id()} combines the owner (team and bundle name) with a hash of the PEM, so two
 * teams never share a client and a changed bundle gets a new one.
 *
 * <p>Immutable and thread-safe.
 */
public final class CaBundle {

    /** Largest accepted PEM text (LLD section 2). */
    public static final int MAX_PEM_CHARS = 64 * 1024;

    /** Largest number of certificates in one bundle. */
    public static final int MAX_CERTIFICATES = 100;

    private static final int MAX_OWNER = 256;

    private final String id;
    private final List<X509Certificate> certificates;

    private CaBundle(String id, List<X509Certificate> certificates) {
        this.id = id;
        this.certificates = List.copyOf(certificates);
    }

    /**
     * Parses a PEM bundle.
     *
     * @param owner who owns the bundle, for example {@code <teamId>/<bundle name>}; part of the id
     * @param pem one or more PEM {@code CERTIFICATE} blocks
     * @return the bundle
     * @throws IllegalArgumentException if the PEM is too large, holds no certificate, too many, or does not parse
     */
    public static CaBundle parsePem(String owner, String pem) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(pem, "pem");
        if (owner.isEmpty() || owner.length() > MAX_OWNER) {
            throw new IllegalArgumentException("a CA bundle owner is 1 to " + MAX_OWNER + " characters");
        }
        if (pem.isEmpty() || pem.length() > MAX_PEM_CHARS) {
            throw new IllegalArgumentException("a CA bundle is 1 to " + MAX_PEM_CHARS + " characters");
        }
        byte[] bytes = pem.getBytes(StandardCharsets.US_ASCII);
        Collection<? extends Certificate> parsed;
        try {
            parsed = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            throw new IllegalArgumentException("the CA bundle is not valid PEM", e);
        }
        if (parsed.isEmpty() || parsed.size() > MAX_CERTIFICATES) {
            throw new IllegalArgumentException("a CA bundle holds 1 to " + MAX_CERTIFICATES + " certificates");
        }
        List<X509Certificate> certificates = new ArrayList<>(parsed.size());
        for (Certificate certificate : parsed) {
            if (!(certificate instanceof X509Certificate x509)) {
                throw new IllegalArgumentException("a CA bundle holds X.509 certificates only");
            }
            certificates.add(x509);
        }
        return new CaBundle(owner + "#" + sha256(bytes), certificates);
    }

    /**
     * Returns the identity of this bundle: owner plus content hash.
     *
     * @return the id
     */
    public String id() {
        return id;
    }

    /**
     * Returns the certificates.
     *
     * @return the unmodifiable certificates
     */
    public List<X509Certificate> certificates() {
        return certificates;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof CaBundle that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "CaBundle[id=" + id + ", certificates=" + certificates.size() + "]";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
