# Kairos benchmark CLI

The CLI seeds a dedicated team and signed HTTPS mock-target jobs, runs a named load profile, then checks final history in both cells against every mock-target replica. Reports are written to `build/bench/<session>/verification.json` and `report.md`. Each session directory also contains `session.json` with credentials and signing material; keep it private and remove it after use.

Start the stack with the current migrations image. From the repository root:

```sh
make env
docker compose -f deploy/compose/docker-compose.yml --profile bench build kairos-migrations bench-cli
docker compose -f deploy/compose/docker-compose.yml --profile bench up -d --wait postgres redis kafka kairos-migrations kairos-api kairos-watcher kairos-executor kairos-state mock-target
docker compose -f deploy/compose/docker-compose.yml --profile bench run --rm bench-cli --output-dir /app/build/bench/smoke-1 seed --jobs 500 --interval-sec 1
docker compose -f deploy/compose/docker-compose.yml --profile bench run --rm bench-cli --output-dir /app/build/bench/smoke-1 load --profile smoke
docker compose -f deploy/compose/docker-compose.yml --profile bench run --rm bench-cli --output-dir /app/build/bench/smoke-1 verify
docker compose -f deploy/compose/docker-compose.yml --profile bench run --rm bench-cli --output-dir /app/build/bench/smoke-1 report
```

The smoke profile runs 500 one-second interval jobs for two measured minutes. The interval loader has a 120-second warm-up by default; `--warmup-seconds` can increase it to 600 seconds for a steady-state measurement and is recorded in `load.json`. `verify` returns a failure code if it finds request failures, lost or duplicate target deliveries, missing final history, dead letters, incomplete target tracking, signature failures, or smoke schedule lag p99 above two seconds. A short diagnostic can use `load --profile smoke --seconds 2 --diagnostic-rate 5`, but its report is labeled diagnostic and does not establish profile acceptance.

For `steady-20k` and `ramp-50k`, create a team before the load, raise its quotas with `kairos-maintenance team set-quota`, then finish seeding the same session. Use `seed --jobs 0`, read the `teamId` from the private session, set `--max-jobs` to at least 20,000 for steady or 50,000 for ramp and set `--max-exec-per-sec` to at least the profile peak. Then use `seed --resume --jobs 20000 --interval-sec 1` and `load --profile steady-20k` or `load --profile ramp-50k`. The ramp creates its additional 30,000 interval jobs during the first ten minutes. Provision sufficient Kafka, database, watcher, executor and mock-target capacity; the 20K and 50K results are T-306 capacity work.

For the cron burst, provision a team with `seed --jobs 0 --cron-percent 100`, raise its max-jobs quota to 50,000, then seed with `seed --resume --jobs 50000 --cron-percent 100 --burst-at <future-UTC-minute>` and run `load --profile burst` before that minute. The burst time must be an ISO instant on a UTC minute boundary and far enough ahead for all job creation. Each run uses a new output directory and mock target. Pass `--management-urls` to `load` with a comma-separated direct management URL for **every** target replica; DNS load balancing through a single URL cannot prove duplicate-free delivery across replicas. The verifier rejects restarted replicas or incomplete per-key tracking.

The interval profiles use scheduler jobs rather than 20K or 50K API requests each second, because the public API has a rate limit. The loader resumes seeded jobs, waits for the configured warm-up, captures target baselines, runs for the named duration, and pauses the jobs. `verify` reconstructs expected interval occurrences from schedules and checks them against final history and target per-key snapshots. For an API-path diagnostic, the loader issues immediate jobs and run-now calls with bounded in-flight requests and journals accepted IDs.

## End-to-end scenarios (T-304)

Run these against a fresh Compose stack. The scenario runner provisions separate teams and trust anchors through the public API, then uses the packaged watcher, executor, state service, Kafka, Redis, PostgreSQL and signed HTTPS mock target. From the repository root:

```sh
make up
docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env --profile bench build bench-cli
docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env --profile bench run --rm bench-cli --output-dir /app/build/bench/scenarios scenario all
```

The six checks run in order: `onboarding`, `job-lifecycle`, `retry-to-dlq`, `workflow-data-passing`, `isolation`, and `auto-protection`. Run one with `scenario <name>` in place of `scenario all`. `scenarios.json` records each result and elapsed time under the selected output directory without storing credentials. A failed check stops the sequence and exits nonzero. The workflow scenario uses signed mock-target assertion headers to verify that A's output appears in D's header and JSON body; the target returns 422 if either value differs. The isolation scenario checks cross-team access to ID-based APIs and team-filtered lists. The protection scenario uses the default threshold of 50 dead letters in five minutes, so leave the stack running until its endpoint suspension is observed.

## Resilience and duplicate execution (T-305)

Build and start the stack, including the current bench CLI image. Run the fault suite from the host with Docker access:

```sh
make up
docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env --profile bench build bench-cli
bash scripts/chaos/run.sh all
```

The runner requires at least 10 GiB of free host disk before starting. It gives each experiment a private smoke session with 500 one-second interval jobs, five one-second retries, an idempotent mock-target effect, and normally a 120-second warm-up. The default recovery window is 15 minutes. The five-second slow-target experiment uses a one-hour recovery window and starts measurement immediately, because its expected drain exceeds 15 minutes and an unmeasured 60K-run warm-up backlog would inflate exact per-key verification memory. The retry allowance lets the default `RETRY_WITH_IDEMPOTENCY_KEY` policy recover executor outcomes made unknown by an injected kill; ordinary smoke seeds still default to zero retries and non-idempotent target behavior. Before each independent experiment, the runner restarts and health-checks the stateless API, watcher, executor and state services so process-local recovery state from one fault cannot contaminate the next. The mock target is also restarted and health-checked so its exact, intentionally unbounded per-key tracker contains only that experiment and its current CA is stable before seeding. Executor kills use a 300-second measured window and rolling restarts use 360 seconds, keeping every restart inside the load; other experiments use the standard 120 seconds. Interval jobs remain active for a 30-second post-measurement drain before they are paused, allowing the fire poller to materialize occurrences due before the boundary after an infrastructure backlog. It starts the load in a detached container, waits for the target baseline, injects the fault, waits for recovery, and runs `verify --chaos --wait-seconds 1200`. The chaos verifier requires zero duplicate effects, missing runs, dead letters, capacity failures and signature failures while reporting repeated HTTP deliveries separately. It records first-attempt schedule lag without imposing the steady-state two-second lag limit during an outage. `scenario chaos` combines the seven fault and verification records into `build/bench/<session>/chaos.json` and `chaos.md`; the private `session.json` files remain under each experiment directory. Use `bash scripts/chaos/run.sh executor-kill <session>` to run only one experiment. A full `all` run uses a fresh session name by default.

The experiments kill one executor five times, with 45 seconds of healthy recovery between independent kills, kill a watcher during lease handover, pause Kafka for 60 seconds, restart Redis, block cell 0 connections for 30 seconds while cell 1 stays available, run a five-second target at the smoke rate with a 200-call endpoint concurrency cap, and restart API, watcher, executor and state services under load. The runner restores a paused Kafka container and cell 0 connection access on interruption. The report measures restoration to service readiness, the latest start of a run due during the fault, and the maximum start lag for those runs. Executor and watcher faults must keep that lag within the HLD's approximately 20-second node target. Infrastructure readiness is limited to 120 seconds; rolling restart and slow target have a 300-second readiness allowance. Final verification waits up to 20 minutes for accepted runs to settle, or 60 minutes for the intentionally throughput-limited five-second target, and records schedule lag in each experiment's `verification.json`.
