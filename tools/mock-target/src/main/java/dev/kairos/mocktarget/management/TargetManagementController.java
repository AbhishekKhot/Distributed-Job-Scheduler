package dev.kairos.mocktarget.management;

import dev.kairos.mocktarget.behavior.application.CallTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public CA only and aggregate per-instance counters; request bodies and signing keys are never exposed. */
@RestController
public final class TargetManagementController {
    private final CallTracker tracker;
    private final String ca;

    public TargetManagementController(CallTracker tracker, String ca) {
        this.tracker = tracker;
        this.ca = ca;
    }

    @GetMapping(path = "/ca.pem", produces = "application/x-pem-file")
    public String ca() {
        return ca;
    }

    @GetMapping(path = "/stats", produces = "application/json")
    public CallTracker.Stats stats() {
        return tracker.stats();
    }

    @GetMapping(path = "/stats/snapshot", produces = "application/json")
    public CallTracker.Snapshot snapshot() {
        return tracker.snapshot();
    }
}
