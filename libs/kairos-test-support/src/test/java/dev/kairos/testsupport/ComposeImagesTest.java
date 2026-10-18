package dev.kairos.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Keeps the test container images identical to the images of the local compose stack. */
class ComposeImagesTest {

    private static final Pattern IMAGE_LINE = Pattern.compile("^\\s*image:\\s*(\\S+)\\s*$");

    @Test
    void postgresImageMatchesCompose() throws IOException {
        assertThat(composeImages("postgres:")).containsExactly(KairosPostgres.IMAGE);
    }

    @Test
    void kafkaImageMatchesBothComposeServices() throws IOException {
        // The broker and the topic-creation job use the same image.
        assertThat(composeImages("apache/kafka:")).containsExactly(KairosKafka.IMAGE, KairosKafka.IMAGE);
    }

    @Test
    void redisImageMatchesCompose() throws IOException {
        assertThat(composeImages("redis:")).containsExactly(KairosRedis.IMAGE);
    }

    @Test
    void everyImageIsPinnedByDigest() {
        for (String image : List.of(KairosPostgres.IMAGE, KairosKafka.IMAGE, KairosRedis.IMAGE)) {
            assertThat(PinnedImage.parse(image).digest()).hasSize(64);
        }
    }

    private static List<String> composeImages(String prefix) throws IOException {
        List<String> lines = Files.readAllLines(
                RepositoryFiles.resolve("deploy/compose/docker-compose.yml"), StandardCharsets.UTF_8);
        return lines.stream()
                .map(IMAGE_LINE::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .filter(image -> image.startsWith(prefix))
                .toList();
    }
}
