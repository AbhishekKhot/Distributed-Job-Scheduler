#!/usr/bin/env bash
set -Eeuo pipefail

# T-305: run each fault in a separate, private smoke-load session.
cd "$(dirname "$0")/../.."

compose=(docker compose --file deploy/compose/docker-compose.yml --env-file deploy/compose/.env --profile bench)
experiments=(executor-kill watcher-handover kafka-pause redis-restart cell-pause slow-target rolling-restart)
selection="${1:-all}"
session="${2:-t305-$(date -u +%Y%m%dT%H%M%SZ)}"
if [[ ! "$session" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]]; then
    echo "Session must contain only letters, digits, dots, underscores, or hyphens" >&2
    exit 2
fi
if [[ "$selection" != all ]] && [[ ! " ${experiments[*]} " =~ " $selection " ]]; then
    echo "Unknown experiment: $selection" >&2
    exit 2
fi
if [[ ! -f deploy/compose/.env ]]; then
    echo "Run make env and make up first" >&2
    exit 2
fi
available_kib="$(df -Pk . | awk 'NR == 2 { print $4 }')"
if [[ ! "$available_kib" =~ ^[0-9]+$ ]] || (( available_kib < 10 * 1024 * 1024 )); then
    echo "T-305 needs at least 10 GiB free on the host before a 500/s fault run" >&2
    exit 2
fi

