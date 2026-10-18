#!/bin/bash
# Creates the Kairos topics (LLD section 4.2). Safe to re-run: existing topics are left alone.
# Partition counts and retention match production; the replication factor is 1 because the
# local stack has a single broker (production uses 3 with min.insync.replicas = 2).
set -euo pipefail

bootstrap="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
topics_cmd=/opt/kafka/bin/kafka-topics.sh

hour=3600000
day=$((24 * hour))

create() {
  local name="$1" partitions="$2" retention_ms="$3"
  "$topics_cmd" --bootstrap-server "$bootstrap" --create --if-not-exists \
    --topic "$name" --partitions "$partitions" --replication-factor 1 \
    --config retention.ms="$retention_ms" \
    --config min.insync.replicas=1
}

create kairos.run.high      24 "$day"
create kairos.run.medium    48 "$day"
create kairos.run.low       24 "$day"
create kairos.run.result    48 "$day"
create kairos.run.cancel     6 "$hour"
create kairos.run.completed 12 $((3 * day))
create kairos.run.dead       6 $((14 * day))

"$topics_cmd" --bootstrap-server "$bootstrap" --list
