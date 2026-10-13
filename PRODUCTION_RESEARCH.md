# Production Research: Distributed Job Schedulers

This document surveys how distributed job schedulers, delayed-job queues and workflow orchestrators are built and operated in production. It draws on company engineering blogs, open-source code and documentation, foundational papers, managed cloud service documentation and published incident reports. The findings informed the design of Kairos, described in the [HLD](HLD.md).

- **Compiled:** October 2026.
- **Attribution:** every claim is attributed to a source (full index in section 10). Statements marked *(inference)* are interpretation rather than something a source states directly.

---

## 0. Summary: where production practice differs from a typical first design

A typical first design for a job scheduler has the right overall shape: a relational store with a time index, a broker, executor pools, and at-least-once execution with idempotent jobs. Airbnb Dynein, Dropbox ATF and PhonePe Clockwork share that shape. Where the sources consistently disagree is in the mechanisms:

| # | Common first approach | What production systems do | Sources |
|---|---|---|---|
| 1 | A Redis `SETNX` lock plus a `last_polled_time` column stop duplicate scheduling. | The store enforces exclusivity. Each claim is one `FOR UPDATE SKIP LOCKED` statement, a conditional `UPDATE ... WHERE state=… AND version=…`, or exclusive shard ownership plus a per-row CAS. Redis locks only save effort; they do not guarantee correctness. | Kleppmann; redis.io; Airflow; Oban; Solid Queue; Dynein; FOQS |
| 2 | Duplicates are detected by a time window (`last_polled_time < now-30s`). | A deterministic run identity `(job_id, scheduled_fire_time)` under a UNIQUE constraint, passed to targets as the idempotency key. | Google SRE cron; Cloud Scheduler header; Solid Queue; graphile-worker; Temporal |
| 3 | Write to Postgres, then publish to Kafka. This is a dual write. | A transactional outbox, or claim-then-dispatch with a timeout sweeper. The store is the source of truth, and a lost publish is sent again. | brandur; Temporal transfer queue; Netflix Maestro 2025 |
| 4 | Separate Kafka `retry` and `dead` topics. | A retry is a new `next_run_time` written to the store with capped exponential backoff and full jitter. The DLQ is a terminal state with purge and redrive tooling. Kafka retry-topic ladders are only for consumer-side reprocessing. | FOQS; Dropbox ATF; AWS jitter; Uber DLQ; Sidekiq |
| 5 | A Kafka `cancel` topic. | A CAS on the run row (`cancel_requested`), checked before dispatch and delivered to the running job on its heartbeat. Cancellation is cooperative. | Temporal; River; Timestone; Durable Functions |
| 6 | Executors heartbeat to Redis, and a reaper reschedules their jobs. | The lease and heartbeat deadline live in the indexed store, so the same poller re-drives stuck work. A fencing or generation token rejects late writes from zombie workers, and executors fence themselves before their lease expires. | Dropbox ATF; Temporal RangeID; Maestro generation IDs; Chubby sequencers |
| 7 | A 20 s poll with a ±2 s target. | Either a 0.1–2 s poll with LISTEN/NOTIFY, or a coarse poll with lookahead that loads the next N minutes into in-memory timers. 60 s precision is an accepted industry contract for cron. | Solid Queue; River; ATF; OneSignal; EventBridge Scheduler |
| 8 | One `run` topic and a global priority integer. | Per-tenant or per-type queues, concurrency keys, token-bucket dispatch and shuffle sharding. Priority is often expressed as a deadline. | ATF; Async; Inngest; Cadence; AWS shuffle sharding; Timestone |
| 9 | Cron is evaluated inside the watchers, with no misfire or overlap rules. | A small cron component, usually leader-elected, emits instances keyed by fire time. Each job has explicit misfire, catch-up, overlap and DST policies and hash-based jitter to spread out `:00` load. | Google; Slack cron; Temporal; K8s CronJob; Shopify |
| 10 | DAG and backfill live inside the scheduler. | A separate orchestration layer on top of the queue, with bounded, rate-limited backfill. | Netflix Maestro; Airflow backfill; Tron |
| 11 | One `jobs` table that is constantly updated. | Split low-churn definitions from append-only runs. Partition runs by day and drop old partitions. Kill long transactions, tune autovacuum per table, and alert on XID age. | brandur; Hatchet; Mandrill; Sentry; Centrifuge |

---

## 1. Company case studies (engineering blogs)

### Overview table

| System (company) | Store | Dispatch | How due work is found | How claims stay exclusive | Scale |
|---|---|---|---|---|---|
| **Dynein** (Airbnb, 2019) | DynamoDB | SQS per service | Pods range-query their own partitions every tick; partition key is a random ID, sort key is `scheduled_time+UUID` | CAS `Scheduled→Acquired`; pod-to-partition mapping computed from the ReplicaSet | p95 deviation under 10 s; less than ⅓ the cost of RDS/Quartz |
| **ATF** (Dropbox, 2020) | Edgestore | SQS per (lambda, priority) | Poll `next_timestamp <= now` every ~2 s | Claim only from `Enqueued`; executor kills itself after 3 missed heartbeats | ~9K tasks/s; 95% start within 5 s |
| **Clockwork** (PhonePe, 2024) | HBase | RabbitMQ per client | Range scans per partition (e.g. 64) | ZooKeeper leader per client assigns partitions round-robin | 2B+ callbacks/day; p99 lag ≤ 1 min |
| **FOQS** (Meta, 2021) | Sharded MySQL | Pull API | In-memory per-shard ready index sorted by priority | Shard Manager gives one owner per shard; leases | ~1T items/day |
| **Async** (Meta, 2020) | — | Per-use-case queues | Dispatcher compares queue heads by priority and deadline | — | Hundreds of billions/day |
| **Job Queue** (Slack, 2017) | Kafka in front of Redis | Redis to workers | — | One JQRelay per topic via a Consul lock | 1.4B jobs/day, 33K/s peak |
| **Cron Conductor** (Slack, 2023) | Vitess run table | Slack Job Queue | K8s leader-elected Go service | Single leader; overlap checked against the run table | Queue handles ~9B jobs/day |
| **Borg cron** (Google) | Paxos state | Datacenter scheduler | Paxos leader | Launch intent recorded in Paxos; deterministic job names | Global |
| **Maestro** (Netflix, 2022–25) | CockroachDB | Internal outbox queue | Time trigger service | Flow-group ownership + generation IDs; outbox + sweeper | ~500K–2M jobs/day; step launch 5 s → 50 ms |
| **Timestone** (Netflix, 2022) | Durable Redis (sorted sets, Lua) | Pull | EDF on Unix-ms priority | Leases with attempts; exclusive queues per key | 30K dequeue RPS, p99 45 ms |
| **Cadence** (Uber, 2021) | DB per shard | Matching | Per-shard timer and transfer queues with ACK/read cursors | Shard ownership | 3M timers spread over ~1.6 h without harming other domains |
| **Cherami** (Uber, 2016) | RocksDB extents | Competing consumers | Delivery-time-prefixed keys | Redelivery timeout; DLQ after N | Hundreds of millions/day |
| **Centrifuge** (Segment, 2018) | MySQL JobDBs (append-only) | HTTP | Director caches jobs in memory | Single writer per JobDB via a Consul session | 400K req/s; 340B jobs/month |
| **OneSignal timers** (2023) | Scylla, bucket × 1024 shards | Kafka | Poll every 1 min for the next 10 min, then in-memory `sleep_until` | StatefulSet ordinal owns a shard range | ~10K timers/s per pod |
| **PinLater / Pacer** (Pinterest) | MySQL | Thrift pull / per-queue K8s pools | Pacer prefetches into memory | Pacer: Helix + ZooKeeper give each partition one broker | 6M+ jobs/min (PinLater) |
| **BigBen** (Walmart) | Cassandra, minute bucket × shards | Kafka/HTTP | Bucket scan | Hazelcast master; shards spread across nodes | "millions of events" |
| **PagerDuty Scheduler** (2017) | Cassandra + Kafka | — | Soon-due tasks kept in memory | Kafka partition assignment is ownership | — |

### Per-company notes

**1. Airbnb Dynein.** "Dynein: Building a Distributed Delayed Job Queueing System", Andy Fang, Dec 2019. https://medium.com/airbnb-engineering/dynein-building-a-distributed-delayed-job-queueing-system-93ab10f05f99 (read via the Medium RSS feed), plus https://github.com/airbnb/dynein
- **Why they built it:**
  - Resque gives at-most-once delivery from a single Redis instance and keeps future jobs in RAM.
  - Quartz on MySQL topped out at ~1,000 QPS per r4.8xlarge RDS instance. Each enqueue cost 4 SELECTs and 3 INSERTs.
  - Quoting the Quartz manual: "Quartz is not a job queue."
- **Immediate jobs:** anything due within 15 minutes goes straight to SQS.
- **Delayed jobs:**
  1. They land first in an inbound SQS buffer. It absorbed a scheduler outage without losing any jobs.
  2. They are stored in DynamoDB with a **random partition key** to avoid hot partitions and a **sort key of `scheduled_time + UUID`**.
  3. Each scheduler pod owns a set of partitions, computed deterministically from the ReplicaSet's pod list.
  4. A pod claims a job with a **conditional update `Scheduled → Acquired`**, dispatches it to SQS, then deletes the row.
- **Config:** `recoverStuckJobsLookAheadMs=60000` and `stallTolerance=300000`. `maxPartitions` can only grow.
- **Implication:** use a sharded time index, give each pod exclusive ownership of its partitions, and claim each row with a CAS.

**2. Dropbox ATF.** "Asynchronous Task Scheduling at Dropbox", Nov 2020. https://dropbox.tech/infrastructure/asynchronous-task-scheduling-at-dropbox
- **Scale:** ~9K tasks/s from 28 teams and 100+ use cases. SLA: 95% of tasks start within 5 s, with 99.9% availability.
- **The key idea:** every state change also rewrites `next_timestamp`, so a single poller handles both scheduling and recovery.

  | State | Next trigger time |
  |---|---|
  | Enqueued | `enqueued_ts + enqueue_timeout` |
  | Claimed | `claimed_ts + claim_timeout` |
  | Processing | `heartbeat_ts + heartbeat_timeout` |
  | Retriable failure | backoff time |

- **No concurrent runs:** a task can only be claimed from `Enqueued`. An executor **kills itself after 3 consecutive heartbeat failures**, and the heartbeat timeout is set longer than that window.
- **Isolation:** one SQS queue per (lambda, priority).
- **Lesson:** with no DLQ, tasks misclassified as retriable looped forever and consumed scheduling bandwidth.
- **Implication:** keep lease deadlines in the same indexed column as `next_run_time`, and have executors fence themselves.

