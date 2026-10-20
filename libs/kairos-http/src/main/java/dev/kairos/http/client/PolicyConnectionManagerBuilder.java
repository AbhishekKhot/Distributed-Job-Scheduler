package dev.kairos.http.client;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.config.RegistryBuilder;

/**
 * A pooling connection manager builder whose connection operator knows the {@code https} scheme only, so no plain-text
 * connection can be opened even if a request slipped past the URL check, and whose sockets come from the given factory.
 */
final class PolicyConnectionManagerBuilder extends PoolingHttpClientConnectionManagerBuilder {

    private final DetachedSocketFactory sockets;

    PolicyConnectionManagerBuilder(DetachedSocketFactory sockets) {
        this.sockets = sockets;
    }

    @Override
    protected HttpClientConnectionOperator createConnectionOperator(
            SchemePortResolver schemePortResolver, DnsResolver dnsResolver, TlsSocketStrategy tlsSocketStrategy) {
        return new DefaultHttpClientConnectionOperator(
                sockets,
                schemePortResolver,
                dnsResolver,
                RegistryBuilder.<TlsSocketStrategy>create()
                        .register("https", tlsSocketStrategy)
                        .build());
    }
}
