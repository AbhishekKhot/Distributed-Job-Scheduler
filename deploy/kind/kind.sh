#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KIND_DIR="$ROOT/deploy/kind"
BUILD_DIR="$ROOT/build/kind"
TOOLS_DIR="$BUILD_DIR/tools"
CHARTS_DIR="$BUILD_DIR/charts"
KUBECONFIG_FILE="$BUILD_DIR/kubeconfig"
CREDS_FILE="$BUILD_DIR/credentials.env"
CLUSTER_NAME="${KAIROS_KIND_CLUSTER:-kairos}"
NAMESPACE=kairos

KIND_VERSION=v0.33.0
KUBECTL_VERSION=v1.37.0
HELM_VERSION=v3.17.3

mkdir -p "$TOOLS_DIR" "$CHARTS_DIR"

fail() {
  echo "kind validation: $*" >&2
  exit 1
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

download() {
  local url="$1" output="$2" expected="$3"
  if [[ -f "$output" ]] && [[ "$(sha256 "$output")" == "$expected" ]]; then
    return
  fi
  local temporary="${output}.download"
  curl --fail --location --silent --show-error "$url" --output "$temporary"
  [[ "$(sha256 "$temporary")" == "$expected" ]] || fail "checksum mismatch for $url"
  mv "$temporary" "$output"
}

bootstrap_tools() {
  local os arch kind_sum kubectl_sum helm_sum helm_platform archive
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) arch=amd64 ;;
    arm64|aarch64) arch=arm64 ;;
    *) fail "unsupported architecture: $arch" ;;
  esac
  case "$os-$arch" in
    darwin-amd64)
      kind_sum=5a99f26f57246dc9319dd294803313197a0f34d33c525b3ea8b655db5916ece0
      kubectl_sum=d5276c0f4fde77fc446070290f345944a7f1fda153df6b960e5fde93b7a9bccd
      helm_sum=20ef8df4671349a6fc556a621be1170dd709c6c0cf5f7e83a2d9fb0515fd97fc
      ;;
    darwin-arm64)
      kind_sum=0c8c7dbe5e23594a198b786c4bc13dacc101fa6196b0cb0b23a1ca44e61f4b4f
      kubectl_sum=583beedaebe422e71d3f1a96acef8b1fef86ea2f09a45ad01aa6c9ce287c1380
      helm_sum=89aec43ce07b06239f1bba4a6507236bb48ae487bc5065a8e254d3ce58a16997
      ;;
    linux-amd64)
      kind_sum=aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d
      kubectl_sum=6129359f4e1f3848a5572ccb0b26cf28b8ca08cef38c95a765b2f64a2c961a2f
      helm_sum=ee88b3c851ae6466a3de507f7be73fe94d54cbf2987cbaa3d1a3832ea331f2cd
      ;;
    linux-arm64)
      kind_sum=20022bee6cfcd5086cb7234d218e3454e6090022f2a8f55d1fa7fcf42c3867a2
      kubectl_sum=922df28df248cc00a9e025f947704f1d1482de64ece54cfe57e61f19eaf1eef3
      helm_sum=7944e3defd386c76fd92d9e6fec5c2d65a323f6fadc19bfb5e704e3eee10348e
      ;;
    *) fail "unsupported platform: $os-$arch" ;;
  esac

  download "https://kind.sigs.k8s.io/dl/$KIND_VERSION/kind-$os-$arch" "$TOOLS_DIR/kind" "$kind_sum"
  download "https://dl.k8s.io/release/$KUBECTL_VERSION/bin/$os/$arch/kubectl" "$TOOLS_DIR/kubectl" "$kubectl_sum"
  chmod 0755 "$TOOLS_DIR/kind" "$TOOLS_DIR/kubectl"

  helm_platform="$os-$arch"
  archive="$BUILD_DIR/helm-$HELM_VERSION-$helm_platform.tar.gz"
  download "https://get.helm.sh/helm-$HELM_VERSION-$helm_platform.tar.gz" "$archive" "$helm_sum"
  if [[ ! -x "$TOOLS_DIR/helm" ]]; then
    tar -xzf "$archive" -C "$BUILD_DIR"
    mv "$BUILD_DIR/$helm_platform/helm" "$TOOLS_DIR/helm"
    rm -rf "$BUILD_DIR/$helm_platform"
    chmod 0755 "$TOOLS_DIR/helm"
  fi
}

