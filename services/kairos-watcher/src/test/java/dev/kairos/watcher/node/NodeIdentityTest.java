package dev.kairos.watcher.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NodeIdentityTest {
    @Test
    void watcherIdContainsServicePodAndProcessStart() {
        NodeIdentity identity = NodeIdentity.watcher("pod/one", Instant.ofEpochMilli(1234), "1.2.3");
        assertThat(identity.nodeId()).isEqualTo("kairos-watcher-pod-one-1234");
        assertThat(identity.role()).isEqualTo("watcher");
        assertThat(identity.version()).isEqualTo("1.2.3");
    }

    @Test
    void persistedFieldsAreBounded() {
        assertThatThrownBy(() -> new NodeIdentity("x".repeat(129), "watcher", "dev", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
