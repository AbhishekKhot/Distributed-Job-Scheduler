package dev.kairos.state.settle.application;

import dev.kairos.domain.retry.RetryPolicy;
import dev.kairos.domain.retry.StatusRules;
import dev.kairos.domain.retry.UnknownOutcomePolicy;

/** Immutable attempt policy resolved from pinned definitions and the owning team's defaults. */
public record RunPolicy(
        RetryPolicy retry, UnknownOutcomePolicy unknown, StatusRules statusRules, boolean completedEvents) {}
