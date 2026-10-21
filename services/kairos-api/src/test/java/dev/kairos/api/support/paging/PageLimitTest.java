package dev.kairos.api.support.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ValidationFailedException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageLimitTest {

    @Test
    void defaultsAndBounds() {
        assertThat(PageLimit.resolve(null)).isEqualTo(50);
        assertThat(PageLimit.resolve(1)).isEqualTo(1);
        assertThat(PageLimit.resolve(200)).isEqualTo(200);
        assertThatThrownBy(() -> PageLimit.resolve(0)).isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> PageLimit.resolve(201)).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void pagesCopyTheirItems() {
        List<String> items = new ArrayList<>(List.of("a"));
        Page<String> page = new Page<>(items, null);
        items.add("b");

        assertThat(page.items()).containsExactly("a");
        assertThat(page.nextCursor()).isNull();
    }
}
