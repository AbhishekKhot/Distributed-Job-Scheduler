/**
 * Cross-cutting web support shared by every feature slice of the API: problem details, request ids, security
 * headers, ETags, cursors, idempotency, rate limits and the OpenAPI conventions. Owned by the API foundation task;
 * feature slices use it but never change it.
 */
@NullMarked
package dev.kairos.api.support;

import org.jspecify.annotations.NullMarked;