**3. PhonePe Clockwork.** Feb 2024. https://tech.phonepe.com/clockwork-the-backbone-of-phonepes-2-billion-daily-jobs/, plus the author's deep dive https://snehasishroy.com/deep-dive-of-the-distributed-job-scheduler-that-powers-over-2-billion-daily-jobs-at-phonepe
- **Stack:** HBase, RabbitMQ and ZooKeeper. Used for merchant settlements, coupon expiry and similar work.
- **Flow:**
  1. Jobs are stored with a random partition ID.
  2. Extractors range-scan each partition (64 in their example).
  3. A ZooKeeper leader per client assigns partitions to workers round-robin.
  4. Work is sent to per-client RabbitMQ queues, which make HTTP callbacks.
- **Protections:**
  - **The publisher pauses scanning when queue depth passes a threshold.**
  - **A relevancy window drops callbacks that are already stale.**
  - Callback sidelining protects clients that are recovering.
  - Retry policy is set per client (drop, or exponential backoff with jitter), followed by a DLQ.
- **Scale:** 2B+ callbacks/day, 100K+ schedules/s, p99 lag capped at 1 min.
- **Lesson:** a fast producer feeding a slow consumer destabilised RabbitMQ through flow control.
- **Implication:** this is the closest match to the shape described in section 0. Add backpressure on broker depth and a relevancy (TTL) window.

**4. Meta FOQS.** "FOQS: Scaling a distributed priority queue", Feb 2021. https://engineering.fb.com/2021/02/22/production-engineering/foqs-scaling-a-distributed-priority-queue/
- **Store:** sharded MySQL, with one owner host per shard assigned by Shard Manager.
- **Item fields:** a 32-bit priority, `deliver_after`, a lease duration and a TTL.
- **Dispatch:** an in-memory per-shard ready index; prefetch k-way merges across shards.
- **Delivery and retries:**
  - A lease expiry either redelivers the item (at-least-once) or deletes it (at-most-once).
  - **A nack rewrites `deliver_after`. That is the entire retry mechanism.**
  - A circuit breaker per shard protects each shard.
- **Scale:** ~1T items/day.
- **Lesson:** timestamp scans locked many rows and produced long MySQL history lists. **Bounding each scan with a lower-bound checkpoint** fixed it.
- **Implication:** retry means rescheduling in the same store. Keep a scan checkpoint.

**5. Meta Async.** Aug 2020. https://engineering.fb.com/2020/08/17/production-engineering/async/
- **What went wrong:** one MySQL FIFO with 3 priorities let large users dominate and caused head-of-line blocking.
- **The fix:** per-use-case queues ordered by **execution deadline**. Each job declares a **delay tolerance**.
- **Further techniques:**
  - Time-shifting work to off-peak hours.
  - Batching jobs into "mega jobs".
  - Per-use-case quotas, which incident traffic is allowed to burst past.
- **Implication:** express priority as a deadline or SLO and give each tenant its own queue.

**6. Slack Job Queue.** "Scaling Slack's Job Queue", Dec 2017. https://slack.engineering/scaling-slacks-job-queue/
- **The incident:**
  - Database contention slowed workers, and Redis filled to maxmemory.
  - New enqueues failed.
  - **Dequeues also needed free memory, so the queue stayed wedged after the DB recovered.**
- **Fix:** put Kafka in front of Redis:
  - Kafkagate (Go) accepts enqueues and writes to Kafka.
  - Kafka: 16 brokers, 32 partitions per topic, RF 3, 2-day retention.
  - JQRelay moves jobs to Redis, with one relay per topic held by a Consul lock. It commits offsets only after a successful Redis write.
  - Failed jobs are re-enqueued to Kafka so they do not block a partition.
- **Migration:** double writes with per-hop count reconciliation, and **canary jobs every minute across all 1,600 partitions**.
- **Implication:** in-memory Redis must never be the only buffer. Run end-to-end canaries.

**7. Slack cron.** "Executing Cron Scripts Reliably At Scale", Sept 2023. https://slack.engineering/executing-cron-scripts-reliably-at-scale/
- **Before:** one cron box acting as a single point of failure.
- **After:** a Go Scheduled Job Conductor on K8s.
  - **A single leader schedules, chosen by K8s leader election.** The team decided multi-node scheduling was not worth the synchronisation cost, given that leader failover is fast.
  - The leader is deliberately not restarted at the top of the minute.
  - Each run is a row in a Vitess table. **Before starting a run, the conductor checks for an active run of the same script.** This replaced `flock`, which broke whenever a run lasted longer than the interval.
- **Implication:** keep cron as a tiny leader-elected emitter, with overlap checked against the run table.

**8. Google distributed cron.** SRE Book ch. 24, Štěpán Davidovič. https://sre.google/sre-book/distributed-periodic-scheduling/
- **Coordination:** Paxos with 3 replicas; the leader is the cron leader.
- **Launch protocol:**
  - The leader **synchronously records "launch started" and "launch finished" in a quorum.**
  - A launch is identified by **job + scheduled launch time**. Job names are precomputed with that time embedded.
  - A new leader checks which of those names exist and launches only the missing ones.
  - A deposed leader must stop talking to the datacenter scheduler immediately.
- **Policy:** **skip rather than double-launch** ("fail closed"). It is a per-job decision: GC is fine either way, payroll must not be skipped, a newsletter must not be sent twice.
- **Timing:** failover must finish in under 1 minute, and 1 minute is the minimum interval.
- **Thundering herd:** a **`?` crontab field** hashes the job config into the allowed range.
- **Implication:** use the `(job_id, scheduled_fire_time)` identity, durable LAUNCHING/LAUNCHED states, a per-job skip-or-run choice when the outcome is ambiguous, and hashed jitter.

**9. Netflix Maestro.** 2022, 2024 and 2025 posts. https://netflixtechblog.com/maestro-netflixs-workflow-orchestrator-ee13a06f9c78 and https://netflixtechblog.com/100x-faster-how-we-supercharged-netflix-maestros-workflow-engine-028e9637f041 (read via RSS), plus https://github.com/Netflix/maestro
- **Predecessor:** Meson had a single leader, struggled at the **12 AM UTC peak**, and hit instance-size limits.
- **Design:**
  - Time triggers fire at-least-once; **the engine deduplicates trigger requests**.
  - DAGs are capped at about 1K steps; foreach allows about 100K iterations.
  - **Run strategies:** sequential, strict-sequential, first-only, last-only, and parallel with a concurrency limit (used for backfill).
  - **Platform retries are kept separate from user retries.**
- **2025 rewrite:**
  - **The old stateless pollers double-executed steps after GC pauses**, and the stale worker reset the state.
  - The new engine has nodes claim flow groups with a heartbeat, and **generation IDs fence** stale owners.
  - A **transactional-outbox table** is written in the same transaction as the state change, with a **sweeper** that re-enqueues rows whose timeout has expired.
  - Results: step launch dropped from 5 s to 50 ms, and DB query traffic fell 90%.
- **Implication:** use an outbox and generation fencing, and keep the DAG and backfill orchestrator as its own layer.

**10. Netflix Timestone.** Sep 2022. https://netflixtechblog.com/timestone-netflixs-high-throughput-low-latency-priority-queueing-system-with-built-in-support-1abf249ba95f (via RSS), plus InfoQ coverage.
- **Store:** durable Redis. Each queue's structures sit in one shard via a hash tag, and every operation is an atomic Lua script. All mutations are idempotent.
- **States:** invisible, pending, running, completed, canceled, errored.
- **Leases:** a worker can extend its lease. **An expired lease uses up an attempt; when attempts run out, the message goes to errored.**
- **Priority:** Unix-ms deadlines, so dequeue order is earliest deadline first.
- **Exclusive queues:** at most one message in flight per exclusivity key.
- **Scale:** 30K dequeue RPS at p99 45 ms.
- **Implication:** leases with attempt counts plus canceled and errored states can replace separate cancel and dead topics. Per-key exclusivity covers "don't overlap."

**11. Uber Cadence multi-tenant task processing.** Dec 2021. https://www.uber.com/blog/cadence-multi-tenant-task-processing/
- **Storage model:** per-shard timer and transfer queues, committed atomically with workflow state, tracked by ACK and read-level cursors.
- **Problem:** **timer bursts** caused background overload even when API rate limits were low.
- **Fix:**
  - Host-level shared worker pools.
  - Per-domain rate limiters with weighted round-robin.
  - Overloaded domains split off into their own virtual queues.
- **Results:**
  - Goroutines per host dropped from 16K to 100.
  - In a test, 3M timers due within 10 minutes were spread over about 1.6 hours without harming other domains.
- **Lesson:** a stuck task stalls the ACK level, so tasks are reprocessed and must be idempotent.

**12. Uber Cherami.** Dec 2016. https://www.uber.com/blog/cherami-message-queue-system/
- **Delivery:** a redelivery timeout when no ack arrives, immediate redelivery on nack, **and the DLQ after N redeliveries.**
- **DLQ tooling:** purge and **merge back**.
- **Delayed delivery:** RocksDB keys with the delivery time in the high bits.

**13. Uber, Kafka retry and DLQ.** "Building Reliable Reprocessing and Dead Letter Queues with Apache Kafka", Feb 2018. https://www.uber.com/blog/reliable-reprocessing/
- **Why a ladder:** retrying in place blocks the partition.
- **Design:** a ladder of retry topics with increasing delays, ending in a DLQ, plus a merge-back tool.
- **Caveats:**
  - Ordering is lost.
  - Delays are only "at least" the configured time.
  - Bugs should go straight to the DLQ.
- **Implication:** this suits consumer-side reprocessing. Precise backoff belongs in the store.

**14. Segment Centrifuge.** May 2018. https://www.twilio.com/en-us/blog/archive/2018/introducing-centrifuge/
- **Problem:** about 88K logical queues, which Kafka, RabbitMQ and SQS could not handle.
- **Store:** MySQL JobDBs, **append-only** (a jobs table plus a state-transitions table).
- **Single writer:** **one Director per JobDB, enforced by a Consul session lock.**
- **Retries:** errors are classified (400 means discard; 5xx means retry), with **4 h of backoff before archiving to S3**.
- **Cleanup:** **JobDBs are cycled every ~30 min with DROP TABLE**, not row deletes.
- **Lesson:** a retry storm reached 100K RPS during an outage.

**15. OneSignal timers.** QCon SF 2023, "Sleeping at Scale". https://www.infoq.com/presentations/scale-rust-kafka/
- **Store:** Scylla, partitioned by **time bucket (5 min, later 1 min) × 1024 shards**.
- **Scheduler:** **polls every 1 min for the next 10 min**, then fires each timer from memory with Tokio `sleep_until`, sends it to Kafka, and deletes the row.
- **Ownership:** a StatefulSet ordinal gives each pod its shard range.
- **Capacity:** ~10K timers/s per pod. Top-of-hour bursts caused OOMs, so memory was capped at about 600K timers.
- **Limitation:** **a timer cannot be cancelled once it is loaded into memory.**
- **Key alert:** **drift**, the time of the last fired timer compared with wall-clock time.
- **Implication:** a coarse poll plus in-memory timers gives sub-second accuracy. Check cancellation at fire time.

