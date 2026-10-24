package dev.kairos.api.endpoints.application;

import java.util.UUID;

/** Cross-cell schedule changes applied after an endpoint status commits. */
public interface EndpointScheduleControl {
    void reconcile(UUID endpointId, boolean suspended);
}
