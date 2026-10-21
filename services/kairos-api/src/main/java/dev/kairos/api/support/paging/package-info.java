/**
 * Cursor pagination (LLD section 3.1): {@code limit} 1 to 200 (default 50), an opaque {@code cursor} authenticated
 * with an HMAC so it cannot be forged or edited, and the {@code {"items", "nextCursor"}} response shape.
 */
@NullMarked
package dev.kairos.api.support.paging;

import org.jspecify.annotations.NullMarked;
