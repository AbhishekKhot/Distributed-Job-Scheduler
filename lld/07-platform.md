# Kairos LLD Part 07: Platform (Compose, kind, Helm, Terraform, CI)

Back to [LLD index](../LLD.md).

## 1. Local Docker Compose (`deploy/compose/`)

The Compose stack is the default development environment. Container resource limits come from `.env`, so each developer can tune them to their machine.

| Service | Image (pinned by digest in M1) | Default limit (CPU / memory) | Notes |
|---|---|---|---|
| `postgres` | `postgres:18` | from `.env` | Tuned `postgresql.conf` (part 01 §5); `pg_stat_statements`. Hosts **three databases**: `kairos_control`, `kairos_cell0` (shards 0–127) and `kairos_cell1` (shards 128–255), matching the production layout. |
| `pgbouncer` | A PgBouncer 1.21+ image, chosen and pinned by digest in M1 | 0.5 / 128 MB | Transaction mode, `max_prepared_statements = 200` |
| `kafka` | `apache/kafka:4.x` (KRaft, single broker) | 2 / 2 GB | Topics created by the init container |
| `kafka-init` | same image | one-shot | Creates topics (part 03 §2, local partition counts) |
| `redis` | `redis:8` (pinned by digest) | 1 / 512 MB | TLS port with the dev CA, per-role ACL users from `.env`, `appendonly yes`, `appendfsync everysec` |
| `kairos-migrations` | its own image | one-shot | Flyway as `sched_migrator` for `db/control` and each cell, then role grants |
| `kairos-maintenance` | its own image | one-shot at start, then a daily loop | Partitions and purges |
| `kairos-api` | service image | 1 / 768 MB | Port 8080 (API), 8081 (management) |
| `kairos-watcher` | service image | 1 / 768 MB | 2 replicas, to exercise shard rebalancing |
| `kairos-executor` | service image | 2 / 1 GB | 2 replicas |
| `kairos-state` | service image | 1 / 768 MB | |
| `mailpit` | `axllent/mailpit` (pinned) | 0.25 / 128 MB | Catches verification, reset and invitation emails; web UI on localhost only |
| `mock-target` | built from the repo | 1 / 512 MB | HTTPS with the dev CA |
| `prometheus`, `grafana` | official images | 0.5 / 1 GB in total | Provisioned scrape configs and dashboards |
| `postgres-exporter`, `kafka-exporter` | official images | 0.25 / 128 MB each | |

- **Secrets.** `scripts/dev-keys.sh` generates a gitignored `deploy/compose/.env` containing random database passwords for every role, Redis ACL passwords, the pepper, the platform admin key (whose plaintext is printed once and whose hash goes into `.env`), the dev KEK keyset file and the dev CA with a mock-target certificate. `.env.example` is committed with empty values.
- **Commands.** `make up`, `make down`, `make bench S=s1`, which wrap `docker compose` and `scripts/bench.sh`.

## 2. Local Kubernetes (`deploy/kind/`, `scripts/kind-up.sh`)

| Component | How it is installed | Purpose |
|---|---|---|
| `kind` cluster: 1 control plane + 2 workers, Calico for NetworkPolicy | `kind create cluster --config deploy/kind/cluster.yaml` | Real Kubernetes behaviour: NetworkPolicy, PDB, probes, drains |
| CloudNativePG operator + `Cluster` (1 instance locally) | Helm, pinned chart version | Postgres 18 |
| Strimzi operator + `Kafka` (KRaft, 1 broker locally) + `KafkaTopic` resources | Helm, pinned chart version | Kafka |
| Redis (StatefulSet with 1 primary + 1 replica + Sentinel, official image) | Manifests in `deploy/kind/redis/` | Locks and rate limits; failover chaos for S9 |
| KEDA | Helm | Lag-based autoscaling for executors and state processors |
| kube-prometheus-stack (slim values: no node exporter, 1 Prometheus replica) | Helm | Metrics, alerts, Grafana |
| `scheduler` chart with `values-local.yaml` | Helm | System under test |

`kind` is heavier than Compose, so it is used for **Kubernetes validation and chaos runs (S5)**, not for peak-throughput benchmarks. Those run on Compose.

## 3. Helm chart (`deploy/helm/kairos/`)

```
Chart.yaml  values.yaml  values-local.yaml  values-gcp.yaml  values.schema.json
templates/
  _helpers.tpl
  deployment.yaml          # rendered once per service in .Values.services (api, watcher, executor, state), each with its own image
  service-api.yaml         # ClusterIP :8080
  gateway-httproute.yaml   # GKE Gateway (internal ALB) — gcp only
  hpa-api.yaml
  keda-scaledobject.yaml   # executor (lag on run.* topics), state (lag on run.result)
  pdb.yaml                 # per service, minAvailable 1 (watcher: maxUnavailable 1)
  networkpolicy.yaml       # default deny + per-role allow rules (below)
  serviceaccount.yaml      # per service; GCP Workload Identity annotation
  configmap.yaml           # non-secret config
  externalsecret.yaml      # gcp: Secret Manager -> Secret; local: references a pre-created Secret
  job-migrate.yaml         # helm hook pre-install, pre-upgrade (Flyway)
  cronjob-maintenance.yaml # daily partitions and analyze
  podmonitoring.yaml       # GCP Managed Prometheus (ServiceMonitor when local)
  prometheusrule.yaml      # alerts (part 06 §2)
  dashboards-configmap.yaml
  mock-target.yaml         # only when .Values.bench.enabled
```

