package dev.kairos.engine.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Parsing {@code INFO replication}. */
class RedisTopologyTest {

    private static final String REPLID = "8c0f4b6e3c1e6f4a2a1d9b5e7f3c2d1a0b9e8f7d";

    @Test
    void parsesAPrimary() {
        String info = "# Replication\r\nrole:master\r\nconnected_slaves:0\r\nmaster_failover_state:no-failover\r\n"
                + "master_replid:" + REPLID + "\r\nmaster_replid2:0000000000000000000000000000000000000000\r\n"
                + "master_repl_offset:1234\r\n";

        assertThat(RedisTopology.parse(info)).contains(new RedisTopology(RedisTopology.Role.PRIMARY, REPLID, 1234));
    }

    @Test
    void parsesAReplicaAndUnknownRoles() {
        String replica = "role:slave\nmaster_replid:" + REPLID + "\nmaster_repl_offset:0\n";
        String other = "role:sentinel\nmaster_replid:" + REPLID + "\nmaster_repl_offset:0\n";

        assertThat(RedisTopology.parse(replica))
                .hasValueSatisfying(topology -> assertThat(topology.role()).isEqualTo(RedisTopology.Role.REPLICA));
        assertThat(RedisTopology.parse(other))
                .hasValueSatisfying(topology -> assertThat(topology.role()).isEqualTo(RedisTopology.Role.UNKNOWN));
    }

    @Test
    void rejectsIncompleteOrMalformedReplies() {
        assertThat(RedisTopology.parse("")).isEmpty();
        assertThat(RedisTopology.parse("role:master\nmaster_repl_offset:1\n")).isEmpty();
        assertThat(RedisTopology.parse("role:master\nmaster_replid:" + REPLID + "\n"))
                .isEmpty();
        assertThat(RedisTopology.parse("master_replid:" + REPLID + "\nmaster_repl_offset:1\n"))
                .isEmpty();
        assertThat(RedisTopology.parse("role:master\nmaster_replid:xyz\nmaster_repl_offset:1\n"))
                .isEmpty();
        assertThat(RedisTopology.parse("role:master\nmaster_replid:" + REPLID + "\nmaster_repl_offset:x\n"))
                .isEmpty();
        assertThat(RedisTopology.parse("role:master\nmaster_replid:" + REPLID + "\nmaster_repl_offset:-1\n"))
                .isEmpty();
        assertThat(RedisTopology.parse(":x\n" + "a".repeat(RedisTopology.MAX_INFO_LENGTH)))
                .isEmpty();
        assertThatThrownBy(() -> new RedisTopology(RedisTopology.Role.PRIMARY, REPLID, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
