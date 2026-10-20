/**
 * The outbound HTTP client.
 *
 * <p>{@link dev.kairos.http.client.HttpClientFactory} builds pooled, TLS-only clients that resolve through the
 * validating resolver; {@link dev.kairos.http.client.OutboundHttpClient} sends one request and reports a
 * {@link dev.kairos.http.client.HttpCallOutcome} that says precisely whether the request was sent;
 * {@link dev.kairos.http.client.SendObserver} runs just before the request bytes are written (send-time guard and sent
 * marker, T-242); {@link dev.kairos.http.client.ResponseReader} caps responses at 256 KB and decodes JSON; and
 * {@link dev.kairos.http.client.CaBundle} adds a team's trust anchors to that team's clients only.
 */
package dev.kairos.http.client;