generate_credentials() {
  if [[ -f "$CREDS_FILE" ]]; then
    return
  fi
  umask 077
  {
    printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'KAIROS_DB_OWNER_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'KAIROS_DB_APP_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'KAIROS_REDIS_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'KAIROS_LOCAL_KEK=%s\n' "$(openssl rand -base64 32 | tr -d '\n')"
    printf 'KAIROS_API_KEY_PEPPER=%s\n' "$(openssl rand -base64 32 | tr -d '\n')"
    printf 'KAIROS_CURSOR_HMAC_KEY=%s\n' "$(openssl rand -base64 32 | tr -d '\n')"
  } >"$CREDS_FILE"
}

load_credentials() {
  generate_credentials
  set -a
  # shellcheck disable=SC1090
  source "$CREDS_FILE"
  set +a
}

pull_charts() {
  while IFS='|' read -r name version _ archive_sum; do
    [[ -n "$name" && "${name:0:1}" != "#" ]] || continue
    local archive="$CHARTS_DIR/$name-$version.tgz"
    if [[ ! -f "$archive" ]] || [[ "$(sha256 "$archive")" != "$archive_sum" ]]; then
      rm -f "$archive"
      "$TOOLS_DIR/helm" pull "oci://registry-1.docker.io/bitnamicharts/$name" \
        --version "$version" --destination "$CHARTS_DIR"
    fi
    [[ "$(sha256 "$archive")" == "$archive_sum" ]] || fail "chart checksum mismatch: $name-$version"
  done <"$KIND_DIR/charts.lock"
}

kubectl() {
  KUBECONFIG="$KUBECONFIG_FILE" "$TOOLS_DIR/kubectl" "$@"
}

helm() {
  KUBECONFIG="$KUBECONFIG_FILE" "$TOOLS_DIR/helm" "$@"
}

apply_secrets() {
  kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$NAMESPACE" create secret generic kairos-postgres \
    --from-literal="postgres-password=$POSTGRES_PASSWORD" \
    --from-literal="password=$KAIROS_DB_APP_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$NAMESPACE" create secret generic kairos-postgres-init-credentials \
    --from-literal="KAIROS_DB_OWNER_PASSWORD=$KAIROS_DB_OWNER_PASSWORD" \
    --from-literal="KAIROS_DB_APP_PASSWORD=$KAIROS_DB_APP_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$NAMESPACE" create secret generic kairos-redis \
    --from-literal="redis-password=$KAIROS_REDIS_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$NAMESPACE" create secret generic kairos-runtime \
    --from-literal="KAIROS_DB_PASSWORD=$KAIROS_DB_APP_PASSWORD" \
    --from-literal="KAIROS_REDIS_PASSWORD=$KAIROS_REDIS_PASSWORD" \
    --from-literal="KAIROS_LOCAL_KEK=$KAIROS_LOCAL_KEK" \
    --from-literal="KAIROS_API_KEY_PEPPER=$KAIROS_API_KEY_PEPPER" \
    --from-literal="KAIROS_CURSOR_HMAC_KEY=$KAIROS_CURSOR_HMAC_KEY" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$NAMESPACE" create secret generic kairos-owner \
    --from-literal="KAIROS_DB_PASSWORD=$KAIROS_DB_OWNER_PASSWORD" \
    --from-literal="KAIROS_LOCAL_KEK=$KAIROS_LOCAL_KEK" --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$NAMESPACE" create secret generic kairos-bench \
    --from-literal="postgres-password=$POSTGRES_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
}

diagnostics() {
  kubectl -n "$NAMESPACE" get pods,jobs 2>/dev/null || true
  kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp 2>/dev/null | tail -40 || true
}

