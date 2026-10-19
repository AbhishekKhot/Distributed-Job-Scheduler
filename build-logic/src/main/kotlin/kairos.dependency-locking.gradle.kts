// Locks every resolvable configuration of a project, so a build resolves exactly the versions
// recorded in its gradle.lockfile. `make locks` rewrites the lockfiles with resolveAndLockAll.

dependencyLocking {
    lockAllConfigurations()
}

tasks.register("resolveAndLockAll") {
    description = "Resolves every resolvable configuration so --write-locks can record it."
    group = "help"
    // Captured as plain file collections rather than read from the configuration container in a
    // task action, which keeps the task compatible with the configuration cache. Gradle never
    // reuses a cache entry for a --write-locks build, so the lock state is always recomputed.
    val resolvable = files(configurations.matching { it.isCanBeResolved })
    val writingLocks = gradle.startParameter.isWriteDependencyLocks
    doFirst {
        require(writingLocks) { "Run with --write-locks" }
    }
    doLast {
        resolvable.files
    }
}
