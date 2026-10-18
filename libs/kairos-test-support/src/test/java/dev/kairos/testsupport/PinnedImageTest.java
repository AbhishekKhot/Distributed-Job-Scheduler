package dev.kairos.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

class PinnedImageTest {

    private static final String DIGEST = "77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";

    @Test
    void parsesNameTagAndDigest() {
        PinnedImage image = PinnedImage.parse("apache/kafka:4.3.1@sha256:" + DIGEST);

        assertThat(image.name()).isEqualTo("apache/kafka");
        assertThat(image.tag()).isEqualTo("4.3.1");
        assertThat(image.digest()).isEqualTo(DIGEST);
    }

    @Test
    void createsTheContainerFromTheDigestAlone() {
        DockerImageName name = PinnedImage.parse("apache/kafka:4.3.1@sha256:" + DIGEST)
                .toDockerImageName("apache/kafka");

        assertThat(name.asCanonicalNameString()).isEqualTo("apache/kafka@sha256:" + DIGEST);
        assertThat(name.isCompatibleWith(DockerImageName.parse("apache/kafka"))).isTrue();
        name.assertValid();
    }

    @Test
    void rejectsReferencesWithoutDigest() {
        assertThatThrownBy(() -> PinnedImage.parse("redis:8.8.3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReferencesWithoutTag() {
        assertThatThrownBy(() -> PinnedImage.parse("redis@sha256:" + DIGEST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsShortDigests() {
        assertThatThrownBy(() -> PinnedImage.parse("redis:8.8.3@sha256:abc123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongReferences() {
        assertThatThrownBy(() -> PinnedImage.parse("a".repeat(400))).isInstanceOf(IllegalArgumentException.class);
    }
}
