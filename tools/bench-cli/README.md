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
