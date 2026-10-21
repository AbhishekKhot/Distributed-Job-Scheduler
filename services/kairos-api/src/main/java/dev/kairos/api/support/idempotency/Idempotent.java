package dev.kairos.api.support.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a create operation as idempotent: it accepts an optional {@code Idempotency-Key} header (1 to 64 visible
 * ASCII characters). The OpenAPI document lists the header for every operation that carries this annotation.
 *
 * <p>The record is stored where the created resource lives, chosen by {@link #store()}, so the record and the resource
 * are in the same database.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * Where the idempotency record is stored.
     *
     * @return the store
     */
    IdempotencyScope store();
}
