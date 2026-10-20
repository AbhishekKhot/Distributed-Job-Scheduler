package dev.kairos.http.client;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds the TLS contexts of outbound clients: the JDK default trust store, optionally merged with a team's CA bundle.
 * Certificate chain validation is always PKIX; hostname verification is configured on the TLS strategy and is never
 * affected by a bundle.
 */
final class TlsContexts {

    /** TLS 1.3 and 1.2 only. */
    static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private TlsContexts() {}

    /**
     * Returns a context that trusts the JDK default trust store.
     *
     * @return the context
     */
    static SSLContext defaultContext() {
        return context(defaultTrustManagers());
    }

    /**
     * Returns a context that trusts the default trust store plus the bundle's certificates.
     *
     * @param bundle the team's CA bundle
     * @return the context
     */
    static SSLContext withBundle(CaBundle bundle) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            int index = 0;
            for (TrustManager manager : defaultTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    for (X509Certificate issuer : x509.getAcceptedIssuers()) {
                        store.setCertificateEntry("default-" + index++, issuer);
                    }
                }
            }
            for (X509Certificate certificate : bundle.certificates()) {
                store.setCertificateEntry("team-" + index++, certificate);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            return context(factory.getTrustManagers());
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("the CA bundle cannot be turned into a trust store", e);
        }
    }

    private static TrustManager[] defaultTrustManagers() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            return factory.getTrustManagers();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the default trust store cannot be loaded", e);
        }
    }

    private static SSLContext context(TrustManager[] trustManagers) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("a TLS context cannot be created", e);
        }
    }
}