**16. Pinterest PinLater and Pacer.** https://github.com/pinterest/pinlater, plus InfoQ on Pacer: https://www.infoq.com/news/2023/08/pinterest-pacer-kubernetes/
- **PinLater:**
  - MySQL store.
  - Claimed jobs time out after 600 s.
  - Rate limits per queue.
  - Succeeded jobs are kept 24 h, failed jobs 7 d.
  - Scale: 6M+ jobs/min.
- **Pacer:** Helix + ZooKeeper give each partition exclusively to one dequeue broker, which prefetches into memory. Each queue has its own K8s worker pool.

**17. Walmart BigBen.** https://github.com/walmartlabs/bigben (README only)
- **Store:** Cassandra, with events bucketed **by minute × shards**. The shard size is fixed after deploy.
- **Coordination:** a Hazelcast master.
- **Retries:** 3 in-memory retries at 1, 2 and 4 s, then status ERROR.

**18. PagerDuty Scheduler.** https://www.pagerduty.com/eng/distributed-task-scheduling-pt1/ and https://www.pagerduty.com/eng/distributed-task-scheduling-pt2/
- **Design:** Kafka + Cassandra + Akka. **Kafka partition assignment decides which node owns which tasks**, which removes the manual partition recovery the old system needed.

**19. Shopify job-iteration.** https://github.com/Shopify/job-iteration
- **Approach:** jobs are interruptible and resumable. A **checkpoint cursor is saved after each iteration**.
- **Retries:** an interruption is not counted as a retry.
- **Sizing:** keep each iteration under about 30 s, since Sidekiq's shutdown timeout is 25 s.

**20. Yelp Tron.** 2010. https://engineeringblog.yelp.com/2010/09/tron.html
- **Design:** DAG actions over SSH.
- **Key idea:** **variables are frozen per job instance**, so reruns and backfills use the original date.

**Coverage gaps:**
- **No primary engineering post found:** Robinhood, Stripe, Coinbase, Grab, Zomato, Flipkart, a Shopify scheduler post, or LinkedIn's Azkaban internals.
- **Pages blocked:** DoorDash, Razorpay and Swiggy (see section 14).

---

## 2. Open-source internals

### Comparison table

| System | Discovery | Claiming without duplicates | Misfire / catch-up | Overlap policy | Worker-death detection | Retries (default) |
|---|---|---|---|---|---|---|
| **Airflow** | Scheduler loop, 1 s idle sleep, 5 s heartbeat | Active-active schedulers; DagRun claimed with `SKIP LOCKED`; pool rows locked with `NOWAIT` in a critical section | `catchup` off by default; backfill CLI with its own `max_active_runs` | `max_active_runs` (16) | TI heartbeat timeout 300 s; orphan adoption every 300 s; `task_queued_timeout` 600 s | 0 retries, 300 s delay |
| **Temporal** | Per-shard durable timer queue | Shard ownership + `RangeID` fencing; long-poll matching with sync match | Catch-up window 1 year (min 10 s); backfill | Skip (default), BufferOne, BufferAll, CancelOther, TerminateOther, AllowAll | Start-To-Close and Heartbeat timeouts | Unlimited attempts; 1 s ×2, cap 100× |
| **Celery beat / RedBeat** | Beat tick; RedBeat sorted set | One beat only; RedBeat Redis lock (1500 s) | Not documented | None (needs a custom lock) | Visibility timeout 1 h; `acks_late` | 3 retries, 180 s |
| **Quartz JDBC** | Idle poll every 30 s | Row in the `LOCKS` table, `FOR UPDATE` (global) | Threshold 60 s; for cron, SMART means fire once now; or DO_NOTHING | `@DisallowConcurrentExecution` | Check-in every 15 s; `requestsRecovery` | None built in |
| **K8s CronJob** | Controller checks every 10 s | Single controller | `startingDeadlineSeconds`; more than 100 misses means no start | Allow (default), Forbid, Replace | Job and pod controllers | `backoffLimit` 6, 10 s doubling, 6 min cap |
| **Sidekiq** | Sorted sets polled ~5 s, jittered and scaled by process count | Lua ZRANGE+ZREM; Enterprise has a leader for cron | Enterprise does no backfill | None | super_fetch: 60 s heartbeat plus orphan scan | 25 retries over ~20 d; Dead set (10K or 6 months) |
| **River** (Go/PG) | 1 s poll + NOTIFY | Leader runs maintenance only | Periodic runs can be skipped when the leader changes | Unique jobs | Rescue after 1 h | 25 attempts, attempt⁴ ±10% |
| **Oban** (Elixir/PG) | Stager every 1 s | `FOR UPDATE SKIP LOCKED`; leader in a DB table | Not documented | Docs warn runs can overlap | Lifeline after 1 h, time-based (may duplicate) | 20 |
| **pg-boss** (Node/PG) | Cron check every 30 s; LISTEN/NOTIFY | SKIP LOCKED; throttling | **skip** (default) or `once` | singleton / stately queue policies | Expire after 15 min | 2 retries; `deadLetter` queue |
| **graphile-worker** | LISTEN/NOTIFY | SKIP LOCKED; `known_crontabs` table | `fill=` (unbounded) | Named queues run serially | Lock held 4 h; sweep every 8–10 min | 25, `exp(min(10,n))` |
| **Solid Queue** (Rails) | Dispatcher 1 s, workers 0.1 s | SKIP LOCKED; **UNIQUE (task_key, run_at)** | Not documented | `limits_concurrency` semaphores | Heartbeat 60 s, dead after 5 min | None (Active Job handles retries) |
| **Dkron** | Raft leader | Single leader | Not documented | allow / forbid | Serf gossip | Per job, on the same node |
| **XXL-JOB** | Preload ~1 s plus an in-memory time ring | DB lock | More than 5 s late is skipped, otherwise fires once | serial / discard-later / cover-earlier | Registration every 30 s, expires after 90 s | Count per task |
| **ElasticJob** | Each node triggers its own work | Sharding items assigned by a leader | Runs once after the current run | No concurrent runs | Failover through the registry | Not documented |
| **Nomad periodic** | Leader | — | Not documented | `prohibit_overlap` | — | — |

### Key mechanisms worth copying

- **Airflow HA (AIP-15):** schedulers run active-active and coordinate only through the database. It uses "no consensus algorithm (Raft, Paxos)". The scheduler picks DagRuns with `with_row_locks(..., skip_locked=True)`, ordered by `last_scheduling_decision`. Scaling and its limits:
  - Benchmark: 285, 541 and 698 tasks/min with 1, 2 and 3 schedulers.
  - The pool critical section is the bottleneck.
  - The docs warn that batching means "a low-priority task can sometimes run before a high-priority one."

  https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/scheduler.html and https://cwiki.apache.org/confluence/display/AIRFLOW/AIP-15+Support+Multiple-Schedulers+for+HA+%26+Better+Scheduling+Performance
- **Temporal:** timer tasks are written in the **same DB transaction as workflow state**. Transfer tasks act as a transactional outbox to Matching. The shard count is **fixed at cluster creation**, and each shard is fenced with `RangeID`. DST handling is literal: a 1:30 am schedule **runs twice** on fall-back. Activity cancellation is only delivered when the activity heartbeats. https://github.com/temporalio/temporal/blob/main/docs/architecture/README.md and https://docs.temporal.io/schedule
- **Celery:** ETA tasks sit in worker RAM. If the ETA is longer than the visibility timeout (1 h), the task is redelivered "again, and again in a loop." The docs say the broker is not a database: use values "no longer than several minutes." `revoke` state is held in memory, and `terminate=True` is "a last resort." https://docs.celeryq.dev/en/stable/getting-started/backends-and-brokers/redis.html
- **Quartz:** the single global lock row degrades noticeably "beyond about three nodes." Clocks must be in sync to within 1 s. https://www.quartz-scheduler.org/documentation/quartz-2.3.0/configuration/ConfigJDBCJobStoreClustering.html
- **K8s CronJob:** delivery is "approximately once": two Jobs or none can be created. If more than 100 schedules were missed, it logs "too many missed start times… check clock skew" and does not start. https://kubernetes.io/docs/concepts/workloads/controllers/cron-jobs/
- **Sidekiq:** retry backoff is `(n**4) + 15 + rand(10)*(n+1)`. Poison-pill rule: a job "recovered three times in 72 h" is killed. https://github.com/sidekiq/sidekiq/wiki
- **Oban fetch query:** `FOR UPDATE SKIP LOCKED`, ordered by `(priority, scheduled_at, id)`, with `LIMIT demand`. It sets state to `executing` and increments `attempt` in the same statement. Lifeline docs warn that time-based rescue "may transition jobs that are genuinely executing and cause duplicate execution." https://oban.hexdocs.pm/
- **Solid Queue:** recurring tasks are deduplicated by a **UNIQUE index on `(task_key, run_at)`**, written in the same transaction as the enqueue. https://github.com/rails/solid_queue
- **River (brandur):** the queue lives in the app database so enqueues are transactional. NOTIFY "respects transactions," giving sub-millisecond pickup. Cancellation of running jobs is cooperative, through a context, with a 2 s poll fallback. https://brandur.org/river
- **Nomad:** documents DST explicitly. On spring-forward, runs in the skipped hour are **skipped**. On fall-back, runs in the repeated hour **run twice**. https://developer.hashicorp.com/nomad/docs/job-specification/periodic
- **Hatchet fairness in SQL:** `row_number() PARTITION BY` took 159 ms at 10K rows. Assigning sequence-ID blocks per group at write time brought reads to 21 ms at 1M rows, at the cost of write throughput (~500–1K tasks/s). https://hatchet.run/blog/multi-tenant-queues
- **Inngest:** each function gets its own queue, plus a "queue of queues" that workers sample in weighted random order. Concurrency keys can be stacked (e.g. 5 per user and 100 globally). https://www.inngest.com/blog/building-the-inngest-queue-pt-i-fairness-multi-tenancy
- **Trigger.dev:** `concurrencyKey` with `{perKey, total}` limits. A run that is waiting on a checkpoint releases its concurrency slot. https://trigger.dev/docs/queue-concurrency

---

## 3. Foundations: correctness theory

### 3.1 Locks, leases and fencing
- **Kleppmann, "How to do distributed locking" (2016).** https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
  - Locks come in two kinds. An *efficiency* lock, where single-instance Redis is fine, only avoids duplicated work. A *correctness* lock needs consensus plus **fencing tokens**: a monotonically increasing token that the storage layer checks and uses to reject stale writers.
  - GC pauses, network delay (one GitHub incident delayed packets ~90 s) and clock jumps all break TTL locks. Redis TTLs use wall-clock time.