**Per-service pod specification:**

| Setting | kairos-api | kairos-watcher | kairos-executor | kairos-state |
|---|---|---|---|---|
| Replicas | HPA 2–6 (CPU 70%) | 3 (fixed) | KEDA 2–12 (lag) | KEDA 2–8 (lag) |
| Requests (gcp) | 1 CPU / 1.5 GiB | 2 CPU / 2 GiB | 4 CPU / 4 GiB | 2 CPU / 2 GiB |
| `terminationGracePeriodSeconds` | 30 | 30 | 75 | 30 |
| `preStop` | sleep 5 (LB deregistration) | none | `curl` the management `/drain` endpoint, then sleep | none |
| Readiness | DB + Kafka | DB + at least 1 shard held (after 30s) | DB + Kafka + not draining | DB + Kafka |

**Common pod settings:**
- `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, drop all capabilities, `seccompProfile: RuntimeDefault` (Pod Security `restricted`).
- `topologySpreadConstraints` across zones.
- `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+UseZGC`. ZGC is chosen for low pause times at large heaps and is validated in M2 against G1.

**NetworkPolicies** (default deny for ingress and egress in the namespace):

| From → to | Allowed |
|---|---|
| Gateway → api | `:8080` |
| Prometheus → all | `:8081` |
| all → PgBouncer / Cloud SQL | `:5432` (Cloud SQL private IP range) |
| all → Kafka | `:9092`/`:9093` (bootstrap and brokers) |
| executor, watcher, api → Redis | `:6378` (Memorystore TLS) / `:6379` locally |
| executor → targets | Tenant target CIDRs from values, ports 443 and 8443 |
| executor → Google APIs | Private Google Access range, `:443` (KMS) |
| all → DNS | kube-dns `:53` |
| all → GKE metadata server | `169.254.169.254:80`, for Workload Identity only. Job calls to it are blocked in the application (part 05 §4). |

## 4. Terraform for GCP (`deploy/terraform/`, validated but not applied)

```
modules/
  network/           VPC, subnets (+ secondary ranges for pods and services), Private Service Access, firewall
  gke/               regional private cluster, Dataplane V2, Workload Identity, Gateway API, node pools
                     (control: e2-standard-8, executor: c3-standard-8, platform: e2-standard-4, bench: optional)
  cloudsql/          PostgreSQL 18 Enterprise Plus; one control instance + one instance per cell (for_each over var.cells, 2 by default);
                     HA (REGIONAL), private IP, flags (part 01 §5), read replica,
                     automated backups + PITR, deletion protection, database users without passwords (set via Secret Manager)
  kafka/             google_managed_kafka_cluster (vCPU and memory sized), google_managed_kafka_topic per part 03 §2
  memorystore/       google_redis_instance: STANDARD_HA tier, transit_encryption_mode SERVER_AUTHENTICATION, auth_enabled,
                     private service access, maintenance window. The AUTH string is never a Terraform output, but it is in
                     state as a sensitive attribute, so the state bucket is CMEK-encrypted and access-restricted. Pods read it
                     through Secret Manager.
  kms/               key ring + "tenant-keysets" key (rotation 90 days), IAM: executor/state/api decrypt only
  secrets/           Secret Manager secrets (DB passwords per service, API-key pepper, bootstrap admin, SMTP); values are not in TF state
  iam/               one GSA per service, Workload Identity bindings, least-privilege IAM roles
  artifact_registry/ docker repository, immutable tags
envs/gcp-reference/  main.tf (composes modules), variables.tf, versions.tf (provider versions pinned), backend.tf (GCS, commented)
```

- **Secret values never pass through Terraform variables or state.** Terraform creates only the Secret Manager *secrets*. Versions are added out of band with `scripts/seed-secrets.sh` and `gcloud`, as documented.
- **Validation.** `scripts/verify-iac.sh` runs `terraform fmt -check -recursive`, `terraform init -backend=false`, `terraform validate`, `tflint --recursive` and `trivy config deploy/` (misconfiguration scan).
- Provider and module versions are pinned in M5 after checking the registry. Exact service limits (Managed Kafka sizing, Cloud SQL flag support for PostgreSQL 18) are also verified then.

## 5. CI pipeline (`.github/workflows/ci.yaml`; the same steps run locally with `./gradlew check` and the scripts)

| Stage | Steps |
|---|---|
| Build and unit | `./gradlew build` (compile with `-Werror`, unit and property tests, ArchUnit, Spotless formatting, ErrorProne) |
| Integration | `./gradlew integrationTest` (Testcontainers: Postgres 18, Kafka) |
| Contracts | `buf lint` + `buf breaking`; OpenAPI snapshot diff |
| Supply chain | Gradle dependency verification (checksums); OWASP dependency-check or Trivy on dependencies; **CycloneDX SBOM**; `gitleaks` |
| Images | Jib build (distroless Java 25, non-root, pinned base digest) + Trivy image scan (fails on HIGH/CRITICAL with a fix available) |
| Kubernetes and IaC | `helm lint`, `helm template` piped to `kubeconform -strict`, `scripts/verify-iac.sh` |
| E2E smoke (main branch) | Compose up → `bench-cli` S1-lite (500/s, 2 min), S3-lite, S6-lite, **S9-lite** → `verify` must pass |
