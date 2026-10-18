package dev.kairos.testsupport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.utility.DockerImageName;

/**
 * An image reference of the form {@code name:tag@sha256:digest}, as written in {@code docker-compose.yml}.
 *
 * <p>Testcontainers does not parse a tag and a digest together (the tag ends up in the repository name), so the
 * container is created from {@code name@sha256:digest}. The digest alone fixes the image; the tag is kept only so the
 * reference reads the same as in compose.
 *
 * @param name the repository, for example {@code apache/kafka}
 * @param tag the tag, for example {@code 4.3.1}
 * @param digest the hex SHA-256 digest of the image
 */
record PinnedImage(String name, String tag, String digest) {

    private static final Pattern REFERENCE =
            Pattern.compile("^([a-z0-9][a-z0-9._/-]{0,127}):([A-Za-z0-9._-]{1,128})@sha256:([0-9a-f]{64})$");

    static PinnedImage parse(String reference) {
        if (reference.length() > 300) {
            throw new IllegalArgumentException("Image reference is too long");
        }
        Matcher matcher = REFERENCE.matcher(reference);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Expected name:tag@sha256:digest, got " + reference);
        }
        return new PinnedImage(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    /**
     * Returns the Testcontainers image name, declared compatible with the image a Testcontainers module expects.
     *
     * @param compatibleWith the image name the module checks against, for example {@code postgres}
     */
    DockerImageName toDockerImageName(String compatibleWith) {
        return DockerImageName.parse(name + "@sha256:" + digest).asCompatibleSubstituteFor(compatibleWith);
    }
}
