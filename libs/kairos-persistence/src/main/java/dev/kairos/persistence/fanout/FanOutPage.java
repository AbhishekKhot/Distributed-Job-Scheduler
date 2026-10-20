package dev.kairos.persistence.fanout;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list merged from every cell.
 *
 * <p>{@code next} is the sort key of the last item on the page when more items exist. The API layer turns it into the
 * opaque, authenticated {@code nextCursor}; passing it back as {@code after} continues the list exactly where this page
 * ended, because the key is unique and totally ordered.
 *
 * @param <T> the item type
 * @param <K> the sort key type
 * @param items the items of this page, in list order
 * @param next the key to continue after, or {@code null} on the last page
 */
public record FanOutPage<T, K>(List<T> items, @Nullable K next) {

    /**
     * Copies the items.
     */
    public FanOutPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    /**
     * Returns the key to continue after.
     *
     * @return the key, or empty on the last page
     */
    public Optional<K> nextKey() {
        return Optional.ofNullable(next);
    }

    /**
     * Tells whether more items follow this page.
     *
     * @return true if {@link #next()} is set
     */
    public boolean hasMore() {
        return next != null;
    }
}
