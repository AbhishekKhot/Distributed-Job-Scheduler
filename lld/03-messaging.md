# Kairos LLD Part 03: Messaging (Kafka and Protobuf)

Back to [LLD index](../LLD.md).

## 1. Protobuf schemas (`kairos-proto/src/main/proto/kairos/v1/*.proto`)

**Rules:**
- `proto3`.
- Field numbers are **never reused**. Removed fields are marked `reserved`.
- New fields are additive only.
- CI runs `buf lint` and `buf breaking --against` the main branch.
- Consumers ignore unknown fields.
- Kafka record headers carry `content-type: application/x-protobuf`, `schema: kairos.v1.<Message>` and the W3C `traceparent`.

```proto
syntax = "proto3";
package kairos.v1;
option java_package = "dev.kairos.proto.v1";
option java_multiple_files = true;

enum Trigger { TRIGGER_UNSPECIFIED = 0; SCHEDULE = 1; IMMEDIATE = 2; MANUAL = 3; BACKFILL = 4; REDRIVE = 5; }
enum TargetType { TARGET_TYPE_UNSPECIFIED = 0; JOB = 1; WORKFLOW_TASK = 2; }

message RunKey {
  int32  shard_id   = 1;
  bytes  run_id     = 2;   // 16 bytes
  int32  fence      = 3;
}

// kairos.run.{high|medium|low}
message DispatchMessage {
  RunKey     key                  = 1;
  bytes      tenant_id            = 2;
  TargetType target_type          = 3;
  bytes      definition_id        = 4;   // job_id / workflow_id
  int32      definition_version   = 5;
  bytes      target_id            = 6;   // job_id / workflow_run_id
  string     task_key             = 7;
  bytes      endpoint_id          = 8;
  int32      endpoint_version     = 9;
  int64      scheduled_at_us      = 10;
  int64      due_at_us            = 11;
  int32      attempt              = 12;
  int32      infra_attempt        = 13;
  int32      priority             = 14;
  Trigger    trigger              = 15;
  int32      max_lateness_ms      = 16;  // 0 = none
  RequestValues request           = 17;  // values for the endpoint template; may contain placeholders
  repeated string needs_outputs_of = 18; // upstream task keys whose outputs are referenced
  bool       extract_outputs      = 19;  // true only when a downstream task references this task's outputs
}

message RequestValues {
  map<string, string> path_params = 1;
  map<string, string> query       = 2;
  map<string, string> headers     = 3;   // never credentials (validated at save time, part 05)
  bytes  body_json                = 4;   // UTF-8 JSON (<= 64 KB) or empty
  string body_text                = 5;   // alternative non-JSON body (<= 64 KB)
  string content_type             = 6;
}

enum Outcome { OUTCOME_UNSPECIFIED = 0; SUCCESS = 1; RETRYABLE = 2; NON_RETRYABLE = 3; DEFERRED = 4; INFRA = 5; CANCELLED = 6; SKIPPED = 7; }

// kairos.run.result
message ResultMessage {
  RunKey  key                 = 1;   // fence = the fence after the claim
  bytes   tenant_id           = 2;
  TargetType target_type      = 3;
  bytes   definition_id       = 4;
  bytes   target_id           = 5;
  string  task_key            = 6;
  bytes   endpoint_id         = 7;
  int64   scheduled_at_us     = 8;
  int32   attempt             = 9;
  int32   infra_attempt       = 10;
  Outcome outcome             = 11;
  int32   error_class         = 12;  // codes from part 01 §2
  int32   http_status         = 13;  // 0 = no response
  int64   started_at_us       = 14;
  int64   finished_at_us      = 15;
  int64   retry_after_ms      = 16;  // from Retry-After, 0 = absent
  string  error               = 17;  // <= 512 chars, redacted
  string  response_snippet    = 18;  // failures only, <= 1 KB, redacted
  bytes   outputs_json        = 19;  // non-sensitive outputs (<= 16 KB)
  bytes   sealed_outputs      = 20;  // XChaCha20-Poly1305 ciphertext of sensitive outputs
  string  executor_id         = 21;
  Trigger trigger             = 22;
  int32   priority            = 23;
}

// kairos.run.cancel
message CancelMessage { RunKey key = 1; bytes tenant_id = 2; int64 requested_at_us = 3; }

// kairos.run.completed / kairos.run.dead
message RunEvent {
  enum Type { TYPE_UNSPECIFIED = 0; RUN_SUCCEEDED = 1; RUN_DEAD_LETTERED = 2; RUN_CANCELLED = 3; RUN_SKIPPED = 4;
              WORKFLOW_SUCCEEDED = 5; WORKFLOW_FAILED = 6; WORKFLOW_BLOCKED = 7; JOB_AUTO_PAUSED = 8;
              JOB_UNHEALTHY = 9; ENDPOINT_SUSPENDED = 10; ENDPOINT_RESUMED = 11; }
  Type   type            = 1;
  bytes  event_id        = 2;   // deterministic: hash(run_id, attempt, infra_attempt, type), so consumers can dedupe
  bytes  tenant_id       = 3;
  bytes  run_id          = 4;
  bytes  definition_id   = 5;
  bytes  target_id       = 6;
  string task_key        = 7;
  bytes  endpoint_id     = 8;
  int64  scheduled_at_us = 9;
  int64  occurred_at_us  = 10;
  int32  attempt         = 11;
  int32  error_class     = 12;
  int32  http_status     = 13;
}
```

