package dev.kairos.bench;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

/** Cross-checks accepted occurrences, both cell histories and all mock-target replicas. */
@CommandLine.Command(name = "verify", mixinStandardHelpOptions = true)
final class VerifyCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    BenchCommand parent;

    @CommandLine.Option(names = "--wait-seconds", defaultValue = "120")
    int waitSeconds;

    @CommandLine.Option(
            names = "--chaos",
            description = "Allow recovery lag caused by a recorded fault; retain all delivery checks")
    boolean chaos;

    @Override
    public Integer call() throws Exception {
        if (waitSeconds < 0 || waitSeconds > 3_600) throw new IllegalArgumentException("Invalid wait time");
        BenchFiles files = parent.files();
        JsonNode session = files.read("session.json");
        JsonNode load = files.read("load.json");
        Instant started = Instant.parse(BenchApi.required(load, "startedAt"));
        Instant ended = Instant.parse(BenchApi.required(load, "endedAt"));
        String team = BenchApi.required(session, "teamId");
        Set<String> runIds = new HashSet<>();
        Set<String> immediateJobs = new HashSet<>();
        for (String line : Files.readAllLines(files.path("expected.ndjson"))) {
            String[] parts = line.split(" ", 2);
            if (parts.length != 2) throw new IllegalStateException("Corrupt expected run journal");
            if (parts[0].equals("RUN")) runIds.add(parts[1]);
            else if (parts[0].equals("JOB")) immediateJobs.add(parts[1]);
            else throw new IllegalStateException("Unknown expected run record");
        }
        Set<String> cronJobs = new HashSet<>();
        Set<String> intervalJobs = new HashSet<>();
        if (load.path("expectedCronFires").asLong() > 0) {
            for (JsonNode item : session.path("cronJobs")) cronJobs.add(item.asString());
        }
        if (load.path("driver").asString().equals("interval")) {
            for (JsonNode item : session.path("reusableJobs")) intervalJobs.add(item.asString());
            for (JsonNode item : load.path("rampJobs")) intervalJobs.add(item.asString());
        }
        History history = null;
        Instant deadline = Instant.now().plusSeconds(waitSeconds);
        do {
            history = history(team, started.minusSeconds(5), ended.plusSeconds(300), started, ended);
            if (history.contains(runIds, immediateJobs, cronJobs)
                    && history.intervalMissing(
                                    intervalJobs, session.path("intervalSec").asInt(), started, ended, null)
                            == 0) break;
            if (Instant.now().isAfter(deadline)) break;
            Thread.sleep(2_000);
        } while (true);
        if (history == null) throw new IllegalStateException("History could not be read");
        History verifiedHistory = history;
        List<JsonNode> baselines = new ArrayList<>();
        for (JsonNode node : files.read("baseline.json")) baselines.add(node);
        JsonNode urls = load.path("managementUrls");
        if (urls.size() != baselines.size()) throw new IllegalStateException("Snapshot replica count changed");
        Map<String, Long> calls = new HashMap<>();
        Map<String, Long> effects = new HashMap<>();
        long signatureFailures = 0;
        long capacityRejected = 0;
        boolean trackingComplete = true;
        for (int i = 0; i < urls.size(); i++) {
            JsonNode before = baselines.get(i);
            JsonNode after = new BenchApi(urls.get(i).asString()).get("stats/snapshot", null);
            if (!before.path("stats")
                    .path("instanceId")
                    .asString()
                    .equals(after.path("stats").path("instanceId").asString())) {
                throw new IllegalStateException("Mock-target replica restarted; counts cannot be verified");
            }
            if (!after.path("stats").path("trackingComplete").asBoolean()) trackingComplete = false;
            signatureFailures += delta(before, after, "signatureFailures");
            capacityRejected += delta(before, after, "capacityRejected");
            Map<String, Long> previous = counts(before);
            counts(after).forEach((key, value) -> {
                long difference = value - previous.getOrDefault(key, 0L);
                if (difference < 0) throw new IllegalStateException("Target count went backwards");
                if (difference > 0) calls.merge(key, difference, Long::sum);
            });
            Map<String, Long> previousEffects = effectCounts(before);
            effectCounts(after).forEach((key, value) -> {
                long difference = value - previousEffects.getOrDefault(key, 0L);
                if (difference < 0) throw new IllegalStateException("Target effect count went backwards");
                if (difference > 0) effects.merge(key, difference, Long::sum);
            });
        }
        Set<String> expected = new HashSet<>(runIds);
        int missingHistory = 0;
        for (String id : runIds) if (!history.rows.containsKey(id)) missingHistory++;
        for (String job : immediateJobs) {
            List<History.Row> rows = history.byJob.getOrDefault(job, List.of());
            if (rows.size() != 1) missingHistory++;
            else expected.add(rows.getFirst().runId());
        }
        for (String job : cronJobs) {
            List<History.Row> rows = history.byJob.getOrDefault(job, List.of());
            if (rows.size() != 1) missingHistory++;
            else expected.add(rows.getFirst().runId());
        }
        missingHistory += history.intervalMissing(
                intervalJobs, session.path("intervalSec").asInt(), started, ended, expected);
        int missingTarget = 0;
        for (String id : expected) {
            if (history.rows.containsKey(id) && !effects.containsKey(id)) missingTarget++;
        }
        long redeliveries = calls.values().stream()
                .mapToLong(count -> Math.max(0L, count - 1))
                .sum();
        long duplicates = effects.values().stream()
                .mapToLong(count -> Math.max(0L, count - 1))
                .sum();
        Set<String> historyIds = history.rows.keySet();
        long unexpected = calls.keySet().stream()
                .filter(id -> !expected.contains(id) && !historyIds.contains(id))
                .count();
        long missing = missingHistory + missingTarget;
        long offered = load.path("offered").asLong();
        long accepted = load.path("accepted").asLong();
        long failures = load.path("requestFailures").asLong();
        long dropped = load.path("capacityDropped").asLong();
        long elapsedMillis = Math.max(1L, Duration.between(started, ended).toMillis());
        Map<String, Long> outcomeMix = new java.util.TreeMap<>();
        for (String id : expected) {
            History.Row row = history.rows.get(id);
            if (row != null) outcomeMix.merge(outcome(row.outcome()), 1L, Long::sum);
        }
        List<Long> lags = history.rows.values().stream()
                .filter(row -> expected.contains(row.runId()))
                .filter(row -> verifiedHistory.startedAt(row) != null)
                .map(row -> Math.max(
                        0L,
                        Duration.between(row.scheduledAt(), verifiedHistory.startedAt(row))
                                .toMillis()))
                .sorted()
                .toList();
        double p50 = percentile(lags, .50);
        double p95 = percentile(lags, .95);
        double p99 = percentile(lags, .99);
        boolean passed = offered == accepted
                && failures == 0
                && dropped == 0
                && trackingComplete
                && missing == 0
                && duplicates == 0
                && (unexpected == 0 || load.path("driver").asString().equals("interval"))
                && history.dlqCount == 0
                && signatureFailures == 0
                && capacityRejected == 0
                && lags.size() == expected.size()
                && (chaos || !load.path("profile").asString().equals("smoke") || p99 <= 2_000);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("profile", load.path("profile").asString());
        report.put("diagnostic", load.path("diagnostic").asBoolean());
        report.put("chaos", chaos);
        report.put("startedAt", started.toString());
        report.put("endedAt", ended.toString());
        report.put("passed", passed);
        report.put("offered", offered);
        report.put("accepted", accepted);
        report.put("requestFailures", failures);
        report.put("capacityDropped", dropped);
        report.put("achievedExecutionsPerSecond", Math.round(expected.size() * 1_000d / elapsedMillis));
        report.put("scheduleLagP50Ms", p50);
        report.put("scheduleLagP95Ms", p95);
        report.put("scheduleLagP99Ms", p99);
        report.put("lagSamples", lags.size());
        report.put("outcomeMix", outcomeMix);
        report.put("duplicates", duplicates);
        report.put("redeliveries", redeliveries);
        report.put("missingRuns", missing);
        report.put("unexpectedTargetKeys", unexpected);
        report.put("dlqCount", history.dlqCount);
        report.put("signatureFailures", signatureFailures);
        report.put("capacityRejected", capacityRejected);
        report.put("trackingComplete", trackingComplete);
        if (chaos) {
            JsonNode fault = files.read("fault.json");
            Instant faultStarted = Instant.parse(BenchApi.required(fault, "startedAt"));
            Instant faultRestored = Instant.parse(BenchApi.required(fault, "restoredAt"));
            List<History.Row> affected = history.rows.values().stream()
                    .filter(row -> !row.scheduledAt().isBefore(faultStarted)
                            && !row.scheduledAt().isAfter(faultRestored))
                    .toList();
            long recoverySeconds = affected.stream()
                    .filter(row -> verifiedHistory.startedAt(row) != null)
                    .mapToLong(row -> Math.max(
                            0,
                            Duration.between(faultRestored, verifiedHistory.startedAt(row))
                                    .toSeconds()))
                    .max()
                    .orElse(-1);
            long maxFaultLagSeconds = affected.stream()
                    .filter(row -> verifiedHistory.startedAt(row) != null)
                    .mapToLong(row -> Math.max(
                            0,
                            Duration.between(row.scheduledAt(), verifiedHistory.startedAt(row))
                                    .toSeconds()))
                    .max()
                    .orElse(-1);
            report.put("faultAffectedRuns", affected.size());
            report.put("deliveryRecoverySeconds", recoverySeconds);
            report.put("faultMaxLagSeconds", maxFaultLagSeconds);
        }
        files.write("verification.json", report);
        ReportCommand.render(files, report);
        System.out.printf(
                "%s: %s; %,d/s, lag p99 %.0f ms, duplicate effects %d, redeliveries %d, missing %d, DLQ %d%n",
                load.path("profile").asString(),
                passed ? "PASS" : "FAIL",
                report.get("achievedExecutionsPerSecond"),
                p99,
                duplicates,
                redeliveries,
                missing,
                history.dlqCount);
        return passed ? 0 : 1;
    }

    static Map<String, Long> counts(JsonNode snapshot) {
        return countMap(snapshot.path("counts"));
    }

    static Map<String, Long> effectCounts(JsonNode snapshot) {
        JsonNode effects = snapshot.path("effectCounts");
        return effects.isObject() ? countMap(effects) : counts(snapshot);
    }

    private static Map<String, Long> countMap(JsonNode counts) {
        Map<String, Long> result = new HashMap<>();
        counts.properties()
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().asLong()));
        return result;
    }

    private static long delta(JsonNode before, JsonNode after, String field) {
        long value = after.path("stats").path(field).asLong()
                - before.path("stats").path(field).asLong();
        if (value < 0) throw new IllegalStateException("Target counter went backwards: " + field);
        return value;
    }

    static double percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) return -1;
        int index = Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1);
        return sorted.get(index);
    }

    private History history(String team, Instant from, Instant to, Instant windowStart, Instant windowEnd)
            throws Exception {
        History history = new History();
        for (String url : List.of(parent.cellUrl(0), parent.cellUrl(1))) {
            try (Connection connection = DriverManager.getConnection(url, parent.dbUser(), parent.dbPassword())) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT run_id, definition_id, outcome, scheduled_at, started_at, final "
                                + "FROM scheduler.run_history WHERE team_id = ?::uuid "
                                + "AND scheduled_at >= ? AND scheduled_at < ?")) {
                    query.setString(1, team);
                    query.setTimestamp(2, Timestamp.from(from));
                    query.setTimestamp(3, Timestamp.from(to));
                    query.setFetchSize(2_000);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            String runId = rows.getString(1);
                            Instant startedAt = rows.getTimestamp(5) == null
                                    ? null
                                    : rows.getTimestamp(5).toInstant();
                            if (startedAt != null) {
                                history.firstStartedAt.merge(
                                        runId,
                                        startedAt,
                                        (first, candidate) -> first.isBefore(candidate) ? first : candidate);
                            }
                            if (!rows.getBoolean(6)) continue;
                            History.Row row = new History.Row(
                                    runId,
                                    rows.getString(2),
                                    rows.getInt(3),
                                    rows.getTimestamp(4).toInstant(),
                                    startedAt);
                            history.rows.put(row.runId(), row);
                            history.byJob
                                    .computeIfAbsent(row.definitionId(), ignored -> new ArrayList<>())
                                    .add(row);
                            history.byOccurrence.put(
                                    row.definitionId() + "|" + row.scheduledAt().toEpochMilli(), row);
                        }
                    }
                }
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT j.job_id, COALESCE(s.next_fire_at, j.created_at), j.created_at "
                                + "FROM scheduler.jobs j JOIN scheduler.schedules s ON s.schedule_id = j.job_id "
                                + "WHERE j.team_id = ?::uuid")) {
                    query.setString(1, team);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            history.jobAnchors.put(
                                    rows.getString(1), rows.getTimestamp(2).toInstant());
                            history.jobCreated.put(
                                    rows.getString(1), rows.getTimestamp(3).toInstant());
                        }
                    }
                }
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT count(*) FROM scheduler.dead_letters WHERE team_id = ?::uuid "
                                + "AND scheduled_at >= ? AND scheduled_at < ?")) {
                    query.setString(1, team);
                    query.setTimestamp(2, Timestamp.from(windowStart));
                    query.setTimestamp(3, Timestamp.from(windowEnd));
                    try (ResultSet rows = query.executeQuery()) {
                        rows.next();
                        history.dlqCount += rows.getLong(1);
                    }
                }
            }
        }
        return history;
    }

    private static String outcome(int value) {
        return switch (value) {
            case 1 -> "SUCCEEDED";
            case 2 -> "FAILED_RETRYING";
            case 3 -> "DEAD_LETTERED";
            case 4 -> "CANCELLED";
            case 5 -> "SKIPPED";
            case 6 -> "UPSTREAM_FAILED";
            case 7 -> "EXECUTOR_LOST";
            default -> "UNKNOWN";
        };
    }

    private static final class History {
        final Map<String, Row> rows = new HashMap<>();
        final Map<String, Instant> firstStartedAt = new HashMap<>();
        final Map<String, List<Row>> byJob = new HashMap<>();
        final Map<String, Row> byOccurrence = new HashMap<>();
        final Map<String, Instant> jobAnchors = new HashMap<>();
        final Map<String, Instant> jobCreated = new HashMap<>();
        long dlqCount;

        Instant startedAt(Row row) {
            return firstStartedAt.getOrDefault(row.runId(), row.startedAt());
        }

        boolean contains(Set<String> runIds, Set<String> immediateJobs, Set<String> cronJobs) {
            if (!rows.keySet().containsAll(runIds)) return false;
            for (String id : immediateJobs) if (!byJob.containsKey(id)) return false;
            for (String id : cronJobs) if (!byJob.containsKey(id)) return false;
            return true;
        }

        int intervalMissing(Set<String> jobs, int periodSeconds, Instant from, Instant to, Set<String> expected) {
            if (jobs.isEmpty()) return 0;
            if (periodSeconds < 1) throw new IllegalStateException("Invalid seeded interval");
            Duration period = Duration.ofSeconds(periodSeconds);
            int missing = 0;
            for (String job : jobs) {
                Instant anchor = jobAnchors.get(job);
                if (anchor == null) {
                    missing++;
                    continue;
                }
                long periodNanos = period.toNanos();
                long steps = Math.floorDiv(Duration.between(anchor, from).toNanos(), periodNanos);
                Instant fire = anchor.plusNanos(steps * periodNanos);
                if (fire.isBefore(from)) fire = fire.plus(period);
                Instant first = jobCreated.get(job).plus(period);
                if (fire.isBefore(first)) fire = first;
                while (fire.isBefore(to)) {
                    Row row = byOccurrence.get(job + "|" + fire.toEpochMilli());
                    if (row == null) missing++;
                    else if (expected != null) expected.add(row.runId());
                    fire = fire.plus(period);
                }
            }
            return missing;
        }

        record Row(String runId, String definitionId, int outcome, Instant scheduledAt, Instant startedAt) {}
    }
}
