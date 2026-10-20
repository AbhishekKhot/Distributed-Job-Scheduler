package dev.kairos.security.keyset;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.integration.gcpkms.GcpKmsClient;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * The production key encryption key: a Cloud KMS symmetric key, reached with the workload's default credentials
 * (Workload Identity on GKE). The key material never leaves KMS; Kairos only holds the URI.
 *
 * <p>Immutable and thread-safe.
 */
public final class GcpKmsKeyEncryptionKey implements KeyEncryptionKey {

    /** The URI scheme of Cloud KMS keys. */
    public static final String SCHEME = "gcp-kms://";

    private static final int MAX_URI_LENGTH = 512;

    private final String uri;
    private final Aead aead;

    GcpKmsKeyEncryptionKey(String uri, Aead aead) {
        this.uri = requireValidUri(uri);
        this.aead = Objects.requireNonNull(aead, "aead");
    }

    /**
     * Connects to a Cloud KMS key with the default credentials.
     *
     * @param uri the key, {@code gcp-kms://projects/<p>/locations/<l>/keyRings/<r>/cryptoKeys/<k>}
     * @return the key
     * @throws IllegalArgumentException if the URI is not a Cloud KMS crypto key
     * @throws IllegalStateException if the KMS client cannot be created
     */
    public static GcpKmsKeyEncryptionKey connect(String uri) {
        requireValidUri(uri);
        try {
            Aead aead = new GcpKmsClient(uri).withDefaultCredentials().getAead(uri);
            return new GcpKmsKeyEncryptionKey(uri, aead);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot connect to the Cloud KMS key encryption key", e);
        }
    }

    /**
     * Checks that a URI names a Cloud KMS crypto key. Only the characters KMS resource names allow are accepted.
     *
     * @param uri the URI
     * @return the URI
     * @throws IllegalArgumentException if it does not
     */
    static String requireValidUri(String uri) {
        Objects.requireNonNull(uri, "uri");
        if (uri.length() > MAX_URI_LENGTH || !uri.startsWith(SCHEME)) {
            throw new IllegalArgumentException("the key encryption key must be a gcp-kms:// URI");
        }
        String[] parts = uri.substring(SCHEME.length()).split("/", -1);
        boolean shape = parts.length == 8
                && parts[0].equals("projects")
                && parts[2].equals("locations")
                && parts[4].equals("keyRings")
                && parts[6].equals("cryptoKeys");
        if (!shape) {
            throw new IllegalArgumentException("the key encryption key must name a Cloud KMS crypto key");
        }
        for (int i = 1; i < parts.length; i += 2) {
            if (!isResourceId(parts[i])) {
                throw new IllegalArgumentException("the key encryption key must name a Cloud KMS crypto key");
            }
        }
        return uri;
    }

    private static boolean isResourceId(String id) {
        if (id.isEmpty() || id.length() > 63) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean allowed =
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public Aead aead() {
        return aead;
    }

    @Override
    public String toString() {
        return "GcpKmsKeyEncryptionKey[" + uri + "]";
    }
}
