package dev.kairos.http.client;

import java.io.IOException;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.HttpResponseInformationCallback;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.jspecify.annotations.Nullable;

/**
 * The request executor of every outbound client. HttpClient calls it with an open, TLS-established connection right
 * before it writes the request line, which is exactly where the {@link SendObserver} must run. A request without a
 * {@link CallState} is refused, so nothing can bypass the hook.
 */
final class ObservingRequestExecutor extends HttpRequestExecutor {

    @Override
    public ClassicHttpResponse execute(
            ClassicHttpRequest request,
            HttpClientConnection connection,
            @Nullable HttpResponseInformationCallback informationCallback,
            HttpContext context)
            throws IOException, HttpException {
        if (!(context.getAttribute(CallState.ATTRIBUTE) instanceof CallState state)) {
            throw new CallState.VetoedSend(null);
        }
        state.beforeSend();
        return super.execute(request, connection, informationCallback, context);
    }
}
