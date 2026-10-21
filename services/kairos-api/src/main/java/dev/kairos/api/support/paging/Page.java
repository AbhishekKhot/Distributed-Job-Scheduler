package dev.kairos.api.support.paging;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list response, {@code {"items": [...], "nextCursor": "..."}} (LLD section 3.1).
 *
 * @param items the items of this page, unmodifiable
 * @param nextCursor the cursor of the next page, or {@code null} on the last page
 * @param <T> the item type
 */
@Schema(description = "One page of results.")
public record Page<T>(
        List<T> items,

        @Schema(description = "Opaque cursor of the next page; null on the last page.", nullable = true) @Nullable String nextCursor) {

    /** Validates the components and copies the items. */
    public Page {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