up() {
  command -v docker >/dev/null || fail "docker is required"
  command -v curl >/dev/null || fail "curl is required"
  command -v openssl >/dev/null || fail "openssl is required"
  bootstrap_tools
  load_credentials
  pull_charts

  if ! "$TOOLS_DIR/kind" get clusters | grep -qx "$CLUSTER_NAME"; then
    "$TOOLS_DIR/kind" create cluster --name "$CLUSTER_NAME" --config "$KIND_DIR/cluster.yaml" \
      --kubeconfig "$KUBECONFIG_FILE" --wait 180s
  else
    "$TOOLS_DIR/kind" export kubeconfig --name "$CLUSTER_NAME" --kubeconfig "$KUBECONFIG_FILE"
  fi
  trap diagnostics ERR
  apply_secrets
  kubectl apply -f "$KIND_DIR/postgres-init.yaml"

  helm upgrade --install postgres "$CHARTS_DIR/postgresql-18.12.16.tgz" -n "$NAMESPACE" \
    -f "$KIND_DIR/postgresql-values.yaml" --wait --timeout 10m
  helm upgrade --install kafka "$CHARTS_DIR/kafka-32.4.3.tgz" -n "$NAMESPACE" \
    -f "$KIND_DIR/kafka-values.yaml" --wait --timeout 10m
  helm upgrade --install redis "$CHARTS_DIR/redis-28.3.10.tgz" -n "$NAMESPACE" \
    -f "$KIND_DIR/redis-values.yaml" --wait --timeout 10m
  kubectl delete job kairos-kafka-topics -n "$NAMESPACE" --ignore-not-found
  kubectl apply -f "$KIND_DIR/kafka-topics-job.yaml"
  kubectl wait -n "$NAMESPACE" --for=condition=complete job/kairos-kafka-topics --timeout=5m

  make -C "$ROOT" images
  "$TOOLS_DIR/kind" load docker-image --name "$CLUSTER_NAME" \
    kairos/kairos-api:dev kairos/kairos-watcher:dev kairos/kairos-executor:dev \
    kairos/kairos-state:dev kairos/kairos-migrations:dev kairos/kairos-maintenance:dev \
    kairos/mock-target:dev kairos/bench-cli:dev

  kubectl apply -f "$KIND_DIR/mock-target.yaml"
  kubectl rollout status -n "$NAMESPACE" deployment/mock-target --timeout=5m
  helm upgrade --install kairos "$ROOT/deploy/helm/kairos" -n "$NAMESPACE" \
    -f "$ROOT/deploy/helm/kairos/values-kind.yaml" --wait --wait-for-jobs --timeout 15m
  kubectl wait -n "$NAMESPACE" --for=condition=complete job/kairos-migrations --timeout=5m
  kubectl wait -n "$NAMESPACE" --for=condition=available \
    deployment/kairos-api deployment/kairos-watcher deployment/kairos-executor deployment/kairos-state \
    --timeout=10m
  trap - ERR
  kubectl -n "$NAMESPACE" get pods,jobs
}

run_job() {
  local job="$1" timeout="$2"
  if ! kubectl wait -n "$NAMESPACE" --for=condition=complete "job/$job" --timeout="$timeout"; then
    kubectl logs -n "$NAMESPACE" "job/$job" --all-containers --prefix || true
    diagnostics
    return 1
  fi
  kubectl logs -n "$NAMESPACE" "job/$job" --all-containers --prefix
}

test_cluster() {
  bootstrap_tools
  [[ -f "$KUBECONFIG_FILE" ]] || fail "run make kind-up first"
  trap diagnostics ERR
  kubectl delete job -n "$NAMESPACE" kairos-kind-smoke kairos-kind-scenarios --ignore-not-found
  kubectl apply -f "$KIND_DIR/bench-jobs.yaml"
  run_job kairos-kind-smoke 15m
  run_job kairos-kind-scenarios 20m
  trap - ERR
  echo "kind validation passed: services ready, migrations complete, smoke verified with zero duplicates, E2E scenarios passed"
}

down() {
  bootstrap_tools
  "$TOOLS_DIR/kind" delete cluster --name "$CLUSTER_NAME"
  rm -f "$KUBECONFIG_FILE" "$CREDS_FILE"
}

case "${1:-}" in
  up) up ;;
  test) test_cluster ;;
  down) down ;;
  *) fail "usage: $0 {up|test|down}" ;;
esac
