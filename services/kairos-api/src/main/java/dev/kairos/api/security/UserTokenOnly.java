package dev.kairos.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reserves an operation (or every operation of a controller) for user access tokens. Called with an API key, it is
 * rejected with 403 {@code forbidden-for-api-key} before the method runs, for example member management, API key
 * creation and signing-key rotation (LLD section 3.4).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UserTokenOnly {}
