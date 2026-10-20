/**
 * The request a caller hands to the HTTP client.
 *
 * <p>{@link dev.kairos.http.request.OutboundRequest} is a fully rendered, validated request;
 * {@link dev.kairos.http.request.HeaderField} refuses CR, LF and other control characters so no header can be split.
 */
package dev.kairos.http.request;
