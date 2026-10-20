package dev.kairos.security.token;

import com.google.crypto.tink.AccessesPartialKey;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeyStatus;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.signature.EcdsaParameters;
import com.google.crypto.tink.signature.EcdsaPrivateKey;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The access-token signing keys, read from the platform's {@code JWT_SIGNING} Tink keyset.
 *
 * <p>The primary key signs; every enabled key verifies and is published in the JWKS, so tokens signed before a rotation
 * stay valid until they expire. Each key's {@code kid} is {@code k} followed by its Tink key id in eight hex digits.
 * Only ECDSA P-256 / SHA-256 keys are accepted, which is exactly ES256.
 *
 * <p>Immutable and thread-safe. Private keys never leave this object and never appear in {@link #toString()}.
 */
public final class JwtKeyRing {

    private final String primaryKid;
    private final ECDSASigner signer;
    private final Map<String, ECDSAVerifier> verifiers;
    private final Map<String, Object> jwks;

    private JwtKeyRing(
            String primaryKid, ECDSASigner signer, Map<String, ECDSAVerifier> verifiers, Map<String, Object> jwks) {
        this.primaryKid = primaryKid;
        this.signer = signer;
        this.verifiers = verifiers;
        this.jwks = jwks;
    }

    /**
     * Reads the keys of an opened {@code JWT_SIGNING} keyset.
     *
     * @param keyset the keyset, with private keys
     * @return the key ring
     * @throws IllegalArgumentException if the keyset holds a key that is not ECDSA P-256 with SHA-256
     */
    @AccessesPartialKey
    public static JwtKeyRing fromKeyset(KeysetHandle keyset) {
        Map<String, ECDSAVerifier> verifiers = new HashMap<>();
        List<JWK> published = new ArrayList<>();
        @Nullable String primaryKid = null;
        @Nullable ECDSASigner signer = null;
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            for (int i = 0; i < keyset.size(); i++) {
                KeysetHandle.Entry entry = keyset.getAt(i);
                if (!entry.getStatus().equals(KeyStatus.ENABLED)) {
                    continue;
                }
                if (!(entry.getKey() instanceof EcdsaPrivateKey key) || !isEs256(key.getParameters())) {
                    throw new IllegalArgumentException("the access-token keyset must hold ES256 keys only");
                }
                String kid = kid(entry.getId());
                ECParameterSpec spec = key.getParameters().getCurveType().toParameterSpec();
                ECPublicKey publicKey = (ECPublicKey) factory.generatePublic(
                        new ECPublicKeySpec(key.getPublicKey().getPublicPoint(), spec));
                verifiers.put(kid, new ECDSAVerifier(publicKey));
                published.add(new ECKey.Builder(Curve.P_256, publicKey)
                        .keyID(kid)
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.ES256)
                        .build());
                if (entry.isPrimary()) {
                    ECPrivateKey privateKey = (ECPrivateKey) factory.generatePrivate(new ECPrivateKeySpec(
                            key.getPrivateValue().getBigInteger(InsecureSecretKeyAccess.get()), spec));
                    primaryKid = kid;
                    signer = new ECDSASigner(privateKey);
                }
            }
        } catch (GeneralSecurityException | JOSEException e) {
            throw new IllegalArgumentException("the access-token keyset cannot be read", e);
        }
        if (primaryKid == null || signer == null) {
            throw new IllegalArgumentException("the access-token keyset has no enabled primary key");
        }
        Map<String, Object> jwks = new JWKSet(published).toJSONObject(true);
        return new JwtKeyRing(primaryKid, signer, Map.copyOf(verifiers), Map.copyOf(jwks));
    }

    private static boolean isEs256(EcdsaParameters parameters) {
        return parameters.getCurveType().equals(EcdsaParameters.CurveType.NIST_P256)
                && parameters.getHashType().equals(EcdsaParameters.HashType.SHA256);
    }

    static String kid(int keyId) {
        return "k" + String.format(Locale.ROOT, "%08x", keyId);
    }

    String primaryKid() {
        return primaryKid;
    }

    ECDSASigner signer() {
        return signer;
    }

    Optional<ECDSAVerifier> verifier(String kid) {
        return Optional.ofNullable(verifiers.get(kid));
    }

    /**
     * Returns the public keys as a JWK Set (RFC 7517), the body of {@code GET /.well-known/jwks.json}.
     *
     * @return an unmodifiable JSON object with a {@code keys} array of public EC keys
     */
    public Map<String, Object> jwks() {
        return jwks;
    }

    /** Lists the key ids only. */
    @Override
    public String toString() {
        return "JwtKeyRing[primary=" + primaryKid + ", kids=" + verifiers.keySet() + "]";
    }
}
