plugins {
    id("kairos.library-conventions")
}

description = "Pure domain model and rules: ids, shards, schedules, retry policy, run state machine, DAG validation, templates."

// No framework dependencies are allowed here (enforced by the architecture tests).