- **antirez's reply (Redlock author).** http://antirez.com/news/101 He concedes that clocks should be monotonic and that clients must check how much time is left on the lock. *(Inference:)* this still implies the protected resource must do its own check-and-set.
- **redis.io distributed locks.** https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/
  - Pattern: acquire with `SET key <random> NX PX ttl`; release with **compare-and-delete**, never a plain `DEL`.
  - **Failover is unsafe**: replication is asynchronous, so after a failover two clients can hold the same lock.
  - The page itself says: "implement fencing tokens."
- **Chubby (Burrows, OSDI 2006).** https://static.googleusercontent.com/media/research.google.com/en//archive/chubby-osdi06.pdf
  - Leases: 12 s KeepAlive extensions, a 45 s grace period, and elections that take 4–6 s.
  - **Sequencers:** the lock name plus a generation number, checked downstream.
  - A lock-delay of up to 1 min applies when a server cannot check sequencers.
- **AWS Builders' Library, "Leader election".** https://builder.aws.com/content/3Ev0vH0hfkcUizISUWYTvHibtcp/leader-election-in-distributed-systems
  - It is **impossible to guarantee exactly one leader**.
  - Measure leases with local elapsed (monotonic) time.
  - Check the time left on the lease before every side effect.
  - **Do not heartbeat from a background thread while the work thread is stuck.**
  - Shard leadership. Correctness must come from the database.
- **DynamoDB Lock Client.** https://aws.amazon.com/blogs/database/building-distributed-locks-with-the-dynamodb-lock-client/ Heartbeats bump a record version number (RVN). A taker must observe an unchanged RVN for a full lease period (10 s lease, 3 s heartbeat), which makes it independent of clock skew.

**Implication:** a Redis `SETNX` scheduling lock is at best an efficiency lock. Correctness has to come from conditional writes in Postgres, guarded by a `version` or `attempt_epoch` fence.

### 3.2 Durable intent, idempotency and delivery semantics
- **Google cron:** record launch intent before acting. Use deterministic names that include the scheduled time so a new leader can reconcile. Prefer to skip over double-launch.
- **AWS, "Making retries safe with idempotent APIs".** https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/
  - The caller supplies a client token, which is stored **atomically with the mutation**.
  - A repeat returns a semantically equivalent response. The same token with different parameters is a validation error.
  - Tokens are kept for the resource's lifetime plus some margin.
- **brandur, "Transactionally Staged Job Drains".** https://brandur.org/job-drain
  - Enqueuing inside a transaction can run a job whose data later rolls back.
  - Enqueuing after commit can lose the job.
  - The fix: a `staged_jobs` row in the same transaction, then a single locked drainer that pushes and deletes. This gives at-least-once delivery.
- **Kafka exactly-once.**
  - https://www.confluent.io/blog/exactly-once-semantics-are-possible-heres-how-apache-kafka-does-it/
  - https://www.confluent.io/blog/transactions-apache-kafka/

  The idempotent producer deduplicates per partition. Transactions cover Kafka-to-Kafka read-process-write and cost ~3% throughput. They do **not** cover RPCs or external stores, and a Postgres update and a Kafka publish cannot share one transaction.
- **Tyler Treat, "You Cannot Have Exactly-Once Delivery".** https://bravenewgeek.com/you-cannot-have-exactly-once-delivery/ What is called exactly-once in practice is at-least-once delivery plus idempotency.
- **AWS Step Functions.** https://docs.aws.amazon.com/step-functions/latest/dg/choosing-workflow-type.html "Exactly-once" in Standard workflows means durable per-transition state, not exactly-once side effects. Express async workflows are at-least-once. Starting an execution by name is idempotent.

**Contract to adopt:** **at-least-once execution with effectively-once results.** The scheduler *supplies* the idempotency key (`run_id`). It also offers an **at-most-once ("fail closed") mode** for jobs that cannot be made idempotent.

### 3.3 Retries, backoff and jitter
- **Marc Brooker, "Exponential Backoff and Jitter" (AWS, 2015).** https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/ Formulas taken from https://github.com/aws-samples/aws-arch-backoff-simulator/blob/master/src/backoff_simulator.py:
  - `expo(n) = min(cap, base·2ⁿ)`
  - **Full Jitter** = `uniform(0, expo(n))`. Least total work.
  - Equal Jitter = `expo(n)/2 + uniform(0, expo(n)/2)`. Worst of the jittered options.
  - Decorrelated = `min(cap, uniform(base, prev·3))`. Slightly faster to finish.
  - Backoff with no jitter at all is "the clear loser."
- **AWS, "Timeouts, retries, and backoff with jitter".** https://builder.aws.com/content/3EumjoZascWd1oZiEgL8ORlv3qE/timeouts-retries-and-backoff-with-jitter
  - Set the timeout at the downstream p99.9.
  - **Retry at one layer only**: 5 layers × 3 tries = **243×** load.
  - Use a token-bucket **retry budget**. Do not retry 4xx errors.
  - **Add jitter to all timers and periodic jobs, not just retries.** For scheduled work, use a consistent per-host jitter.
  - EBS and Lambda saw spikes at the top of the minute and just after midnight.
- **Google SRE, "Addressing Cascading Failures".** https://sre.google/sre-book/addressing-cascading-failures/
  - 3 layers × 3 retries = 64×. Use a server-wide retry budget.
  - Keep queues at no more than ~50% of the thread count.
  - Drop work that is past its deadline. Shut off batch load during an outage.

### 3.4 Timer data structures
- **Varghese & Lauck, hierarchical timing wheels** (IEEE/ACM ToN 1997). http://www.cs.columbia.edu/~nahum/w6998/papers/ton97-timing-wheels.pdf
  - Start, stop and per-tick operations are all O(1).
  - Covering 100 days at 1 s resolution takes **244 slots instead of 8.64M**.
- **Confluent, "Apache Kafka, Purgatory, and Hierarchical Timing Wheels" (2015).** https://www.confluent.io/blog/apache-kafka-purgatory-hierarchical-timing-wheels/
  - Moving from a `DelayQueue` to wheels with a 1 ms tick and O(1) cancel raised the high-timeout-rate benchmark from ~25K to **~105K req/s**.
  - Completed or cancelled tasks are removed immediately rather than waiting for a reaper.
- **Redis sorted-set delayed queue.** https://redis.antirez.com/fundamental/delayed-queue.md
  - Mechanism: `ZADD` with the due time as score, `ZRANGEBYSCORE`, then `ZREM` to claim.
  - No acks. *(Inference:)* a crash after `ZREM` loses the task.

**Implication:** treat the Postgres `next_run_time` index as the coarse, far-future tier. Each watcher loads the next 1–5 minutes into an in-memory wheel or heap and fires from there with sub-second accuracy. This is the pattern OneSignal and XXL-JOB use.

### 3.5 Postgres as a queue
- **PostgreSQL docs, the locking clause.** https://www.postgresql.org/docs/current/sql-select.html `SKIP LOCKED` "provides an inconsistent view… but can be used to avoid lock contention with multiple consumers accessing a queue-like table."
- **Crunchy Data, "Message Queuing Using Native PostgreSQL" (2021).** https://www.crunchydata.com/blog/message-queuing-using-native-postgresql The claim is a `DELETE … USING (SELECT … FOR UPDATE SKIP LOCKED) … RETURNING`. It warns about MVCC bloat on high-churn tables.
- **brandur, "Postgres Job Queues & Failure By MVCC" (2015).** https://brandur.org/postgres-queues A long-running transaction blocked VACUUM. The result:
  - 247,311 dead tuples that could not be removed.
  - Each lock attempt walked ~100K dead index entries.
  - At 50 jobs/s, the queue reached ~60K after 1 h, and lock time grew ~15×.
- **pganalyze, "the xmin horizon" (2022).** https://pganalyze.com/blog/5mins-postgres-autovacuum-dead-tuples-not-yet-removable-postgres-xmin-horizon The xmin horizon is held back by three things:
  1. long transactions
  2. **forgotten replication slots** (these do not show in `pg_stat_activity`)
  3. orphaned prepared transactions

---

## 4. Managed services: documented guarantees and limits

| Service | Delivery | Timing precision | Retries | Deduplication | DST / time | Notable limits |
|---|---|---|---|---|---|---|
| **AWS EventBridge Scheduler** | At-least-once | **60 s** (a 1:00 schedule fires between 1:00:00 and 1:00:59); optional flexible window (e.g. 15 min) | 0–185 attempts, max event age 60 s–24 h, exponential; **dropped without a DLQ** | — | **Spring-forward: skip. Fall-back: run once.** `rate(1 day)` = 24 h | 10M schedules per region (adjustable). Completed one-time schedules still count unless `ActionAfterCompletion=DELETE`. 1,000 invocations/s; over that, runs are **delayed, not dropped** |
| **AWS SQS** | At-least-once; duplicates possible even within the visibility timeout | Delay 0–15 min | Visibility timeout 30 s by default, extended by heartbeat | — | — | Visibility capped at **12 h from first receive**; ~120K messages in flight |
| **AWS Step Functions** | Standard: exactly-once per transition. Express async: at-least-once | — | Per-state `Retry` | Idempotent `StartExecution` by name | — | Standard runs up to 1 year; Express up to 5 min |
| **GCP Cloud Scheduler** | At-least-once (rarely a duplicate) | — | 0–5 attempts (default 0), backoff 5 s–3,600 s, `maxDoublings` 5. **The next run may be skipped if retries continue into it** | **Job name + `X-CloudScheduler-ScheduleTime` header, constant across retries** | DST can run or skip jobs, so **UTC is recommended**; after re-enabling, missed jobs run immediately | — |
| **GCP Cloud Tasks** | >99.999% run once; no ordering | Up to 30 days ahead | Max 100 attempts, backoff 0.1 s–3,600 s, `maxDoublings` 16 | Task name returns `ALREADY_EXISTS`; names reserved for 24 h after deletion; **sequential names hurt performance, so hash prefixes** | — | **500 dispatches/s per queue**; token bucket (`maxDispatchesPerSecond`, `maxBurstSize`, `maxConcurrentDispatches`); honours 429 and `Retry-After` |
| **Azure Durable Functions** | Activities are at-least-once | Durable timers; 6-day limit for JS/Python, longer timers chained | — | — | Use the deterministic `CurrentUtcDateTime` | Unused timers block completion; a when-any timeout does not cancel the activity |
| **Temporal (Schedules)** | — | ~1 s | Default retries unlimited | Action timestamp appended to the workflow ID | UTC default; literal DST (runs twice on fall-back) | Overlap, catch-up, pause-on-failure and jitter policies (the most complete public model) |

