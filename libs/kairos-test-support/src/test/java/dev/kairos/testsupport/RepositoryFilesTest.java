package dev.kairos.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class RepositoryFilesTest {

    @Test
    void findsTheRepositoryRootAboveTheModule() {
        assertThat(RepositoryFiles.root().resolve("settings.gradle.kts")).isRegularFile();
        assertThat(RepositoryFiles.root().resolve("libs/kairos-test-support")).isDirectory();
    }

    @Test
    void resolvesComposeScripts() {
        assertThat(RepositoryFiles.resolve("deploy/compose/postgres/01-init-databases.sh"))
                .isAbsolute()
                .satisfies(path -> assertThat(Files.isRegularFile(path)).isTrue());
    }

    @Test
    void rejectsPathsOutsideTheRepository() {
        assertThatThrownBy(() -> RepositoryFiles.resolve("../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsolutePaths() {
        String absolute = RepositoryFiles.root().resolve("settings.gradle.kts").toString();

        assertThatThrownBy(() -> RepositoryFiles.resolve(absolute)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsMissingFiles() {
        assertThatThrownBy(() -> RepositoryFiles.resolve("deploy/compose/missing.sh"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.sh");
    }
}