**Why `event_id` is deterministic.** The outbox relay is at-least-once, so it can publish the same event twice (a crash after the Kafka acknowledgement but before the delete commits). A deterministic id lets downstream consumers deduplicate.

## 2. Topics

Topics are created by an `AdminClient` init step at application start, guarded by a flag, or by Terraform or Strimzi `KafkaTopic` resources in Kubernetes.

| Topic | Partitions (local / perf) | Retention | Notable config |
|---|---|---|---|
| `kairos.run.high` | 6 / **48** | 24h | `min.insync.replicas = 2` (perf), `segment.ms = 1h` |
| `kairos.run.medium` | 12 / **96** | 24h | same |
| `kairos.run.low` | 6 / **48** | 24h | same |
| `kairos.run.result` | 12 / **96** | 24h | same |
| `kairos.run.cancel` | 1 / 6 | 1h | |
| `kairos.run.completed` | 6 / 24 | 3 days | |
| `kairos.run.dead` | 1 / 6 | 14 days | |
| `kairos.internal.poison` | 1 / 3 | 14 days | Records that cannot be decoded (quarantined, alerted) |

**Who produces:** every topic except `run.result` is written **only by the `OutboxRelay`** (transactional outbox, HLD §8). `run.result` is written by executors directly, because no database transaction produces a result.

All topics use `cleanup.policy = delete`, `compression.type = producer` and `max.message.bytes = 1048576`. Replication factor is 1 locally and 3 in perf and GCP.

**Keys:**
- `run.*` and `run.result`: `target_id` (the job id, or the workflow run id).
- `run.result` keyed by `target_id` routes every task completion of one workflow run to one partition, so DAG counter updates for a workflow run are serialised without lock contention.

## 3. Client configuration

**Producer** (shared by every role):

| Setting | Value | Why |
|---|---|---|
| `acks` | `all` | Durability |
| `enable.idempotence` | `true` | No duplicate writes from producer retries |
| `max.in.flight.requests.per.connection` | 5 | Allowed with idempotence |
| `linger.ms` | 5 | Batching with low latency |
| `batch.size` | 262144 | |
| `compression.type` | `lz4` | |
| `buffer.memory` | 134217728 | |
| `delivery.timeout.ms` | 30000 | |
| `request.timeout.ms` | 10000 | |

**Executor consumers** (plain `KafkaConsumer`, one per tier per pod):

| Setting | Value | Why |
|---|---|---|
| `group.id` | `kairos-executor` | Competing consumers |
| `group.instance.id` | `${POD_NAME}-${tier}` | **Static membership**: a restart within the session timeout causes no rebalance |
| `partition.assignment.strategy` | `CooperativeStickyAssignor` | Incremental rebalances |
| `enable.auto.commit` | `false` | Offsets are committed by the tracker (§4) |
| `max.poll.records` | high 250 / medium 150 / low 100 | Gives the 50/30/20 weighted share under saturation |
| `fetch.min.bytes` / `fetch.max.wait.ms` | 1 / 10 | Latency over throughput (the ±2s target) |
| `session.timeout.ms` / `heartbeat.interval.ms` | 45000 / 3000 | |
| `max.poll.interval.ms` | 300000 | The poll thread never blocks on work (§4) |
| `auto.offset.reset` | `earliest` | A new group must not skip runs |