Sources:
- EventBridge Scheduler:
  - https://docs.aws.amazon.com/scheduler/latest/UserGuide/what-is-scheduler.html
  - https://docs.aws.amazon.com/scheduler/latest/UserGuide/scheduler-quotas.html
  - https://docs.aws.amazon.com/scheduler/latest/APIReference/API_RetryPolicy.html
  - https://docs.aws.amazon.com/scheduler/latest/UserGuide/managing-schedule-flexible-time-windows.html
  - https://docs.aws.amazon.com/scheduler/latest/UserGuide/schedule-types.html
- SQS:
  - https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html
  - https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-delay-queues.html
- Cloud Scheduler:
  - https://docs.cloud.google.com/scheduler/docs/configuring/retry-jobs
  - https://docs.cloud.google.com/scheduler/docs/configuring/cron-job-schedules
- Cloud Tasks:
  - https://docs.cloud.google.com/tasks/docs/common-pitfalls
  - https://docs.cloud.google.com/tasks/docs/quotas
- Durable Functions: https://learn.microsoft.com/en-us/azure/azure-functions/durable/durable-functions-timers
- Temporal: https://docs.temporal.io/schedule

**Takeaway:** 60 s precision is an accepted contract for cron-style schedules. A 20 s poll is adequate for cron but **too slow for "run now" and short retry delays**. Those should be enqueued directly through the outbox.

---

## 5. Best practice, mechanism by mechanism

Each subsection gives the recommendation, then the evidence behind it.

### 5.1 Discovering due work
- **Recommendation:** keep the Postgres index on `next_run_time` as the source of truth.
  - **Option A, simpler:** poll every 0.5–2 s with jitter scaled by node count, and add `LISTEN/NOTIFY` for wake-ups.
  - **Option B:** poll every 30–60 s with a 5-minute lookahead, holding due timers in memory.
- **Supporting details:**
  - Use a **partial index** `WHERE state IN ('scheduled','ready')`.
  - Keep a **scan checkpoint** so a poll does not rescan dead history.
- **Evidence:**
  - Poll intervals: ATF 2 s; Solid Queue 0.1/1 s; River 1 s + NOTIFY; Oban 1 s; Sidekiq ~5 s, jittered.
  - Lookahead: OneSignal polls 1 min with 10 min lookahead; XXL-JOB uses a time ring.
  - Scan checkpoint: FOQS.
  - Never poll more often than every 10 s *with a global lock*. That rule stops mattering once claims use SKIP LOCKED.

### 5.2 Claiming without duplicates
- **Recommendation:** claim in one statement:
  ```sql
  UPDATE runs SET state='claimed', lease_owner=$me, lease_until=now()+$lease, attempt=attempt+1, version=version+1
  WHERE id IN (SELECT id FROM runs WHERE state='ready' AND due_at<=now()
               ORDER BY priority, due_at, id FOR UPDATE SKIP LOCKED LIMIT $n)
  RETURNING ...;
  ```
  - Any number of watchers can run active-active with no Redis lock.
  - At large scale, add **hash-range shard ownership** (a lease row per shard with a generation number) to cut contention.
- **Evidence:**
  - The same claim query: Oban, Solid Queue, pg-boss, graphile-worker, Airflow.
  - Shard ownership: Dynein, Clockwork, FOQS, Pacer, OneSignal.
  - Scaling limits of the alternatives: Quartz's global lock tops out around 3 nodes, and Airflow's critical section serialises its schedulers.

### 5.3 Run identity and cron firing
- **Recommendation:** a run is the row `(job_id, scheduled_fire_time)`, protected by a **UNIQUE constraint**.
  - The cron emitter computes the next fire time in the job's IANA zone, stores it as UTC, and inserts the run.
  - Duplicate fires from two emitters, or from a new leader, are harmless.
  - Leader election for the emitter only saves effort.
  - Freeze the run's parameters, including the logical date, on the run row.
- **Evidence:**
  - Fire-time identity: Google, Solid Queue, graphile-worker `known_crontabs`, Cloud Scheduler header, Temporal workflow ID.
  - Frozen parameters: Tron.
  - Engine-side deduplication: Maestro.

### 5.4 Misfire and catch-up policy (per job)
- **Recommendation:**
  - Use an enum `misfire_policy ∈ {skip, fire_once, catch_up}`, plus a `catchup_window` or start deadline and a **cap on how many missed runs are created**.
  - Default to `skip`, or `fire_once`.
  - Backfill is a **separate, explicit, rate-limited command** with dry-run and its own concurrency cap.
- **Evidence:**
  - Skip by default: pg-boss, Sidekiq Enterprise, Airflow (`catchup=False`), Google.
  - Fire once: Quartz SMART, XXL-JOB.
  - Start deadlines and miss caps: K8s `startingDeadlineSeconds` and the 100-miss guard; Temporal's catch-up window.
  - Backfill with its own concurrency cap: Airflow (`--max-active-runs`, reprocess modes, dry run).

### 5.5 Overlap policy (per job)
- **Recommendation:**
  - Use an enum `overlap_policy ∈ {skip, buffer_one, buffer_all, cancel_other, allow}`, defaulting to `skip`.
  - Enforce it when the run fires by checking for a live run of the same job.
- **Evidence:**
  - Temporal (default Skip); K8s (Forbid, Replace); Nomad `prohibit_overlap`.
  - XXL-JOB block strategies; Maestro run strategies.
  - Slack checks its run table for overlap because `flock` failed.

### 5.6 Retries and DLQ
- **Recommendation:** a retry writes `due_at = now() + FullJitter(attempt)` back to the run row.
  - Bounds: max attempts **and** a maximum total age (a schedule-to-close budget).
  - Error classes: non-retryable error types go straight to `dead`.
  - Retry **only in the scheduler**, never inside the executor as well.
  - Keep a separate **infrastructure retry** (executor died, deploy) that does not consume the user's retry budget.
  - **Snoozing** does not consume an attempt.
  - The DLQ is the terminal `dead` state with bounded retention, alerts on any entry for critical jobs, plus **purge and redrive (merge-back)** tooling.
- **Evidence:**
  - Retry as a reschedule in the store: FOQS, ATF.
  - Full Jitter: AWS.
  - Total-age budget: Temporal.
  - Error classification: Uber, Centrifuge, ATF.
  - Infrastructure retries separate from user retries: OpenAI Airflow, Maestro.
  - Snooze without using an attempt: Oban, River.
  - DLQ tooling: Cherami, Uber, Sidekiq Dead set, pg-boss `deadLetter`.
  - The cost of no DLQ: ATF's infinite-retry lesson.

### 5.7 Detecting dead workers
- **Recommendation:** use two timers:
  1. a **process heartbeat** that tracks executor liveness;
  2. a **per-run lease** (`lease_until`) that lives in the indexed store, so the poller re-drives expired leases.
- Every executor write is conditional on `attempt` or `version`, which acts as the **fencing token**. A zombie worker's late completion is therefore rejected.
- The executor **kills its own job before its lease expires** after N failed heartbeats.
- Heartbeat **from the work loop**, not a background thread.
- Add poison-pill detection: if a run has been rescued N times within a time window, mark it dead.
- **Evidence:**
  - Lease deadline in the polled column and executor self-fencing: ATF.
  - Fencing tokens: Temporal RangeID, Maestro generation IDs, Chubby sequencers.
  - Heartbeat from the work loop: AWS leader election.
  - Poison-pill rule: Sidekiq (3 rescues in 72 h).
  - Why rescue purely by elapsed time is risky: Oban's warning that it duplicates work.

### 5.8 Cancellation
- **Recommendation:**
  - **Queued run:** a CAS sets `state=cancelled` immediately and removes it from any in-memory timer in O(1).
  - **Running run:** set `cancel_requested=true`. It is delivered **in the heartbeat response** or by NOTIFY.
  - The executor sends SIGTERM, waits out a grace period, then SIGKILL.
  - Cancellation is cooperative. Kill is a last resort.
  - Check cancellation again **at fire time** for runs that are already loaded in memory.
- **Evidence:**
  - Delivery through heartbeats: Temporal.
  - Cooperative cancel with a 2 s poll fallback: River.
  - O(1) timer removal: Kafka purgatory.
  - Kill as a last resort: Celery's warning.
  - Fire-time checks: OneSignal cannot cancel timers once loaded.
  - Cancelled as a store state: Timestone.

### 5.9 Priority and fairness
- **Recommendation:**
  - Use a small number of priority tiers with separate dispatch capacity, or express priority as a deadline.
  - Add **per-tenant and per-job-type concurrency keys**, token-bucket dispatch limits, and quotas on fan-out and backfill.
  - At scale, **shuffle-shard** tenants across executor pools.
  - Strict priority starves lower tiers, so use weighting or aging.
  - Do fairness at dispatch time, not in the claim SQL.
- **Evidence:**
  - Fairness: Inngest, Trigger.dev, Temporal fairness keys, Cadence weighted round-robin.
  - Deadlines as priority: Async, Timestone EDF.
  - Shuffle sharding (8 workers in pairs give 28 shards, so a bad tenant touches about 1/28): AWS.
  - Starvation from strict priority: Temporal, Sidekiq strict mode.
  - The cost of fairness in SQL: Hatchet.
  - The cautionary incident: GitLab INC-5372, where one tenant's fan-out saturated shared shards.

### 5.10 Time, time zones and DST
- **Recommendation:**
  - Store UTC instants. Store the IANA zone on the job.
  - Document the DST rules. Recommended: **skip on spring-forward and run once on fall-back** (EventBridge), or make the rule configurable.
  - Use **DB `now()` as the single clock**.
  - Use **monotonic clocks** for leases and durations.
  - Use an NTP source with leap smearing.
  - Test explicitly on DST dates.
- **Evidence:**
  - EventBridge DST rules versus Temporal and Nomad, which run twice on fall-back.
  - Cloud Scheduler: "UTC is recommended."
  - vixie-cron ran jobs twice across DST (Red Hat bug 138001).
  - A Cloudflare leap-second outage from negative durations.
  - Google leap smear: 24 h linear, max offset < 0.5 s.

### 5.11 Thundering herd at the top of the hour and minute
- **Recommendation:**
  - At registration, derive a **deterministic per-job offset by hashing the job ID**, the equivalent of Google's `?`.
  - Offer flexible windows.
  - Add token-bucket dispatch.
  - Plan capacity for `:00`.
- **Evidence:**
  - Google `?`; Shopify hashes `dag_id`; AWS consistent per-host jitter.
  - EventBridge flexible windows.
  - GitHub Actions says: "some queued jobs may be dropped" at the start of each hour.
  - OneSignal ran out of memory at top-of-hour.
  - Meson struggled at the 12 AM UTC peak.
  - Cadence timer storms.

### 5.12 Backpressure and backlogs
- **Recommendation:**
  - Admission control on enqueue.
  - Throttle publishing based on broker depth.
  - Size concurrency with Little's Law.
  - Never "defer" work into Redis memory.
  - Keep a **relevancy window or TTL** so stale periodic work is dropped.
  - Keep a runbook for sidelining over-quota work and serving fresh work LIFO during a backlog.
