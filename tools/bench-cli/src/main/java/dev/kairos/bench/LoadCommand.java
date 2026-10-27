package dev.kairos.bench;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

/** Drives a time-based target rate and journals every accepted occurrence. */
@CommandLine.Command(name = "load", mixinStandardHelpOptions = true)
final class LoadCommand implements java.util.concurrent.Callable<Integer> {
    @CommandLine.ParentCommand
    BenchCommand parent;

    @CommandLine.Option(names = "--profile", required = true, description = "smoke, steady-20k, ramp-50k or burst")
    String profileName;

    @CommandLine.Option(names = "--management-urls", description = "Comma-separated URL for every target replica")
    String managementUrls;

    @CommandLine.Option(names = "--max-inflight", defaultValue = "1000")
    int maxInflight;

    @CommandLine.Option(names = "--immediate-jobs", defaultValue = "100")
    int immediateJobs;

    @CommandLine.Option(names = "--seconds", description = "Short diagnostic duration override")
    Integer seconds;

    @CommandLine.Option(
            names = "--chaos-seconds",
            description = "Extend a full smoke load so every fault completes during measurement")
    Integer chaosSeconds;

    @CommandLine.Option(names = "--diagnostic-rate", description = "Requests/s for a short diagnostic run")
    Integer diagnosticRate;

    @CommandLine.Option(
            names = "--warmup-seconds",
            defaultValue = "120",
            description = "Interval profile warm-up before measurement")
    int warmupSeconds;

    @CommandLine.Option(
            names = "--interval-drain-seconds",
            defaultValue = "0",
            description = "Keep interval jobs active after measurement so materialization can catch up")
    int intervalDrainSeconds;

