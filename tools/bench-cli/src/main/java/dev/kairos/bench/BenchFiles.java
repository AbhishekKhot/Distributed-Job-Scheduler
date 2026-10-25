package dev.kairos.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Session files are private because the seed manifest contains the API key and signing key. */
final class BenchFiles {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<PosixFilePermission> PRIVATE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path directory;

    BenchFiles(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
    }

    Path path(String name) {
        return directory.resolve(name);
    }

    JsonNode read(String name) throws IOException {
        return JSON.readTree(Files.readString(path(name)));
    }

    void write(String name, Object value) throws IOException {
        writeText(name, JSON.writeValueAsString(value));
    }

    void writeText(String name, String value) throws IOException {
        Path target = path(name);
        Path temporary = Files.createTempFile(directory, ".bench-", ".tmp");
        try {
            privateFile(temporary);
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void privateFile(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PRIVATE);
        }
    }
}
