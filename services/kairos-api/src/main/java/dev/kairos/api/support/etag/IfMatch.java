package dev.kairos.api.support.etag;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the version of a required {@code If-Match} header to a {@code long} controller parameter.
 *
 * <p>Use it on every {@code PUT}: {@code update(@IfMatch long version, ...)}. A missing header is rejected with 428
 * {@code if-match-required} and a malformed one with 412 {@code version-mismatch} before the use case runs; the use
 * case compares the version with the stored one (for example in its {@code UPDATE ... WHERE version = :expected}) and
 * raises 412 on a mismatch. The OpenAPI document lists the header for every operation that uses it.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IfMatch {}