**Cancel listener:**
- **Manual `assign()`** of every `run.cancel` partition, with no consumer group. It seeks to the end at startup, because only live cancels matter.
- A cancel missed during a restart is still covered by `cancel_requested` in the database: the claim refuses the run, and the state processor will not retry a cancelled run.

**State-processor consumer** (Spring Kafka `ConcurrentMessageListenerContainer`):

| Setting | Value |
|---|---|
| Listener | Batch |
| `AckMode` | `MANUAL` (acknowledge after the settle transaction commits and publishes succeed) |
| `max.poll.records` | 1000 |
| `group.id` | `kairos-state` |
| Assignor, membership | Cooperative-sticky, static membership |
| Error handler | `DefaultErrorHandler` with exponential backoff and **no retry limit** for transient database errors. Records that cannot be decoded go to `kairos.internal.poison` through a `DeadLetterPublishingRecoverer`. |

## 4. Executor offset tracking (`OffsetTracker`)

Runs in one partition finish out of order. A Kafka offset may only be committed once **every** earlier offset in that partition has finished, otherwise a crash would skip unfinished runs.

```
per partition p:
  pending: a sorted set of the offsets handed to the pipeline
  done:    the offsets that finished (result published, or the message was dropped as lost or stale)

register(record)      -> pending.add(offset)
complete(offset)      -> done.add(offset)
committable(p)        -> the lowest pending offset that is not done, else lastRegistered + 1
every 1s on the poll thread: consumer.commitAsync(committable offsets of every partition)
on partitions revoked: commitSync(committable), then drop that partition's state
  (runs still in flight keep going; their results are still published; if Kafka redelivers
   their messages to the new owner, the fenced claim refuses them because the run is RUNNING)
```

- **Backpressure.** `inflight >= highWater` (default 4,000 per pod) → `consumer.pause(assignment)`. Resume once below `lowWater` (3,000). The poll loop keeps calling `poll()` while paused, so the group heartbeat stays alive.
- **Memory bound.** Pending offsets are at most `highWater + max.poll.records` per pod.

## 5. Transactional outbox relay (`OutboxRelay`, watcher role)

```
per owned shard group (8 shards), loop every relayInterval (100 ms), or at once if the last batch was full:
  tx {
    rows = SELECT … FROM outbox WHERE shard_id = ANY(group) AND available_at <= now()
           ORDER BY available_at LIMIT 2000 FOR UPDATE SKIP LOCKED        // part 02 §4c
    if rows.isEmpty: commit; continue
    tokenBucket.acquire(rows.size)                                        // thundering-herd pacing (optional)
    futures = rows.map(row -> producer.send(record(topicOf(row.topic), row.msg_key, row.payload, traceparent)))
    producer.flush(); await all futures (bounded by delivery.timeout.ms)
    if any failed: ROLLBACK                                               // rows remain; retried next loop with backoff
    else: DELETE … WHERE (shard_id, outbox_id) IN rows; COMMIT
  }
  metrics: outbox_published_total, outbox_relay_batch_seconds, outbox_backlog{due}, outbox_oldest_due_age_seconds
```

| Property | How it holds |
|---|---|
| **At-least-once** | A row is deleted only in the transaction that runs **after** every Kafka acknowledgement. A crash anywhere before that commit leaves the row to be published again. |
| **No loss on node death** | The rows live in Postgres. When shard leases move, the new owner's relay publishes them. |
| **Ordering** | Rows are published in `available_at` order per shard group. Per-key ordering across retries is not needed, because fences make each message self-describing. |
| **Duplicates** | Possible (crash between acknowledgement and commit). Dispatch duplicates are rejected by the Redis run lock and the fenced claim; event duplicates by `event_id`. |
| **Backpressure** | If the oldest due row is more than 10s old (`outbox_oldest_due_age_seconds`), fire pollers skip claiming new schedules (admission control). Alert `OutboxBacklog`. |
| **Kafka down** | Each failed attempt rolls back. Rows accumulate durably; the relay backs off (100 ms … 5s); fire pollers stop at the backlog threshold. |
| **Long transaction risk** | The transaction is held only for one batch publish (about 5–30 ms). `idle_in_transaction_session_timeout` (10s) and `delivery.timeout.ms` (30s) bound the worst case; the relay role has `statement_timeout = 5s`. |

**Executor results.** `ResultPublisher` sends `ResultMessage` directly to `run.result` with the idempotent producer and waits for the acknowledgement. Only then does it complete the offset and release the Redis lock. If the executor dies first, the lease sweeper handles the run (part 02 §4b).
