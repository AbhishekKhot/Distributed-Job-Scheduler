#!/usr/bin/env python3
"""Fail when rendered Kairos workloads violate the chart's pod-security contract."""

import sys

import yaml


def pod_spec(document):
    kind = document.get("kind")
    spec = document.get("spec", {})
    if kind == "Deployment":
        return spec.get("template", {}).get("spec")
    if kind == "Job":
        return spec.get("template", {}).get("spec")
    if kind == "CronJob":
        return spec.get("jobTemplate", {}).get("spec", {}).get("template", {}).get("spec")
    return None


def require(condition, message, failures):
    if not condition:
        failures.append(message)


def main():
    documents = [item for item in yaml.safe_load_all(sys.stdin) if item]
    failures = []
    require(not any(item.get("kind") == "Secret" for item in documents), "chart must not render Secret objects", failures)
    for document in documents:
        spec = pod_spec(document)
        if spec is None:
            continue
        name = document.get("metadata", {}).get("name", "unknown")
        pod_security = spec.get("securityContext", {})
        require(pod_security.get("runAsNonRoot") is True, f"{name}: pod must runAsNonRoot", failures)
        require(pod_security.get("runAsUser") == 10001, f"{name}: pod UID must be 10001", failures)
        require(
            pod_security.get("seccompProfile", {}).get("type") == "RuntimeDefault",
            f"{name}: pod seccomp must be RuntimeDefault",
            failures,
        )
        volumes = {volume.get("name"): volume for volume in spec.get("volumes", [])}
        require("emptyDir" in volumes.get("tmp", {}), f"{name}: /tmp must use emptyDir", failures)
        for container in spec.get("initContainers", []) + spec.get("containers", []):
            prefix = f"{name}/{container.get('name', 'unknown')}"
            security = container.get("securityContext", {})
            require(security.get("runAsNonRoot") is True, f"{prefix}: runAsNonRoot missing", failures)
            require(security.get("runAsUser") == 10001, f"{prefix}: UID must be 10001", failures)
            require(security.get("allowPrivilegeEscalation") is False, f"{prefix}: privilege escalation allowed", failures)
            require(security.get("readOnlyRootFilesystem") is True, f"{prefix}: root filesystem is writable", failures)
            require("ALL" in security.get("capabilities", {}).get("drop", []), f"{prefix}: capabilities not dropped", failures)
            limits = container.get("resources", {}).get("limits", {})
            require(bool(limits.get("cpu")) and bool(limits.get("memory")), f"{prefix}: CPU/memory limits required", failures)
            mounts = {mount.get("mountPath"): mount for mount in container.get("volumeMounts", [])}
            require(mounts.get("/tmp", {}).get("name") == "tmp", f"{prefix}: /tmp emptyDir not mounted", failures)
        if name.endswith("-executor"):
            require(spec.get("terminationGracePeriodSeconds", 0) >= 75, f"{name}: drain grace must be at least 75s", failures)
    if failures:
        print("\n".join(f"ERROR: {failure}" for failure in failures), file=sys.stderr)
        return 1
    print(f"policy check passed for {len(documents)} rendered objects")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
