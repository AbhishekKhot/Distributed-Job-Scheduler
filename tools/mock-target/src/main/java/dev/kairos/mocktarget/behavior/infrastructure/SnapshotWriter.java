package dev.kairos.mocktarget.behavior.infrastructure;

import dev.kairos.mocktarget.behavior.application.CallTracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

/** Writes coherent versioned count snapshots atomically; never writes keys or request data to logs. */
public final class SnapshotWriter {
    private final CallTracker tracker;
    private final Path path;
    private final JsonMapper json = JsonMapper.builder().build();

    public SnapshotWriter(CallTracker tracker, Path path) {
        this.tracker = tracker;
        this.path = path.toAbsolutePath();
    }

    @Scheduled(
            fixedDelayString = "${kairos.mock-target.snapshot-interval:30s}",
            initialDelayString = "${kairos.mock-target.snapshot-interval:30s}")
    public synchronized void write() {
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".stats-", ".json");
            json.writeValue(temporary.toFile(), tracker.snapshot());
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            tracker.snapshotFailed();
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    /* Snapshot failure is counted; no request data is logged. */
                }
        }
    }
}
