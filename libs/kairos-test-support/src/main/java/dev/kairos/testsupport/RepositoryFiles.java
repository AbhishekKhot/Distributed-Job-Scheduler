package dev.kairos.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates files of the Kairos repository from a running test.
 *
 * <p>Gradle runs each module's tests with the module directory as the working directory, so the
 * repository root is found by walking up from there until a directory holds both
 * {@code settings.gradle.kts} and {@code deploy/compose/docker-compose.yml}. The second marker
 * skips the included {@code build-logic} build, which has a settings file of its own.
 *
 * <p>Thread-safe and stateless apart from the lazily computed root.
 */
public final class RepositoryFiles {

    private static final String SETTINGS_FILE = "settings.gradle.kts";
    private static final String COMPOSE_FILE = "deploy/compose/docker-compose.yml";
    private static final Lazy<Path> ROOT = new Lazy<>(RepositoryFiles::findRoot);

    private RepositoryFiles() {}

    /**
     * Returns the repository root directory.
     *
     * @return the absolute, normalised root directory
     * @throws IllegalStateException if no parent of the working directory looks like the repository root
     */
    public static Path root() {
        return ROOT.get();
    }

    /**
     * Resolves a regular file inside the repository.
     *
     * @param relativePath a path relative to the repository root, using {@code /} as the separator, for example
     *     {@code deploy/compose/kafka/create-topics.sh}
     * @return the absolute path of the file
     * @throws IllegalArgumentException if the path is absolute or points outside the repository
     * @throws IllegalStateException if the file does not exist
     */
    public static Path resolve(String relativePath) {
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Expected a path relative to the repository root");
        }
        Path root = root();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path points outside the repository");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException("Repository file not found: " + root.relativize(resolved));
        }
        return resolved;
    }

    private static Path findRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(SETTINGS_FILE)) && Files.isRegularFile(dir.resolve(COMPOSE_FILE))) {
                return dir;
            }
        }
        throw new IllegalStateException("No Kairos repository root above the working directory " + start);
    }
}