- **Evidence:**
  - Publisher throttling: Clockwork.
  - Backlog behaviour (AWS "Avoiding insurmountable queue backlogs"): an outage needs about 2× capacity to drain. A 10× spike that takes 30 min to notice needs about 300 min to recover.
  - Little's Law sizing: at 100 msg/s, 10 s latency needs about 1,000 threads.
  - The Redis-memory failure: Slack, GitLab INC-10096 (12M backlog), and Cypress.
  - TTLs on stale work: Centrifuge's 4 h limit.

### 5.13 Storage hygiene (Postgres)
- **Recommendation:**
  - Split `jobs` (definitions, low churn) from `job_runs` (append-heavy).
  - **Partition runs by day** and drop old partitions with `DETACH PARTITION CONCURRENTLY` plus `DROP`, never bulk `DELETE`.
  - **Run `ANALYZE` on partitioned parent tables on a schedule**, because autovacuum skips them.
  - Use per-table aggressive autovacuum.
  - Set `idle_in_transaction_session_timeout` and `statement_timeout`.
  - **Allow no analytics on the primary.**
  - Alert on:
    - XID age (`age(datfrozenxid)`)
    - replication-slot xmin
    - prepared transactions
    - dead tuples
    - oldest transaction age
  - Use PgBouncer.
  - Enforce retention.
- **Evidence:**
  - Partitioning, and the planner error that grew 6.1M× without parent ANALYZE: Hatchet.
  - Autovacuum settings after XID wraparound halted writes: Sentry (`max_workers=6`, `naptime=15s`, `cost_delay=0`, `freeze_max_age=500M`).
  - Mandrill's 40 h outage from XID wraparound on job tables.
  - Long transactions blocking vacuum: brandur.
  - Retention: Shopify 28 days; Centrifuge drops tables; River and PinLater retention windows.
  - PgBouncer: the Airflow docs.

### 5.14 Dispatch path (Postgres to Kafka to executors)
- **Recommendation:**
  - Write the claim and an **outbox row in the same transaction**. A relay publishes to Kafka, and consumers deduplicate on `run_id`.
  - **Never execute a job inside the Kafka poll loop**: `max.poll.interval.ms` is 300 s, after which the group rebalances.
  - Commit offsets only after the job has been handed off.
  - A failing job is re-published, not retried in place, so it does not block its partition.
  - Watch the consumer-group rebalance rate and the CPU throttling of consumers.
  - *Alternative:* executors claim directly from Postgres, and Kafka is used only for fan-out and events.
- **Evidence:**
  - Outbox: brandur, Temporal, Maestro.
  - Poll-loop limits: Kafka consumer configs (`max.poll.interval.ms` 300 s, session 45 s, `max.poll.records` 500).
  - Re-publish rather than block: Slack, Uber.
  - Rebalance and CPU throttling: GitHub, Aug 2023 (49 min outage from CPU throttling and short session timeouts on a consumer group).

### 5.15 Long-running jobs and deploys
- **Recommendation:**
  - On SIGTERM, executors stop fetching, then finish or checkpoint within `terminationGracePeriodSeconds` (default 30 s).
  - Use a preStop hook and a two-phase drain (quiet, then TERM).
  - Put checkpoints in the heartbeat payload.
  - Re-queue interrupted work as an **infrastructure retry**.
  - Use a drain-aware rollout that scales a pod down only when it is idle.
- **Evidence:**
  - Kubernetes pod termination; Sidekiq TSTP/TERM with a 25 s timeout.
  - Checkpoint in the heartbeat: Temporal.
  - Iteration cursors: Shopify job-iteration.
  - OpenAI Airflow slides (2025): SIGKILLed tasks lost their logs. Fixed with a preStop hook, a WorkerPool CRD, and an idempotency key that separates infrastructure retries from user retries.

### 5.16 DAG and backfill orchestration
- **Recommendation:** a separate orchestrator layer that:
  - persists every node transition;
  - deduplicates its triggers;
  - caps DAG size and depth;
  - supports restart from the failed step;
  - runs backfill with parallelism under a concurrency limit.
- **Evidence:**
  - Maestro: about 1K steps, foreach about 100K, run strategies, platform and user retries kept separate.
  - Airflow backfill.
  - Step Functions: durable state per transition.
  - Tron: frozen parameters.

### 5.17 Security of user-supplied jobs
- **Recommendation:**
  - Run each untrusted job in a **fresh microVM or gVisor-class sandbox**.
  - Executors hold **no control-plane credentials** (Postgres, Kafka admin, Redis) and report through a narrow API.
  - Issue short-lived, per-job, scoped credentials (OIDC).
  - Apply an egress network policy.
  - Pass parameters as data, never interpolated into a shell.
- **Evidence:**
  - Airflow security model: "Dag authors can execute arbitrary code." Airflow 3 workers receive no DB credentials.
  - GitHub Actions hardening: ephemeral runners and OIDC.
  - Firecracker (NSDI 2020): containers alone were not enough isolation for multi-tenant serverless.

---

## 6. Incidents and postmortems (what actually broke)

| # | Incident | Root cause | Lesson |
|---|---|---|---|
| 1 | **Slack, ~2016** (in the 2017 post): job queue wedged | Redis reached maxmemory; dequeues also needed memory | Durable buffer (Kafka) in front; Redis memory headroom; `noeviction` |
| 2 | **GitHub, Oct 2020**: ~10% of background-job writes failed for 2 h 32 m | Hosts added too fast while reprovisioning ZooKeeper formed a second cluster; a Kafka broker became controller in it (split brain) | Runbooks and automation for maintenance of Kafka and its coordinator |
| 3 | **GitHub, Aug 2023**: job dispatch down 49 min; delays up to 1 h | CPU throttling and short session timeouts made a Kafka consumer group loop on rebalances | Watch rebalance rate, consumer CPU limits and timeouts |
| 4 | **GitHub, Feb 2024**: job-queue failover failed; a later failback caused a backlog spike | Capacity limits; failover automation failed; "improper restoration to the primary" | Failback is as risky as failover; rehearse both; keep capacity headroom |
| 5 | **GitLab INC-10096, May 2026**: Sidekiq backlog over 12M | Deferring jobs during a previous incident filled Redis memory; nodes crashed and quorum was lost | Deferred work must sit in durable, bounded storage |
| 6 | **GitLab INC-5372, Oct 2025**: shards saturated for ~3 h; Apdex fell to 1% | One group's fan-out of `SyncProjectPolicyWorker` jobs | Concurrency limits per tenant and job type; fan-out quotas |
| 7 | **Cloudflare, Dec 2021** ("PIPEFAIL") | A cron job without `pipefail` wrote empty output; the downstream service panicked | A "successful" job is not necessarily a correct one; validate outputs and keep the last good value |
| 8 | **Cloudflare, Nov 2024**: ~55% of logs lost for 3.5 h | A blank config triggered a fail-open path sized for the past (~40× buffers); overload protections existed but were not configured | Size fail-open defaults for current scale; run overload tests |
| 9 | **Cloudflare leap second, Jan 2017** | A wall-clock delta went negative and caused a panic | Use monotonic clocks for durations and leases |
| 10 | **Mandrill, Feb 2019**: ~40 h | XID wraparound on a Postgres shard holding job tables; retries added load; earlier warnings were ignored | Alert on XID age early; partition and truncate |
| 11 | **Sentry, 2015**: writes stopped for most of a day | XID wraparound with autovacuum settings that were too lax | Tune autovacuum per table from day one |
| 12 | **Atlassian, Apr 2022**: 883 sites deleted; up to 14 days of downtime | A bulk script accepted the wrong kind of ID | Bulk cancel, backfill and purge need dry-run, soft-delete and progressive rollout |
| 13 | **Cypress, Sep 2018**: ~4 h | 100K+ analytics jobs filled Redis memory and blocked the API | Keep low-value job types off critical capacity; alert on memory and queue age |
| 14 | **vixie-cron, 2004** (Red Hat bug 138001) | Jobs ran twice across a DST change | Test DST explicitly |
| 15 | **Netflix Maestro, before 2025** | A GC pause let two workers run the same step | Fence workers with generation IDs |
| 16 | **Segment Centrifuge**: 100K RPS retry storm during an outage | Backoff not capped per destination | Retry budgets per destination or tenant |

Sources (in table order):
1. https://slack.engineering/scaling-slacks-job-queue/
2. https://github.blog/news-insights/company-news/github-availability-report-october-2020
3. https://github.blog/news-insights/company-news/github-availability-report-august-2023/
4. https://github.blog/news-insights/company-news/github-availability-report-february-2024
5. https://gitlab.com/gitlab-com/gl-infra/production/-/issues/22103
6. https://gitlab.com/gitlab-com/gl-infra/production/-/issues/20798
7. https://blog.cloudflare.com/pipefail-how-a-missing-shell-option-slowed-cloudflare-down/
8. https://blog.cloudflare.com/cloudflare-incident-on-november-14-2024-resulting-in-lost-logs/
9. https://blog.cloudflare.com/how-and-why-the-leap-second-affected-cloudflare-dns/
10. https://mailchimp.com/what-we-learned-from-the-recent-mandrill-outage/
11. https://blog.sentry.io/transaction-id-wraparound-in-postgres/
12. https://www.atlassian.com/blog/atlassian-engineering/post-incident-review-april-2022-outage
13. https://www.cypress.io/blog/downtime-post-mortem-09-24-2018
14. https://bugzilla.redhat.com/show_bug.cgi?id=138001
15. https://netflixtechblog.com/100x-faster-how-we-supercharged-netflix-maestros-workflow-engine-028e9637f041
16. https://www.twilio.com/en-us/blog/archive/2018/introducing-centrifuge/

Explicit "may run twice or not at all" disclaimers:
- **Kubernetes CronJob:** "two Jobs might be created, or no Job".
- **Heroku Scheduler:** "a job may be skipped … may run twice". https://devcenter.heroku.com/articles/scheduler
- **GitHub Actions `schedule`:** jobs may be dropped at the top of the hour; minimum interval 5 min. https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows

---

## 7. Top failure modes, ranked, with mitigations

1. **Duplicate execution.**
   - Causes: lock lease expiry from GC pauses or clock jumps; races between watchers; Kafka redelivery after a rebalance; heartbeat timeouts that fire while the original run is still alive.
   - Mitigations:
     - a deterministic `run_id` with a UNIQUE constraint;
     - conditional, versioned state transitions in Postgres;
     - executors that fence themselves;
     - side effects that are idempotent on `run_id`.