root="build/bench/$session"
mkdir -p "$root"
cell_disabled=0
paused_id=""
load_id=""
stopped_watcher=""
stopped_executor=""
cleanup() {
    if (( cell_disabled )); then
        "${compose[@]}" exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
            -c 'ALTER DATABASE kairos_cell0 WITH ALLOW_CONNECTIONS true' >/dev/null || true
    fi
    if [[ -n "$paused_id" ]]; then docker unpause "$paused_id" >/dev/null || true; fi
    if [[ -n "$stopped_watcher" ]]; then
        docker start "$stopped_watcher" >/dev/null || true
        docker update --restart=unless-stopped "$stopped_watcher" >/dev/null || true
    fi
    if [[ -n "$stopped_executor" ]]; then docker start "$stopped_executor" >/dev/null || true; fi
    if [[ -n "$load_id" ]]; then docker rm -f "$load_id" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

service_id() {
    local id
    id="$("${compose[@]}" ps -q "$1" | head -n 1)"
    [[ -n "$id" ]] || { echo "No running container for $1" >&2; return 1; }
    printf '%s\n' "$id"
}

service_ids() {
    "${compose[@]}" ps -q "$1"
}

service_count() {
    local count=0 id
    while IFS= read -r id; do
        [[ -n "$id" ]] || continue
        count=$((count + 1))
    done < <(service_ids "$1")
    printf '%s\n' "$count"
}

healthy_count() {
    local service="$1" id count=0
    while IFS= read -r id; do
        [[ -n "$id" ]] || continue
        if [[ "$(docker inspect -f '{{.State.Health.Status}}' "$id")" == healthy ]]; then
            count=$((count + 1))
        fi
    done < <("${compose[@]}" ps -q "$service")
    printf '%s\n' "$count"
}

wait_healthy() {
    local service="$1" needed="$2" deadline=$((SECONDS + 300))
    until (( $(healthy_count "$service") >= needed )); do
        (( SECONDS < deadline )) || { echo "Health timeout: $service" >&2; return 1; }
        sleep 2
    done
}

wait_file() {
    local file="$1" deadline=$((SECONDS + 240))
    until [[ -s "$file" ]]; do
        (( SECONDS < deadline )) || { echo "Load did not start: $file" >&2; return 1; }
        [[ "$(docker inspect -f '{{.State.Running}}' "$load_id")" == true ]] || {
            docker logs --tail 50 "$load_id" >&2
            return 1
        }
        sleep 2
    done
}

record_fault() {
    local name="$1" start="$2" restored="$3" healthy="$4" attempts="${5:-1}" extra="${6:-}"
    printf '{"experiment":"%s","startedAt":"%s","restoredAt":"%s","healthyAt":"%s","attempts":%d%s}\n' \
        "$name" "$start" "$restored" "$healthy" "$attempts" "$extra" >"$root/$name/fault.json"
    chmod 600 "$root/$name/fault.json"
}

cell_count() {
    "${compose[@]}" exec -T postgres psql -U postgres -d "$1" -At -v ON_ERROR_STOP=1 \
        -c 'SELECT count(*) FROM scheduler.run_history WHERE final = true'
}

outbox_count() {
    local first second
    first="$("${compose[@]}" exec -T postgres psql -U postgres -d kairos_cell0 -At -v ON_ERROR_STOP=1 \
        -c 'SELECT count(*) FROM scheduler.outbox')"
    second="$("${compose[@]}" exec -T postgres psql -U postgres -d kairos_cell1 -At -v ON_ERROR_STOP=1 \
        -c 'SELECT count(*) FROM scheduler.outbox')"
    printf '%d\n' "$((first + second))"
}

redis_replid() {
    "${compose[@]}" exec -T redis sh -c \
        'REDISCLI_AUTH="$KAIROS_REDIS_PASSWORD" redis-cli --no-auth-warning --user kairos INFO replication' \
        | tr -d '\r' | sed -n 's/^master_replid://p'
}

run_one() {
    local name="$1" output="/app/build/bench/$session/$1" start restored healthy id status
    local before during progress repl_before repl_after needed wait_seconds=1200
    mkdir -p "$root/$name"
    # Faults can leave process-local pools, admission counters or consumer generations in a
    # recovery state after their durable proof is complete. Start each independent experiment
    # from fresh stateless services while preserving the databases and brokers under test.
    for service in kairos-api kairos-watcher kairos-executor kairos-state; do
        needed="$(service_count "$service")"
        while IFS= read -r id; do
            [[ -n "$id" ]] && docker restart "$id" >/dev/null
        done < <(service_ids "$service")
        wait_healthy "$service" "$needed"
    done
    # Exact per-key snapshots are intentionally unbounded for proof workloads. Reset the
    # target between experiments so an aggregate run does not retain the preceding
    # experiment's keys or rotate its CA while the next seed is reading it.
    id="$(service_id mock-target)"
    docker restart "$id" >/dev/null
    wait_healthy mock-target 1
    wait_healthy kairos-api 1
    if [[ "$name" == slow-target ]]; then
        "${compose[@]}" run --rm --no-deps bench-cli --output-dir "$output" seed --jobs 500 --interval-sec 1 \
            --max-retries 5 --retry-max-age-sec 3600 --max-concurrency 200 \
            --target-url 'https://mock-target:8443/behave/slow?latencyMs=5000&idempotent=true'
    else
        "${compose[@]}" run --rm --no-deps bench-cli --output-dir "$output" seed --jobs 500 --interval-sec 1 \
            --max-retries 5 --retry-max-age-sec 900 \
            --target-url 'https://mock-target:8443/behave/healthy?idempotent=true'
    fi
    local -a load_args=(--profile smoke --interval-drain-seconds 30)
    # A slow endpoint cannot drain the normal 500/s warm-up. Starting measurement
    # immediately avoids carrying an unmeasured 60K-run backlog into exact verification.
    if [[ "$name" == slow-target ]]; then load_args+=(--warmup-seconds 0); fi
    if [[ "$name" == executor-kill ]]; then load_args+=(--chaos-seconds 300); fi
    if [[ "$name" == rolling-restart ]]; then load_args+=(--chaos-seconds 360); fi
    load_id="$("${compose[@]}" run -d --no-deps bench-cli --output-dir "$output" load "${load_args[@]}")"
    wait_file "$root/$name/baseline.json"
    sleep 5
    start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    case "$name" in
        executor-kill)
            for ((i = 1; i <= 5; i++)); do
                [[ "$(docker inspect -f '{{.State.Running}}' "$load_id")" == true ]] || {
                    echo "Load ended before all five executor kills" >&2
                    return 1
                }
                id="$(service_id kairos-executor)"
                stopped_executor="$id"
                docker kill "$id" >/dev/null
                docker start "$id" >/dev/null
                wait_healthy kairos-executor 2
                stopped_executor=""
                # Treat these as five independent node-loss observations rather than one
                # continuous half-capacity outage; allow the healthy pair to recover between
                # kills while keeping every fault inside the measured window.
                (( i < 5 )) && sleep 45
            done
            restored="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            wait_healthy kairos-executor 1
            healthy="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            record_fault "$name" "$start" "$restored" "$healthy" 5
            ;;
        watcher-handover)
            id="$(service_id kairos-watcher)"
            stopped_watcher="$id"
            docker update --restart=no "$id" >/dev/null
            docker kill "$id" >/dev/null
            sleep 20
            docker start "$id" >/dev/null
            docker update --restart=unless-stopped "$id" >/dev/null
            stopped_watcher=""
            restored="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            wait_healthy kairos-watcher 1
            healthy="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            record_fault "$name" "$start" "$restored" "$healthy"
            ;;
        kafka-pause)
            before="$(outbox_count)"
            paused_id="$(service_id kafka)"
            docker pause "$paused_id" >/dev/null
            sleep 60
            during="$(outbox_count)"
            docker unpause "$paused_id" >/dev/null
            paused_id=""
            restored="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            wait_healthy kafka 1
            healthy="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            record_fault "$name" "$start" "$restored" "$healthy" 1 \
                ",\"outboxBefore\":$before,\"outboxDuring\":$during"
            ;;
        redis-restart)
            repl_before="$(redis_replid)"
            id="$(service_id redis)"
            docker restart "$id" >/dev/null
            restored="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            wait_healthy redis 1
            healthy="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            repl_after="$(redis_replid)"
            record_fault "$name" "$start" "$restored" "$healthy" 1 \
                ",\"replicationIdChanged\":$([[ "$repl_before" != "$repl_after" ]] && echo true || echo false)"
            ;;
        cell-pause)
            before="$(cell_count kairos_cell1)"
            "${compose[@]}" exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
                -c 'ALTER DATABASE kairos_cell0 WITH ALLOW_CONNECTIONS false' >/dev/null
            cell_disabled=1
            "${compose[@]}" exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
                -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'kairos_cell0'" >/dev/null
            sleep 30
            during="$(cell_count kairos_cell1)"
            "${compose[@]}" exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
                -c 'ALTER DATABASE kairos_cell0 WITH ALLOW_CONNECTIONS true' >/dev/null
            cell_disabled=0
            restored="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            wait_healthy kairos-api 1
            healthy="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            progress=$((during - before))
            record_fault "$name" "$start" "$restored" "$healthy" 1 \
                ",\"cell1FinalizedDuringFault\":$progress"
            ;;
        slow-target)
            # The seeded endpoint stays slow for the full load window.
            ;;
        rolling-restart)
            for service in kairos-api kairos-watcher kairos-executor kairos-state; do
                needed="$(service_count "$service")"
                (( needed > 0 )) || { echo "No containers for $service" >&2; return 1; }
                while IFS= read -r id; do
                    [[ -n "$id" ]] || continue
                    docker restart "$id" >/dev/null
                    wait_healthy "$service" "$needed"
                done < <(service_ids "$service")
            done
            restored="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            healthy="$restored"
            record_fault "$name" "$start" "$restored" "$healthy"
            ;;
    esac
    status="$(docker wait "$load_id")"
    docker logs --tail 25 "$load_id"
    docker rm "$load_id" >/dev/null
    load_id=""
    if [[ "$name" == slow-target ]]; then
        restored="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        healthy="$restored"
        record_fault "$name" "$start" "$restored" "$healthy"
        wait_seconds=3600
    fi
    [[ "$status" == 0 ]] || { echo "Load failed: $name (exit $status)" >&2; return 1; }
    "${compose[@]}" run --rm --no-deps bench-cli --output-dir "$output" verify --chaos \
        --wait-seconds "$wait_seconds"
}

for name in "${experiments[@]}"; do
    if [[ "$selection" == all || "$selection" == "$name" ]]; then
        echo "Starting $name"
        run_one "$name"
    fi
done

if [[ "$selection" == all ]]; then
    "${compose[@]}" run --rm --no-deps bench-cli --output-dir "/app/build/bench/$session" scenario chaos
fi
echo "Evidence: $root"
