# Kairos Helm chart

This chart deploys the four Kairos services, a pre-install/pre-upgrade migration Job, and the daily partition-maintenance CronJob. It also defines PodDisruptionBudgets, API CPU autoscaling, KEDA Kafka-lag autoscaling, and default-deny network policies.

## Install

Use an immutable image tag and an environment overlay:

```bash
helm upgrade --install kairos deploy/helm/kairos \
  --namespace kairos --create-namespace \
  -f deploy/helm/kairos/values-gke.yaml \
  --set-string images.tag="$IMAGE_TAG" \
  --atomic --timeout 15m
```

`values.yaml` documents every setting. `values-kind.yaml` is the local overlay used by T-404. `values-gke.yaml` is a production example: replace every `PROJECT`, `REGION`, `LOCATION`, database/Kafka/Redis address, and sample CIDR before installation. In particular, keep infrastructure CIDRs separate from the executor target CIDRs. Kubernetes NetworkPolicy cannot authorize DNS names, so those CIDR lists must contain all resolved private addresses and must be updated when the backing ranges change.

The API Service is ClusterIP by default. An ingress controller or Gateway owned by the platform should route public traffic to port 8080. Update `networkPolicy.apiIngress` to select that controller. The executor Service is internal; its port 8080 accepts the endpoint-test route only from API pods. Port 8081 is reserved for health and Prometheus traffic selected by `networkPolicy.monitoringIngress`.

## Secrets and workload identity

The chart never renders a Kubernetes `Secret` or accepts secret values in `values.yaml`.

For GKE, the chart mounts Google Secret Manager versions directly with the managed Secret Manager CSI driver and Workload Identity Federation. Each component has a distinct Kubernetes ServiceAccount and SecretProviderClass; set its `iam.gke.io/gcp-service-account` annotation to the matching Google service account and keep `runtimeSecrets.csi.objectsByComponent` limited to that service's needs. Grant only the listed Secret Manager versions and required Cloud KMS operations to each identity. Enable the GKE Secret Manager add-on and automatic rotation on the cluster. The default network policy permits both documented GKE metadata-server endpoint variants so a Dataplane V2 migration does not interrupt identity; remove the unused variant only when the cluster mode is fixed and verified.

The runtime Secret Manager set uses these filenames:

| Filename | Purpose |
|---|---|
| `KAIROS_DB_PASSWORD` | `sched_app` database password |
| `KAIROS_REDIS_PASSWORD` | Redis ACL password |
| `KAIROS_KAFKA_USERNAME` | Managed Kafka SASL user |
| `KAIROS_KAFKA_PASSWORD` | Managed Kafka SASL password |
| `KAIROS_API_KEY_PEPPER` | 32-byte API-key HMAC pepper encoded as 64 hex characters |
| `KAIROS_CURSOR_HMAC_KEY` | 32-byte cursor HMAC key encoded as 64 hex characters |
| `kafka-ca.pem` | optional Kafka CA bundle |
| `redis-ca.pem` | optional Redis CA bundle |

The owner set contains `KAIROS_DB_PASSWORD` for `sched_owner`. The migration and maintenance service accounts should be the only identities allowed to access it. The operator set contains a separate DML-only control-database password and must not grant schema-owner privileges. Mounted credential files are read into the Java process environment by the container startup command; their values never appear in the Pod manifest. CA bundles stay mounted as files.

When CSI is disabled, create `runtimeSecrets.existingSecret` and `ownerSecrets.existingSecret` out of band with the same keys. The kind workflow does this from generated local credentials. Do not commit those objects or their source files.

KEDA runs in a separate controller pod and cannot read a Kairos container's CSI mount. For SASL Kafka, provision `autoscaling.keda.kafka.authenticationSecretName` outside this chart, preferably with GKE `SecretSync`, containing only `KAIROS_KAFKA_USERNAME` and `KAIROS_KAFKA_PASSWORD`. The chart renders a KEDA `TriggerAuthentication`, never the Secret itself. Set `authenticationRef` instead if the platform owns a pre-existing `TriggerAuthentication`.

Production Kafka must use `SASL_SSL`; the application receives the configured mechanism, username, password and optional CA path. The local kind overlay intentionally keeps Kafka `PLAINTEXT` inside the private test cluster.

## Workload behavior

- All containers run as UID/GID 10001 with `RuntimeDefault` seccomp, no privilege escalation, a read-only root filesystem, and all Linux capabilities dropped. `/tmp` is the only writable filesystem and is backed by `emptyDir`.
- Liveness and readiness probes use the Spring Boot management server on port 8081.
- Every workload declares CPU and memory requests and limits. PDBs allow one unavailable replica.
- The executor gets 75 seconds to terminate: its application first refuses readiness, drains consumers for up to 65 seconds, and requeues unsent work before exit.
- API scales on CPU. Executor scales on lag for `kairos.run.high`, `kairos.run.medium`, and `kairos.run.low`; state scales on `kairos.run.result` lag. Minimum replicas remain two if KEDA cannot reach Kafka.
- The migration hook runs before install and upgrade with owner credentials. Partition maintenance runs daily at 02:17 UTC with `concurrencyPolicy: Forbid`.

## Operator session

Enable `operatorSession.enabled` only for a short, audited maintenance window. The resulting Job runs an idle container for at most one hour; commands are started through an interactive exec stream so a generated password is not written to the Pod's main log. Restrict `pods/exec`, `pods/log`, Job mutation, and the operator Secret to the operator role, and delete the Job immediately afterward.

```bash
helm upgrade kairos deploy/helm/kairos --reuse-values --set operatorSession.enabled=true
POD="$(kubectl get pods -l app.kubernetes.io/component=operator -o jsonpath='{.items[0].metadata.name}')"

# Secret Manager CSI mode. Replace the final arguments with exactly one supported command.
kubectl exec -it "$POD" -- /bin/sh -ec \
  'export KAIROS_DB_PASSWORD="$(cat /var/run/secrets/kairos-operator/KAIROS_DB_PASSWORD)"; exec java org.springframework.boot.loader.launch.JarLauncher "$@"' \
  operator user reset-password --email operator@example.com

kubectl delete job -l app.kubernetes.io/component=operator
```

With `operatorSecrets.existingSecret`, the password is already present in the exec process environment and the command can invoke `java org.springframework.boot.loader.launch.JarLauncher ...` directly. Never run password reset non-interactively or retrieve its output with `kubectl logs`.

## Validation

Run validation only through Docker. Long-running invocations should use detached mode.

```bash
docker run --rm -v "$PWD:/work" -w /work alpine/helm:3.17.3 \
  lint deploy/helm/kairos

docker run --rm -v "$PWD:/work" -w /work alpine/helm:3.17.3 \
  template kairos deploy/helm/kairos > build/kairos-helm.yaml

docker run --rm -v "$PWD:/work" ghcr.io/yannh/kubeconform:v0.7.0 \
  -strict -summary -ignore-missing-schemas /work/build/kairos-helm.yaml

docker run --rm -i -v "$PWD:/work" -w /work python:3.13.2-alpine3.21 \
  sh -c 'pip install --quiet PyYAML==6.0.2 && python deploy/helm/kairos/tests/policy.py' \
  < build/kairos-helm.yaml
```

Repeat `helm lint` and rendering with `-f values-kind.yaml` and `-f values-gke.yaml`. Kubeconform skips schemas it cannot retrieve for the KEDA and Secrets Store CSI CRDs while still validating all built-in Kubernetes objects strictly.
