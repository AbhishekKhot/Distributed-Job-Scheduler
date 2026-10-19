/**
 * Domain errors.
 *
 * <p>{@link dev.kairos.domain.error.ErrorCode} is the catalogue of problem types from LLD section
 * 3, and {@link dev.kairos.domain.error.KairosException} with its subclasses is how the domain and
 * use cases report them. Messages are fixed, client-safe sentences; only the API layer maps
 * exceptions to HTTP responses.
 */
package dev.kairos.domain.error;
