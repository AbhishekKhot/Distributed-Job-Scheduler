package dev.kairos.maintenance.commands.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validated operator intent. All input validation completes before opening a database. */
public sealed interface OperatorCommand {
    Set<String> QUOTAS =
            Set.of("maxJobs", "maxExecPerSec", "maxInflight", "maxWorkflowTasks", "backfillPerSec", "redrivePerSec");

    /** Only supplied quotas are changed; all other JSON properties are retained. */
    record SetQuota(String team, Map<String, Integer> quotas) implements OperatorCommand {
        public SetQuota {
            if (team == null || team.length() < 3 || team.length() > 63 || !team.matches("^[a-z][a-z0-9-]{2,62}$"))
                throw new IllegalArgumentException("Invalid team slug.");
            quotas = Map.copyOf(quotas);
            if (quotas.isEmpty()
                    || !QUOTAS.containsAll(quotas.keySet())
                    || quotas.values().stream().anyMatch(value -> value < 1))
                throw new IllegalArgumentException("Supply at least one positive integer quota.");
        }
    }

    record DisableUser(String email) implements OperatorCommand {
        public DisableUser {
            email = normalizeEmail(email);
        }
    }

    record ResetPassword(String email) implements OperatorCommand {
        public ResetPassword {
            email = normalizeEmail(email);
        }
    }

    /** Mirrors account email structure without introducing an application-to-application dependency. */
    private static String normalizeEmail(String email) {
        if (email == null || email.length() > 254) throw new IllegalArgumentException("Invalid email.");
        String normalized = email.toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (at < 1 || at > 64 || at != normalized.lastIndexOf('@'))
            throw new IllegalArgumentException("Invalid email.");
        String local = normalized.substring(0, at);
        if (local.startsWith(".") || local.endsWith(".") || local.contains(".."))
            throw new IllegalArgumentException("Invalid email.");
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            if (c <= ' ' || c >= 127 || "\"(),:;<>@[\\]".indexOf(c) >= 0)
                throw new IllegalArgumentException("Invalid email.");
        }
        String[] labels = normalized.substring(at + 1).split("\\.", -1);
        if (labels.length < 2) throw new IllegalArgumentException("Invalid email.");
        for (String label : labels) {
            if (label.isEmpty()
                    || label.length() > 63
                    || label.startsWith("-")
                    || label.endsWith("-")
                    || !label.matches("^[a-z0-9-]+$")) throw new IllegalArgumentException("Invalid email.");
        }
        return normalized;
    }
}