2. **Silent misses** (a job never ran, or "succeeded" but did nothing).
   - Mitigations:
     - an external dead man's switch on a per-minute canary job;
     - per-job check-ins (start, success, fail) with a grace time;
     - an explicit misfire policy and start deadline;
     - validated outputs.
3. **Backlog spiral and buffer exhaustion.**
   - Mitigations:
     - Kafka or Postgres as the durable buffer;
     - Redis set to `noeviction` with memory alerts;
     - alerts on the age of the oldest due run;
     - admission control;
     - a sideline/LIFO runbook.
4. **Postgres decay** (bloat, xmin horizon, XID wraparound).
   - Mitigations:
     - split and partition the tables;
     - autovacuum tuning;
     - timeouts on long transactions;
     - XID, slot and prepared-transaction alerts;
     - retention;
     - no analytics on the primary.
5. **Retry storms and poison jobs.**
   - Mitigations:
     - retry at one layer only;
     - Full Jitter with a cap;
     - retry budgets;
     - error classification;
     - a poison-pill rule;
     - a DLQ with redrive.
6. **Thundering herd at `:00`.**
   - Mitigations: hashed offsets, flexible windows, token-bucket dispatch, and capacity planning.
7. **Noisy neighbours and fan-out bombs.**
   - Mitigations: concurrency keys per tenant and per type, fan-out and backfill quotas, and shuffle-sharded pools.
8. **Kafka consumer-group instability.**
   - Mitigations: keep execution out of `poll()`, size CPU properly, and alert on rebalance rate and lag.
9. **Failover, failback and split brain.**
   - Mitigations: fencing, rehearsed failover *and* failback, and automated maintenance.
10. **Time bugs** (DST, leap seconds, clock skew).
    - Mitigations: UTC storage, IANA zones, documented DST rules, the DB clock as the single source of time, monotonic durations, and DST test dates.
11. **Deploys killing jobs.**
    - Mitigations: two-phase drain, preStop hooks, checkpoints, and infrastructure retries that don't consume user retries.
12. **Blast radius of bulk operations and bad config.**
    - Mitigations: dry-run, soft cancel, progressive batches, and fail-open paths sized for current scale.
13. **Untrusted code.**
    - Mitigations: microVM sandboxes, no control-plane credentials, scoped short-lived secrets, and egress policy.

---

## 8. Operations checklist

### Metrics
Tag all of these by tenant, job type and priority.
- **Scheduling health:**
  - **Schedule lag** = dispatch time − `scheduled_fire_time`, at p50, p99 and max. This is Airflow's `dagrun.schedule_delay` and OneSignal's "drift".
  - **Age of the oldest due run that has not been dispatched.**
  - Watcher loop duration, rows scanned per poll, and claim conflicts (duplicate-prevention hits).
- **Queues:**
  - Kafka consumer lag and oldest-message age per partition.
  - `AgeOfFirstAttempt` compared with retry latency.
  - DLQ inflow and size.
- **Execution:**
  - Success, failure, timeout and missed counts.
  - Run-duration percentiles compared with each job's baseline.
  - Retry rate and retry-budget use.
  - Executor utilisation and empty-poll ratio.
  - Heartbeat-expiry and zombie count.
- **Postgres:**
  - Dead tuples and when autovacuum last ran.
  - `age(datfrozenxid)`.
  - Oldest transaction age.
  - Replication-slot xmin.
  - Connections.
- **Redis and Kafka:**
  - Redis memory against maxmemory, and evictions (which must be 0).
  - Kafka rebalances per minute and under-replicated partitions.

### Alerts
- **Page:**
  - The canary's dead man's switch is missed.
  - Schedule-lag p99, or the oldest due run, is above SLO (e.g. 60 s) for 5 min.
  - Any DLQ entry for a critical job.
  - XID age is above ~50% of the limit.
  - A transaction older than N minutes is running on the primary.
  - Redis memory is above 70%.
  - Consumer lag has grown for 10 min.
  - A rebalance storm.
- **Ticket:**
  - A per-job check-in is late or missed.
  - A deadline is missed, measured from the scheduled time.
  - A duration regression.
  - A tenant's retry budget is exhausted.
  - A tenant is over quota.

### Runbooks
- **Backlog drain:**
  1. Scale executors.
  2. Pause backfill and low-priority work.
  3. Sideline over-quota tenants.
  4. Serve freshness-sensitive work LIFO.
  5. Estimate drain time (arrival rate vs processing rate).
- **Poison job:** quarantine it, move it to the DLQ, fix it, then redrive at a limited rate.
- **Bloat or long transaction:**
  1. Find the cause in `pg_stat_activity`, `pg_replication_slots` and `pg_prepared_xacts`.
  2. Terminate it.
  3. Run VACUUM.
- **XID emergency.**
- **Failover and failback for Redis and Kafka**, including a capacity check before failback.
- **DST week:** check ahead of time which jobs fall in the skipped or repeated hours.
- **Missed-run recovery:** apply the misfire policy, then run a backfill with dry-run first.
- **Bulk cancel or backfill:** dry-run, soft cancel, then progressive batches.

### Deploy safety
- **Executors:** two-phase drain with checkpoints.
- **Watchers and dispatchers:** roll one at a time. The fencing must tolerate two being active briefly.
- **Rewrites:** shadow-run the new version and reconcile counts, as Slack did.
- **Migrations:** build indexes concurrently, and apply retention first so migrations stay small (Shopify).
- **Game days:** kill brokers, Redis and watchers, and run overload tests.
- **Schedule and tenant config:** validate, diff, stage the change, and keep the last good version.

---

## 9. How these findings shaped Kairos

| Finding | Kairos design | HLD |
|---|---|---|
| Exclusivity must come from the store, not from a cache lock | Watchers claim with `FOR UPDATE SKIP LOCKED` inside leased shards, and every run write is fenced. Redis is used as an execution lock, but always together with the fenced database claim. | Section 8.2 |
| Runs need a deterministic identity | A unique key on (target, task, scheduled time); the run id is sent to targets as the `Idempotency-Key` | Sections 8, 12.1 |
| Avoid dual writes between the store and the broker | A transactional outbox for every state-driven message | Section 8.1 |
| Retries belong in the store, in one layer | Retries are `DELAYED` runs with a due time and jittered exponential backoff; infrastructure retries are counted separately | Section 10.3 |
| A DLQ is a state with tooling, not just a topic | DLQ entries with triage states, bulk redrive and discard | Section 10.4 |
| Dead workers are detected with leases and fencing | Per-run leases, fenced writes, a send-time guard and sent markers | Sections 8.2, 8.3 |
| Cancellation is cooperative state | `cancel_requested` plus a cancel broadcast; claims refuse cancelled runs | Section 10.5 |
| Precision needs short polls or a lookahead | A 1s poll with a 5s lookahead, an outbox relay every 100 ms, and `NOTIFY` wake-ups | Section 10.2 |
| Fairness needs more than a priority integer | Weighted priority tiers, per-endpoint limits and tenant quotas | Sections 10.2, 15 |
| Misfire, overlap and DST rules are per job | `misfirePolicy`, DST handling and `spreadSeconds` | Section 10.7 |
| Orchestration is a separate concern | Workflows are versioned DAG resources with bounded size and paced backfill | Section 10.6 |
| Job tables need storage hygiene | A small live table, day-partitioned append-only history, partition drops, autovacuum tuning, transaction ID age alerts | Sections 7, 16 |
| Backlogs need backpressure and canaries | Admission control on the outbox backlog, lag-based autoscaling and a dead man's switch canary | Sections 13, 16 |

---

## 10. Source index

**Company engineering**
- Airbnb Dynein:
  - https://medium.com/airbnb-engineering/dynein-building-a-distributed-delayed-job-queueing-system-93ab10f05f99
  - https://github.com/airbnb/dynein
- Dropbox ATF: https://dropbox.tech/infrastructure/asynchronous-task-scheduling-at-dropbox
- PhonePe Clockwork:
  - https://tech.phonepe.com/clockwork-the-backbone-of-phonepes-2-billion-daily-jobs/
  - https://snehasishroy.com/deep-dive-of-the-distributed-job-scheduler-that-powers-over-2-billion-daily-jobs-at-phonepe
- Meta FOQS: https://engineering.fb.com/2021/02/22/production-engineering/foqs-scaling-a-distributed-priority-queue/
- Meta Async: https://engineering.fb.com/2020/08/17/production-engineering/async/
- Slack job queue: https://slack.engineering/scaling-slacks-job-queue/
- Slack cron: https://slack.engineering/executing-cron-scripts-reliably-at-scale/
- Google SRE cron: https://sre.google/sre-book/distributed-periodic-scheduling/
- Netflix Maestro:
  - https://netflixtechblog.com/orchestrating-data-ml-workflows-at-scale-with-netflix-maestro-aaa2b41b800c
  - https://netflixtechblog.com/maestro-netflixs-workflow-orchestrator-ee13a06f9c78
  - https://netflixtechblog.com/100x-faster-how-we-supercharged-netflix-maestros-workflow-engine-028e9637f041
  - https://github.com/Netflix/maestro
- Netflix Timestone: https://netflixtechblog.com/timestone-netflixs-high-throughput-low-latency-priority-queueing-system-with-built-in-support-1abf249ba95f
- Uber:
  - https://www.uber.com/blog/cadence-multi-tenant-task-processing/
  - https://www.uber.com/blog/cherami-message-queue-system/
  - https://www.uber.com/blog/reliable-reprocessing/
- Segment Centrifuge: https://www.twilio.com/en-us/blog/archive/2018/introducing-centrifuge/
- OneSignal: https://www.infoq.com/presentations/scale-rust-kafka/
- Pinterest:
  - https://github.com/pinterest/pinlater
  - https://www.infoq.com/news/2023/08/pinterest-pacer-kubernetes/
- Walmart BigBen: https://github.com/walmartlabs/bigben
- PagerDuty:
  - https://www.pagerduty.com/eng/distributed-task-scheduling-pt1/
  - https://www.pagerduty.com/eng/distributed-task-scheduling-pt2/
- Shopify:
  - https://github.com/Shopify/job-iteration
  - https://shopify.engineering/lessons-learned-apache-airflow-scale
- Yelp Tron: https://engineeringblog.yelp.com/2010/09/tron.html
- OpenAI Airflow: https://airflowsummit.org/slides/2025/scaling-airflow-at-openai.pdf
- Pinterest Spinner:
  - https://airflowsummit.org/sessions/2021/pinterest-migration-journey/
  - https://airflowsummit.org/slides/2022/h2-Pinterest.pdf

**Open source**
- Airflow:
  - https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/scheduler.html
  - AIP-15: https://cwiki.apache.org/confluence/display/AIRFLOW/AIP-15+Support+Multiple-Schedulers+for+HA+%26+Better+Scheduling+Performance
  - https://www.astronomer.io/blog/airflow-2-scheduler
  - Backfill: https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/backfill.html
  - Metrics: https://airflow.apache.org/docs/apache-airflow/stable/administration-and-deployment/logging-monitoring/metrics.html
  - Security model: https://airflow.apache.org/docs/apache-airflow/stable/security/security_model.html
