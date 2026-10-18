package dev.kairos.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class KairosContainersTest {

    @Test
    void namesCellProperties() {
        assertThat(KairosContainers.cellUrl(0)).isEqualTo("kairos.datasource.cells[0].url");
        assertThat(KairosContainers.cellUsername(1)).isEqualTo("kairos.datasource.cells[1].username");
        assertThat(KairosContainers.cellPassword(1)).isEqualTo("kairos.datasource.cells[1].password");
    }

    @Test
    void rejectsNegativeCells() {
        assertThatThrownBy(() -> KairosContainers.cellUrl(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectsTheContainersTheAnnotationAsksFor() {
        assertThat(KairosContainers.selection(AllContainers.class.getAnnotation(KairosIntegrationTest.class)))
                .containsExactly(KairosContainer.POSTGRES, KairosContainer.KAFKA, KairosContainer.REDIS);
        assertThat(KairosContainers.selection(RedisOnly.class.getAnnotation(KairosIntegrationTest.class)))
                .containsExactly(KairosContainer.REDIS);
        assertThat(KairosContainers.selection(NoContainers.class.getAnnotation(KairosIntegrationTest.class)))
                .isEmpty();
    }

    @Test
    void startingNothingReturnsNoProperties() {
        assertThat(KairosContainers.springProperties(Set.of())).isEmpty();
    }

    @KairosIntegrationTest
    private static final class AllContainers {}

    @KairosIntegrationTest(postgres = false, kafka = false)
    private static final class RedisOnly {}

    @KairosIntegrationTest(postgres = false, kafka = false, redis = false)
    private static final class NoContainers {}
}
