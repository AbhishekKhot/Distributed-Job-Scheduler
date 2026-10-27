# Local kind validation

T-404 runs the packaged Kairos stack in a two-node kind cluster. The scripts download
checksum-pinned `kind`, `kubectl`, and Helm binaries into the ignored `build/kind` directory,
install checksum-pinned Bitnami PostgreSQL, Kafka, and Redis charts, build/load the local Kairos
images, and install the Kairos Helm chart with `values-kind.yaml`.

```bash
make kind-up
make kind-test
make kind-down
```

`kind-test` runs a short smoke benchmark and the T-304 E2E scenarios as Kubernetes Jobs. The
smoke Job cross-checks database history against the mock target and fails on missing work,
unexpected calls, DLQ entries, or duplicate effects. Generated credentials, downloaded tools,
charts, and kubeconfig files stay under ignored `build/kind`; `kind-down` deletes the cluster and
generated credentials.

The host requirements are Docker, Make, Bash, curl, and OpenSSL. Chart versions, OCI manifest
digests, and downloaded archive checksums are recorded in `charts.lock`; container images are
also digest-pinned.