- Temporal:
  - https://github.com/temporalio/temporal/blob/main/docs/architecture/README.md
  - https://docs.temporal.io/schedule
  - https://docs.temporal.io/encyclopedia/retry-policies
  - https://docs.temporal.io/encyclopedia/detecting-activity-failures
  - https://docs.temporal.io/develop/task-queue-priority-fairness
- Celery:
  - https://docs.celeryq.dev/en/stable/userguide/periodic-tasks.html
  - https://docs.celeryq.dev/en/stable/getting-started/backends-and-brokers/redis.html
  - RedBeat: https://redbeat.readthedocs.io/en/latest/design.html
- Quartz: https://www.quartz-scheduler.org/documentation/quartz-2.3.0/configuration/ConfigJDBCJobStoreClustering.html
- Kubernetes:
  - https://kubernetes.io/docs/concepts/workloads/controllers/cron-jobs/
  - https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/
- Sidekiq:
  - https://github.com/sidekiq/sidekiq/wiki/Scheduled-Jobs
  - https://github.com/sidekiq/sidekiq/wiki/Ent-Periodic-Jobs
  - https://github.com/sidekiq/sidekiq/wiki/Reliability
  - https://github.com/sidekiq/sidekiq/wiki/Signals
- River:
  - https://riverqueue.com/docs/periodic-jobs
  - https://brandur.org/river
- Oban: https://oban.hexdocs.pm/Oban.Peer.html, https://oban.hexdocs.pm/Oban.Cron.html, https://oban.hexdocs.pm/Oban.Lifeline.html
- pg-boss: https://github.com/timgit/pg-boss
- graphile-worker: https://worker.graphile.org/docs/cron
- Solid Queue: https://github.com/rails/solid_queue
- Dkron: https://dkron.io/docs/architecture/
- XXL-JOB: https://github.com/xuxueli/xxl-job
- ElasticJob: https://shardingsphere.apache.org/elasticjob/current/en/features/failover/
- DolphinScheduler: https://github.com/apache/dolphinscheduler
- Hatchet:
  - https://docs.hatchet.run/home/architecture
  - https://hatchet.run/blog/multi-tenant-queues
  - https://hatchet.run/blog/postgres-partitioning
- Inngest:
  - https://www.inngest.com/blog/building-the-inngest-queue-pt-i-fairness-multi-tenancy
  - https://www.inngest.com/blog/fixing-multi-tenant-queueing-concurrency-problems
- Trigger.dev: https://trigger.dev/docs/queue-concurrency
- Nomad: https://developer.hashicorp.com/nomad/docs/job-specification/periodic

**Foundations**
- Kleppmann: https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- antirez: http://antirez.com/news/101
- Redis locks: https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/
- Chubby: https://static.googleusercontent.com/media/research.google.com/en//archive/chubby-osdi06.pdf
- DynamoDB lock client: https://aws.amazon.com/blogs/database/building-distributed-locks-with-the-dynamodb-lock-client/
- AWS Builders' Library:
  - https://builder.aws.com/content/3EumjoZascWd1oZiEgL8ORlv3qE/timeouts-retries-and-backoff-with-jitter
  - https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/
  - https://builder.aws.com/content/3Ev0vH0hfkcUizISUWYTvHibtcp/leader-election-in-distributed-systems
  - https://builder.aws.com/content/3EuRcgkTP1MI0c7zM8W6HL3WIqA/avoiding-insurmountable-queue-backlogs
  - https://builder.aws.com/content/3EuS9Sakq7L3VLQIF3qzfMfke1Y/avoiding-fallback-in-distributed-systems
  - https://builder.aws.com/content/3F06NpJ8YeoIGP8VHTw4n81pFn8/workload-isolation-using-shuffle-sharding
- Backoff and jitter:
  - https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
  - https://github.com/aws-samples/aws-arch-backoff-simulator
- Timing wheels:
  - http://www.cs.columbia.edu/~nahum/w6998/papers/ton97-timing-wheels.pdf
  - https://www.confluent.io/blog/apache-kafka-purgatory-hierarchical-timing-wheels/
- Redis delayed queue: https://redis.antirez.com/fundamental/delayed-queue.md
- Postgres as a queue:
  - https://www.postgresql.org/docs/current/sql-select.html
  - https://www.crunchydata.com/blog/message-queuing-using-native-postgresql
  - https://brandur.org/postgres-queues
  - https://brandur.org/job-drain
  - https://pganalyze.com/blog/5mins-postgres-autovacuum-dead-tuples-not-yet-removable-postgres-xmin-horizon
- Kafka exactly-once:
  - https://www.confluent.io/blog/exactly-once-semantics-are-possible-heres-how-apache-kafka-does-it/
  - https://www.confluent.io/blog/transactions-apache-kafka/
  - https://docs.confluent.io/platform/current/installation/configuration/consumer-configs.html
- Exactly-once delivery: https://bravenewgeek.com/you-cannot-have-exactly-once-delivery/
- Google SRE cascading failures: https://sre.google/sre-book/addressing-cascading-failures/
- Google leap smear: https://developers.google.com/time/smear

**Managed services**
- EventBridge Scheduler:
  - https://docs.aws.amazon.com/scheduler/latest/UserGuide/what-is-scheduler.html
  - https://docs.aws.amazon.com/scheduler/latest/UserGuide/scheduler-quotas.html
  - https://aws.amazon.com/blogs/compute/introducing-amazon-eventbridge-scheduler/
- SQS: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html
- Step Functions: https://docs.aws.amazon.com/step-functions/latest/dg/choosing-workflow-type.html
- Cloud Scheduler: https://docs.cloud.google.com/scheduler/docs/configuring/retry-jobs
- Cloud Tasks: https://docs.cloud.google.com/tasks/docs/common-pitfalls
- Durable Functions: https://learn.microsoft.com/en-us/azure/azure-functions/durable/durable-functions-timers

**Operations and security**
- Healthchecks.io: https://healthchecks.io/docs/
- Sentry Crons: https://docs.sentry.io/product/crons/
- OpenTelemetry messaging spans: https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/
- GitHub Actions security hardening: https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions
- Firecracker: https://www.usenix.org/conference/nsdi20/presentation/agache
- Heroku Scheduler: https://devcenter.heroku.com/articles/scheduler

## 11. Sharding strategies for write-heavy systems

### 11.1 Strategies used in production

| Strategy | How it works | Used by | Strengths | Weaknesses |
|---|---|---|---|---|
| **Range** | Each shard owns a contiguous key range; ranges split when they grow | Bigtable, HBase, Spanner, CockroachDB, MongoDB (ranged) | Efficient range scans; automatic splitting | Hot spots when keys increase steadily, such as timestamps or sequential ids |
| **Hash mod N** | `hash(key) mod N` picks the shard | Simple application-level sharding | Even spread, trivial routing | Changing N moves almost every key |
| **Consistent hashing** | Keys and nodes sit on a hash ring, with virtual nodes | Cassandra, DynamoDB, Riak, ScyllaDB | Adding a node moves only a slice of the data | No cross-shard transactions; range queries are hard |
| **Fixed logical shards mapped to physical databases** | Many fixed logical shards; a map assigns each to a physical database; ids carry the shard number | Instagram (thousands of logical shards on PostgreSQL), Notion (480 logical shards on PostgreSQL), Temporal (history shard count fixed at cluster creation), Vitess, Citus | Growth by moving whole shards, never rehashing; routing from the id alone | The shard count is fixed up front; cross-shard queries need scatter-gather |
| **Directory (lookup) based** | A lookup service maps each key or tenant to a shard | Many multi-tenant SaaS products | Any key can move anywhere; good for very large tenants | The lookup service is on every request path and must never fail |
| **Tenant / cell based** | Each tenant lives wholly in one "cell" or "pod" (a full stack); cells are independent | Shopify pods, Salesforce instances, AWS cell-based architecture, Slack's cellular design | Limits the blast radius of an outage; per-tenant isolation | One very large tenant can overload its cell |
| **Time-bucket** | Data partitioned by time bucket × N shards | OneSignal (ScyllaDB), Walmart BigBen (Cassandra), Discord messages | Ideal for timers and append-only logs; old buckets are dropped cheaply | Lookups by entity id need another index |
| **Vertical (functional)** | Different tables or domains in different databases | Almost every system, as a first step | Simple; each database tuned for its load | Does not help a single table that grows too large |

Write-heavy queues and schedulers in production mostly use **fixed logical shards with one owner per shard**:
- **Meta FOQS:** sharded MySQL, where a shard manager gives each shard a single owner.
- **Uber Cadence and Temporal:** a fixed number of history shards, each owned by one host.
- **Segment Centrifuge:** many MySQL job databases, each with a single writer.
- **Airbnb Dynein:** DynamoDB partitions, each read by one pod.

### 11.2 What Kairos combines

1. **Vertical split.** A small control database (teams, users, endpoints) is kept apart from the cell databases (jobs, runs, outbox, history), so the hot path never writes to the control database.
2. **Fixed logical shards.** `murmur3(team_id + name) mod 256`, with the shard embedded in every job and run id, as Instagram does. Any id routes to its database without a lookup.
3. **Single owner per shard.** Watchers lease shards, as in FOQS, Cadence and Temporal. Two watchers never poll the same rows, so there is no lock contention.
4. **Cells for blast radius.** A cell failover pauses only its shards. These are database cells, not full-stack cells: the services are shared across cells.
5. **Time partitioning inside each cell.** `run_history` and `task_outputs` are partitioned by day, and old days are dropped instead of deleted row by row.

**Why the shard key is team + name rather than team alone.** Sharding by team (the tenant pattern) would put a large team's entire load on one cell. Hashing the job name spreads every team across all shards. Each job is a small, bounded unit of load, since it fires at most once per occurrence, so no single job becomes a hot spot. The cost is that team-wide lists (jobs, runs, DLQ) read all cells in parallel, which happens only on the API side and never on the execution path.

### 11.3 Practices not yet written into the design

| Gap | What production systems do |
|---|---|
| **Moving a shard to a new cell** | Copy the shard's rows with logical replication, briefly pause the shard by draining its lease, let the copy catch up, point `cell_map` at the new cell, resume. `cell_map` already has a `DRAINING` status for this, but the procedure is not documented yet. |
| **Hot-shard detection** | Per-shard metrics (fires, outbox backlog, lag) and an alert when one shard is far above the average |
| **Shard-count headroom** | 256 shards allow at most 256 cells, far more than the planned 2–4. Temporal deployments often choose 512 or more. Raising the count later means re-issuing ids, so any increase (for example to 1024, embedding 10 bits instead of 8) is cheapest before the first release. |