    @Override
    public Integer call() throws Exception {
        LoadProfile profile = LoadProfile.parse(profileName);
        if (maxInflight < 1
                || immediateJobs < 0
                || warmupSeconds < 0
                || warmupSeconds > 600
                || intervalDrainSeconds < 0
                || intervalDrainSeconds > 300
                || seconds != null && seconds < 1
                || chaosSeconds != null
                        && (profile != LoadProfile.SMOKE
                                || chaosSeconds < 120
                                || chaosSeconds > 600
                                || seconds != null
                                || diagnosticRate != null)
                || diagnosticRate != null && (seconds == null || diagnosticRate < 1)) {
            throw new IllegalArgumentException("Invalid load limits");
        }
        Duration loadDuration = chaosSeconds != null
                ? Duration.ofSeconds(chaosSeconds)
                : seconds == null ? profile.duration() : Duration.ofSeconds(seconds);
        BenchFiles files = parent.files();
        if (Files.exists(files.path("load.json")) || Files.exists(files.path("expected.ndjson"))) {
            throw new IllegalStateException("Load already exists; use a new --output-dir for another run");
        }
        JsonNode session = files.read("session.json");
        String apiKey = BenchApi.required(session, "apiKey");
        String endpoint = BenchApi.required(session, "endpoint");
        BenchApi api = parent.api();
        List<String> reusable = strings(session.path("reusableJobs"));
        List<String> cron = strings(session.path("cronJobs"));
        if (profile == LoadProfile.BURST && cron.size() != 50_000) {
            throw new IllegalArgumentException("burst requires exactly 50,000 seeded cron jobs");
        }
        if (profile != LoadProfile.BURST && (!cron.isEmpty() || reusable.isEmpty())) {
            throw new IllegalArgumentException("rate profiles require reusable jobs and no cron jobs");
        }
        boolean intervalDriver = diagnosticRate == null
                && (profile == LoadProfile.SMOKE
                        || profile == LoadProfile.STEADY_20K
                        || profile == LoadProfile.RAMP_50K)
                && session.path("intervalSec").asInt() == 1
                && reusable.size() == profile.rateAt(Duration.ZERO);
        if (profile != LoadProfile.BURST && !intervalDriver && diagnosticRate == null) {
            throw new IllegalArgumentException("Seed exactly " + profile.rateAt(Duration.ZERO)
                    + " paused one-second interval jobs for this profile, or use --diagnostic-rate");
        }
        List<String> rampJobs = new ArrayList<>();
        List<String> management =
                managementUrls == null ? strings(session.path("managementUrls")) : List.of(managementUrls.split(","));
        if (management.isEmpty()) throw new IllegalArgumentException("A management URL is required");
        if (intervalDriver) {
            try {
                int failed = JobActions.change(api, reusable, apiKey, "resume");
                if (failed > 0) throw new IllegalStateException(failed + " jobs could not be resumed");
                Thread.sleep(Duration.ofSeconds(warmupSeconds));
            } catch (Exception failure) {
                JobActions.change(api, reusable, apiKey, "pause");
                throw failure;
            }
        }
        List<JsonNode> baseline = new ArrayList<>();
        for (String url : management) baseline.add(new BenchApi(url.trim()).get("stats/snapshot", null));
        files.write("baseline.json", baseline);
        var journalPath = files.path("expected.ndjson");
        Files.createFile(journalPath);
        BenchFiles.privateFile(journalPath);
        AtomicLong offered = new AtomicLong();
        AtomicLong accepted = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        AtomicLong dropped = new AtomicLong();
        ConcurrentHashMap<String, AtomicLong> errors = new ConcurrentHashMap<>();
        Instant started;
        Instant ended;
        try (BufferedWriter journal = Files.newBufferedWriter(journalPath, StandardOpenOption.APPEND)) {
            if (profile == LoadProfile.BURST) {
                Instant burstAt = Instant.parse(BenchApi.required(session, "burstAt"));
                long delay =
                        Duration.between(Instant.now(), burstAt.minusMillis(1)).toMillis();
                if (delay <= 0) throw new IllegalStateException("Burst minute has passed; seed a new session");
                if (delay > 0) Thread.sleep(delay);
                started = burstAt.minusMillis(1);
                Thread.sleep(10_001);
                ended = Instant.now();
                offered.set(cron.size());
                accepted.set(cron.size());
            } else if (intervalDriver) {
                started = Instant.now();
                Duration duration = loadDuration;
                if (profile == LoadProfile.RAMP_50K) {
                    long until = System.nanoTime() + duration.toNanos();
                    while (System.nanoTime() < until) {
                        long elapsedMillis = Math.max(
                                0L, Duration.between(started, Instant.now()).toMillis());
                        int desired = (int) Math.round(30_000d * Math.min(1d, elapsedMillis / 600_000d));
                        while (rampJobs.size() < desired) {
                            int count = Math.min(1_000, desired - rampJobs.size());
                            List<Map<String, Object>> jobs = new ArrayList<>(count);
                            for (int index = 0; index < count; index++) {
                                jobs.add(BenchApi.job(
                                        "ramp-" + started.toEpochMilli() + "-" + (rampJobs.size() + index),
                                        endpoint,
                                        Map.of("type", "INTERVAL", "everySec", 1)));
                            }
                            JsonNode result = api.post("v1/jobs:batch", Map.of("jobs", jobs), apiKey, true);
                            JsonNode rows = result.path("results");
                            if (rows.size() != count) throw new IllegalStateException("Incomplete ramp batch");
                            for (JsonNode row : rows) {
                                if (row.path("status").asInt() != 201) {
                                    throw new IllegalStateException("Ramp job creation failed");
                                }
                                rampJobs.add(BenchApi.required(row, "id"));
                            }
                        }
                        long remaining = Math.min(1_000_000_000L, until - System.nanoTime());
                        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                    }
                } else {
                    Thread.sleep(duration);
                }
                ended = Instant.now();
                double elapsedSeconds = Duration.between(started, ended).toMillis() / 1_000d;
                double expected = reusable.size() * elapsedSeconds;
                if (profile == LoadProfile.RAMP_50K) {
                    double rampSeconds = Math.min(elapsedSeconds, 600d);
                    expected += 30_000d * rampSeconds * rampSeconds / 1_200d;
                    expected += 30_000d * Math.max(0d, elapsedSeconds - 600d);
                }
                offered.set(Math.round(expected));
                accepted.set(offered.get());
                if (intervalDrainSeconds > 0) Thread.sleep(Duration.ofSeconds(intervalDrainSeconds));
            } else {
                started = Instant.now();
                Duration duration = loadDuration;
                Semaphore permits = new Semaphore(maxInflight);
                try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    long origin = System.nanoTime();
                    long until = origin + duration.toNanos();
                    double planned = 0;
                    long sequence = 0;
                    while (System.nanoTime() < until) {
                        long tick = System.nanoTime();
                        Duration elapsed = Duration.ofNanos(tick - origin);
                        planned += (diagnosticRate == null ? profile.rateAt(elapsed) : diagnosticRate) / 10.0;
                        long goal = Math.round(planned);
                        while (sequence < goal) {
                            long index = sequence++;
                            offered.incrementAndGet();
                            if (!permits.tryAcquire()) {
                                dropped.incrementAndGet();
                                continue;
                            }
                            executor.submit(() -> {
                                try {
                                    String kind;
                                    String id;
                                    if (index < immediateJobs) {
                                        kind = "JOB";
                                        String name = "load-" + started.toEpochMilli() + "-" + index;
                                        JsonNode result = api.post(
                                                "v1/jobs",
                                                BenchApi.job(name, endpoint, Map.of("type", "IMMEDIATE")),
                                                apiKey,
                                                true);
                                        id = BenchApi.required(result, "id");
                                    } else {
                                        kind = "RUN";
                                        String job = reusable.get((int) (index % reusable.size()));
                                        JsonNode result =
                                                api.post("v1/jobs/" + job + "/runnow", Map.of(), apiKey, true);
                                        id = BenchApi.required(result, "runId");
                                    }
                                    synchronized (journal) {
                                        journal.write(kind + " " + id);
                                        journal.newLine();
                                    }
                                    accepted.incrementAndGet();
                                } catch (Exception failure) {
                                    failures.incrementAndGet();
                                    String cause = failure instanceof java.io.IOException
                                            ? failure.getMessage()
                                            : failure.getClass().getSimpleName();
                                    errors.computeIfAbsent(cause, ignored -> new AtomicLong())
                                            .incrementAndGet();
                                } finally {
                                    permits.release();
                                }
                            });
                        }
                        long nextTick = tick + TimeUnit.MILLISECONDS.toNanos(100);
                        long remaining = nextTick - System.nanoTime();
                        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                    }
                    executor.shutdown();
                    if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                        executor.shutdownNow();
                        throw new IllegalStateException("In-flight calls did not drain within five minutes");
                    }
                }
                ended = Instant.now();
            }
        } finally {
            if (intervalDriver) {
                List<String> active = new ArrayList<>(reusable);
                active.addAll(rampJobs);
                int failed = JobActions.change(api, active, apiKey, "pause");
                if (failed > 0) {
                    failures.addAndGet(failed);
                    errors.computeIfAbsent("pause-failed", ignored -> new AtomicLong())
                            .addAndGet(failed);
                }
            }
        }
        Map<String, Long> errorCounts = new java.util.TreeMap<>();
        errors.forEach((cause, count) -> errorCounts.put(cause, count.get()));
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("profile", profile.cliName());
        metadata.put("warmupSeconds", intervalDriver ? warmupSeconds : 0);
        metadata.put("intervalDrainSeconds", intervalDriver ? intervalDrainSeconds : 0);
        metadata.put("driver", profile == LoadProfile.BURST ? "cron" : intervalDriver ? "interval" : "api");
        metadata.put("diagnostic", seconds != null || diagnosticRate != null);
        metadata.put("chaosSeconds", chaosSeconds);
        metadata.put("startedAt", started.toString());
        metadata.put("endedAt", ended.toString());
        metadata.put("durationSeconds", Duration.between(started, ended).toSeconds());
        metadata.put("offered", offered.get());
        metadata.put("accepted", accepted.get());
        metadata.put("requestFailures", failures.get());
        metadata.put("capacityDropped", dropped.get());
        metadata.put("errors", errorCounts);
        metadata.put("expectedCronFires", profile == LoadProfile.BURST ? cron.size() : 0);
        metadata.put("managementUrls", management);
        metadata.put("rampJobs", rampJobs);
        files.write("load.json", metadata);
        System.out.printf(
                "%s: offered %,d, accepted %,d, request failures %,d, dropped %,d%n",
                profile.cliName(), offered.get(), accepted.get(), failures.get(), dropped.get());
        if (!errorCounts.isEmpty()) System.out.println("Failure classes: " + errorCounts);
        return failures.get() == 0 && dropped.get() == 0 ? 0 : 1;
    }

    static List<String> strings(JsonNode array) {
        if (!array.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) values.add(value.asString());
        return List.copyOf(values);
    }
}
