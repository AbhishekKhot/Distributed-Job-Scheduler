/**
 * Outbound HTTP to job targets; the platform's SSRF boundary (HLD section 11, LLD section 3.5).
 *
 * <p>{@code policy} decides which URLs and addresses may be called, {@code dns} resolves host names and returns only
 * addresses the policy allows (so a target cannot rebind its name to an internal address between check and connect),
 * {@code request} holds the validated request a caller builds, {@code client} sends it over TLS 1.2+ with a response
 * cap and a send hook for the execution gates, {@code auth} applies target credentials, {@code redact} strips secrets
 * from errors and response snippets, and {@code config} wires everything from {@code kairos.target-policy.*} and
 * {@code kairos.http.*}.
 */
package dev.kairos.http;
