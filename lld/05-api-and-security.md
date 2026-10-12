# Kairos LLD Part 05: API and Security

Back to [LLD index](../LLD.md).

## 1. REST conventions

| Concern | Rule |
|---|---|
| Base path | `/v1/api`, JSON only (`application/json`). Other content types → `415`. |
| Binding | Java `record` DTOs. Jackson runs with `FAIL_ON_UNKNOWN_PROPERTIES = true`, `FAIL_ON_NULL_FOR_PRIMITIVES = true` and `FAIL_ON_TRAILING_TOKENS = true`, plus nesting depth, string length and document size limits through `StreamReadConstraints`. **No payload is spread into entities.** Mappers copy named fields only (mass-assignment safe). |
| Validation | Bean Validation on DTO shape (size, pattern, range), then domain constructors validate semantics (cron, zone, template, DAG). Fails closed with `400`. |
| Errors | RFC 9457 `application/problem+json`: `type`, `title`, `status`, `detail` (generic), `instance`, `traceId`, `errors[]` (field path and code). **Rejected values, stack traces and SQL errors are never echoed back.** `5xx` responses carry only a generic message and the `traceId`. |
| Pagination | `?limit` (1–200, default 50) and `?cursor`. The cursor is base64url of `(sortKey, id)`, strictly validated on decode. |
| Request size | Body ≤ 1 MB (8 MB for `/jobs:batch`). Header and URL limits are set in the server configuration. |
| Concurrency | `PUT` requires `If-Match: <version>` (optimistic locking via `version`); a mismatch returns `412`. |
| Idempotency | `Idempotency-Key` on `POST /jobs`, `POST /jobs:batch`, `POST /workflows`, `POST /dlq:redrive` and `/runnow` (part 01 `api_idempotency`) |

### 1.1 Resources (DTO outlines)

```
EndpointRequest {
  name: string                           ^[a-z][a-z0-9-]{1,62}$
  method: enum GET|POST|PUT|PATCH|DELETE|HEAD
  urlTemplate: string                    ≤ 2048; https only; host literal; {param} only in path; no userinfo; no fragment
  defaultHeaders: map<string,string>     ≤ 50 entries; names RFC 9110 tokens; reserved and credential names rejected (§1.2)
  auth: { type: NONE|BASIC|BEARER|API_KEY|OAUTH2_CLIENT_CREDENTIALS,
          secretRef?: string, headerName?: string (API_KEY), tokenUrl?: https url, scope?: string }
  caBundle?: string                      name in tenant_ca_bundles
  timeoutMs: int                         1000..300000, default 30000
  successStatus: ["2xx" | "200".."599"]  default ["2xx"]
  statusOverrides: map<"100".."599", SUCCESS|RETRYABLE|NON_RETRYABLE>
  retryPolicy?: { maxRetries 0..10 (default 5), strategy EXPONENTIAL_JITTER|FULL_JITTER|FIXED,
                  baseDelaySec 1..3600 (default 30), maxDelaySec ≤ 86400 (default 600),
                  maxAgeSec ≤ 604800 (default 86400), respectRetryAfter (default true) }
  limits?: { maxConcurrency 1..10000, ratePerSec 1..100000 }
  outputs?: map<name, { from: BODY|HEADER, path?: jsonpath-lite, name?: header, required: bool, sensitive: bool }>  ≤ 32
  maxResponseBytes?: 1024..1048576 (default 262144)
  suspendPolicy?: { enabled (default true), deadLetterThreshold (50), failureRatio (0.8), minAttempts (200), windowMin (5) }
  cancelEndpoint?: { method, urlTemplate }   same rules as above
}

JobRequest {
  name: string ≤ 128
  schedule: { type: IMMEDIATE|ONE_TIME|CRON|INTERVAL, at?: RFC 3339 instant, expression?: 5-field cron,
              timezone?: IANA id (default UTC), everySec?: 1..2592000, spreadSec?: 0..3600,
              dstPolicy?: SHIFT|SKIP (gap) + ONCE|TWICE (overlap) }
  endpoint: string                        endpoint name in the same tenant
  request?: { pathParams, query, headers, body (JSON) | bodyText, contentType }    templates allowed (§6)
  retryPolicy?: same as above (overrides the endpoint)
  priority: 1..10 (default 5)
  misfirePolicy: RUN_ALL|RUN_LATEST|SKIP (default RUN_LATEST), maxCatchup 1..1000 (default 100)
  maxLatenessSec?: 1..86400
  autoPauseAfter: 0..100 (default 3; 0 disables)
}

WorkflowRequest {
  name, schedule (as for jobs), priority, misfirePolicy, maxLatenessSec,
  onTaskFailure: BLOCK_FOR_REVIEW|FAIL_FAST (default BLOCK_FOR_REVIEW), reviewTimeoutSec (default 86400),
  tasks: map<taskKey ^[A-Za-z][A-Za-z0-9_-]{0,63}$, { endpoint, dependsOn: [taskKey], request?, retryPolicy?, outputs? }>  ≤ 200
}
```

### 1.2 Header rules for endpoint defaults and job request headers

| Header category | Rule |
|---|---|
| Reserved: `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Upgrade`, `TE`, `Trailer`, `Keep-Alive`, `Proxy-*`, `Idempotency-Key`, `traceparent`, `tracestate`, `X-Kairos-*` | Rejected |
| Credential names: `Authorization`, `Proxy-Authorization`, `Cookie`, `X-Api-Key`, and any name matching `(?i)(token|secret|password|api[-_]?key)` | Allowed **only** when the whole value is a single placeholder that references a **sensitive** DAG output. A literal credential value is rejected (`400`); credentials must go through `auth.secretRef`. |
| All values | Visible ASCII plus space and tab; no CR, LF or NUL; ≤ 8 KB each; ≤ 16 KB in total |

## 2. Authentication: API keys (HLD §15)

```
Key text:   sk_{env}_{keyId}_{secret}
            env    ∈ {dev, test, prod}               (fixed per deployment, checked)
            keyId  = 16 chars [A-Za-z0-9]            (SecureRandom)
            secret = 43 chars base62 = 256 bits      (SecureRandom)
Stored:     secret_hash = HMAC-SHA256(pepper[pepper_version], secret)   -- 32 bytes
```

**Generation** (`ApiKeyService.create`):
- `SecureRandom.getInstanceStrong()` is not used, because it can block. The default `SecureRandom` (DRBG/NativePRNG) is a CSPRNG and is used for both parts.
- The plaintext is returned once in the `201` response body, with `Cache-Control: no-store`.

**Pepper:**
- 32 random bytes, stored in Secret Manager (local: the `.env` file generated by `scripts/dev-keys.sh`, which is gitignored).
- Loaded at startup into a `PepperProvider` that supports several versions, for rotation.
- Never logged.

**Verification** (`ApiKeyAuthenticationFilter`, a `OncePerRequestFilter` placed before Spring Security's authorization filter):

```
1. header = request.getHeader("Authorization"); must be exactly "Bearer " + token; token length must be exactly
   3 + 1 + len(env) + 1 + 16 + 1 + 43. Otherwise -> 401 (generic body).
2. Split on '_' with a fixed field count (no regex); validate the character class of each field with a simple loop.
3. row = apiKeyCache.get(keyId)       // Caffeine, 60s TTL, negative results cached for 10s; loads from the primary DB
4. if row absent, revoked or expired -> 401
5. expected = row.secret_hash; actual = HMAC(pepper[row.pepper_version], secret)
   if !MessageDigest.isEqual(expected, actual) -> 401         // constant time
6. SecurityContext = ApiKeyAuthentication(tenantId, keyId, scopes → authorities "SCOPE_x")
7. lastUsedRecorder.mark(keyId)        // batched UPDATE every 60s
```

- The cache stores rows, never the secret, so a revocation takes effect within 60s.
- Failures add to the `auth_failures_total{reason}` metric and the per-IP failure bucket (§3). Every 100th failure per key id is sampled into `audit_log`, so an attack cannot flood the log.

**Spring Security configuration:**

| Setting | Value |
|---|---|
| Sessions | `STATELESS`; no cookies are issued |
| CSRF | **Disabled. This is safe because there are no ambient credentials:** the API accepts only the `Authorization` header and never cookies. Browsers cannot attach the credential cross-site. |
| Authorization | `authorizeHttpRequests`: `/actuator/health/**` is permitted on the **management port only**; everything else requires authentication. Method security uses `@PreAuthorize("hasAuthority('SCOPE_jobs:write')")` and so on. |
| Security headers | `Strict-Transport-Security: max-age=31536000; includeSubDomains`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` |
| CORS | Disabled (there is no browser client) |
| Platform admin key | <ul><li>Same format. It is verified against a hash read from Secret Manager at startup.</li><li>It grants only the `platform:admin` authority, used by `POST /admin/tenants`.</li><li>Every use is audited.</li></ul> |

**Object-level authorization.** Every service method takes the `TenantId` from the `SecurityContext`, never from the request. Every repository query includes `tenant_id = :tenant` (ArchUnit-enforced, LLD index §3). A resource owned by another tenant returns `404`.

## 3. Rate limiting (fail closed)

| Bucket | Key | Default limit | Applied |
|---|---|---|---|
| Per IP, before authentication | `ip:{client IP}` | 50 requests/s; 20 failed authentications per minute | Before key verification (limits brute force) |
| Per API key and route class | `key:{keyId}:{read\|write\|redrive}` | read 500/s, write 200/s, redrive 5/s | After authentication |
| Per tenant | `tenant:{id}` | from `tenants.quotas` | After authentication |

- **Implementation.** Bucket4j with its Lettuce-based Redis proxy manager (compare-and-swap), keys `kairos:rl:{bucket}` with expiry. Using Redis keeps rate limiting off Postgres.
- **Client IP.** Taken from the gateway's `X-Forwarded-For` header, but only when the request comes from a trusted proxy CIDR (configured). Otherwise the socket address is used.
- **If the limiter store is unreachable,** the request gets `503`. It fails closed.
- `429` responses carry `Retry-After`.

## 4. Egress guard: SSRF defence for internal targets (HLD §15)

**At save time** (`EgressPolicy.check(urlTemplate, tenant)`):

| Check | Rule |
|---|---|
| Scheme | `https` |
| Port | Must be in `tenant.egress_policy.ports` (default `[443, 8443]`) |
| URL shape | No userinfo, no fragment. The host is a literal: a DNS name or an IP literal, never a template. |
| Host | Must match `egress_policy.hosts` (exact), `egress_policy.suffixes` (label-boundary suffix match, e.g. `.orders.svc.cluster.local`), or, for IP literals, `egress_policy.cidrs` |
| Global deny names | `localhost`, `metadata.google.internal`, `*.internal` metadata aliases, and the scheduler's own service names (configuration) |

**At connect time** (`ValidatingAddressResolverGroup`, a Netty `AddressResolverGroup` used by Reactor Netty):

```
resolve(host):
  addrs = dnsResolver.resolveAll(host)            // Netty DnsNameResolver; TTL capped at 30s
  for a in addrs:
    ip = IpNormalizer.normalize(a)                 // IPv4-mapped/compatible IPv6 and NAT64 → IPv4
    deny if ip in GLOBAL_DENY: 0.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16, 224.0.0.0/4, 240.0.0.0/4,
                               255.255.255.255/32, ::/128, ::1/128, fe80::/10, ff00::/8,
                               + configured control-plane CIDRs (Kubernetes API, PgBouncer, Kafka, pod CIDR of the scheduler namespace)
    deny unless ip in tenant.egress_policy.cidrs
  if no address is allowed → fail with EgressBlockedException → NON_RETRYABLE (POLICY_BLOCKED)
  return the allowed addresses only
```

- Netty connects to the **already validated** address, while the URI host stays the DNS name. SNI and certificate hostname verification therefore still check the real hostname. **This closes the DNS-rebinding gap**.
- Redirects are off. If an endpoint enables them, each hop goes through the same resolver and the save-time host check.
- TLS uses `SslContextBuilder.forClient()` with `TLSv1.3` and `TLSv1.2` only, JDK default trust plus the tenant's CA bundle, and **endpoint identification set to `HTTPS`**. Verification is never disabled. Local development trusts the dev CA created by `scripts/dev-keys.sh`, not "trust all".

## 5. Cryptography (Google Tink)

| Purpose | Primitive | Key handling | AAD (context binding) |
|---|---|---|---|
| Target credentials at rest (`tenant_secrets`) | `XCHACHA20_POLY1305` AEAD | Tenant `SECRETS` keyset, encrypted under the KMS KEK (`tenant_keysets`) | `tenantId|secret|name|version` |
| Sensitive DAG outputs (in Kafka and `task_outputs`) | `XCHACHA20_POLY1305` AEAD | Tenant `OUTPUTS` keyset | `tenantId|output|workflowRunId|taskKey` |
| Request signing to targets | `HMAC_SHA256` (256-bit tag) | Tenant `SIGNING` keyset; the key is shown once through `POST /signing-keys:rotate` | — |

- **KEK.** GCP uses `gcp-kms://projects/…/cryptoKeys/tenant-keysets` via tink-gcpkms. Locally, a dev keyset file whose path comes from the `KAIROS_DEV_KEK_PATH` environment variable, generated by `scripts/dev-keys.sh` and gitignored. **No key material is ever committed.**
- **Rotation.** A new primary key is added to the keyset. Older ciphertexts still decrypt, because Tink selects the key by the key-id prefix. Re-encryption is a background maintenance task.
- **Decrypted keysets** are cached per pod per tenant (they are small), so KMS is called only on a cache miss.
- **Signature header:**
  - `X-Kairos-Signature: t=<unixSeconds>,kid=<keyId>,v1=<hex HMAC(t + "." + method + "." + path + "." + sha256Hex(body))>`
  - Targets verify it with a constant-time comparison and a ±300s tolerance. A verification snippet goes in the docs.

## 6. Template and JSONPath grammars

### 6.1 Placeholders

```
template     := segment*
segment      := literal | escape | placeholder
escape       := "\{{"                                   -> literal "{{"
placeholder  := "{{" ws* ref ws* "}}"
ref          := "tasks." key ".outputs." name
              | "run." ("id" | "scheduledAt" | "attempt")
              | "job." ("id" | "name")
              | "workflow." ("id" | "runId" | "scheduledAt")
key, name    := [A-Za-z] [A-Za-z0-9_-]{0,63}
ws           := " "
```

**Parser.** A hand-written single-pass scanner, with no regex and no expression evaluation. Limits: input ≤ 64 KB, ≤ 100 placeholders per request.

**Validation at save time:**
- every `tasks.X` must be an **ancestor** of the current task (`DagPlan.ancestors`);
- every `outputs.Y` must be declared on X's endpoint or task outputs;
- jobs may only use `run.*` and `job.*`.

**Rendering by position:**

| Position | Rule |
|---|---|
| Body JSON, a value that is exactly one placeholder | Replaced by the JSON value (type kept) |
| Body JSON, a placeholder inside a string | Value stringified (objects as compact JSON) and inserted. Escaping is handled by the JSON writer, because we build a tree, not text. |
| `bodyText` | Plain substitution of the stringified value (the content type is the target's concern) |
| Header | The stringified value must not contain CR, LF or NUL and must be ≤ 8 KB. Otherwise `RENDER_ERROR`. |
| Path parameter | RFC 3986 percent-encoding of a path segment, including `/` |
| Query value | `application/x-www-form-urlencoded` encoding |
| Host | **Never templated** |

### 6.2 JSONPath-lite (output extraction)

```
path     := "$" step*
step     := "." name | "[" index "]" | "['" quoted "']"
name     := [A-Za-z_] [A-Za-z0-9_-]{0,63}
index    := 0 .. 9999
quoted   := up to 64 chars, no quote or backslash
```

- **No filters, wildcards, recursive descent or scripts.** That rules out ReDoS and expression injection.
- Path length ≤ 256 characters, ≤ 32 steps.
- Evaluated on a Jackson tree that is parsed with the `StreamReadConstraints` limits.

## 6A. Redis security

| Concern | Rule |
|---|---|
| Network | Private IP only (Memorystore with Private Service Access; a ClusterIP service locally). NetworkPolicy allows only executor, watcher and api pods to reach Redis. |
| Transport | **TLS in transit**: Memorystore in-transit encryption, and the CA certificate is mounted from a Secret. Locally, `redis-server --tls-port` with the dev CA. Lettuce verifies the peer (`SslVerifyMode.FULL`). |
| Authentication | Memorystore AUTH string, or Redis ACL users locally. Each role has its own ACL user, and **the passwords come from Secret Manager or `.env`**, never from code or config files. |
| Authorisation (local ACL) | `kairos-executor`: `+get +set +eval +evalsha +exists ~kairos:lock:* ~kairos:sent:*`; `kairos-watcher`: `+get +exists +info ~kairos:lock:* ~kairos:sent:*`; `kairos-api`: `+eval +evalsha +get +set +pexpire ~kairos:rl:*`. Default user disabled; `FLUSHALL`, `CONFIG`, `KEYS` and `DEBUG` denied. |
| Data stored | Lock tokens (ids and random values), sent markers and rate-limit counters only. **No payloads, secrets or personal data.** |
| Token randomness | 128-bit lock-token suffix from `SecureRandom` |
| Lua | Only the two fixed scripts in part 04 §3.2, loaded with `SCRIPT LOAD` at startup and called with `EVALSHA`. Never built from input. |

## 7. Secrets in configuration

| Secret | Production (GKE) | Local |
|---|---|---|
| Database passwords (one per role) | Secret Manager → External Secrets → Kubernetes Secret → env `SPRING_DATASOURCE_PASSWORD` | `.env` generated by `scripts/dev-keys.sh` |
| API-key pepper(s), platform admin key hash | Secret Manager | `.env` |
| Kafka credentials | Workload Identity (IAM SASL OAUTHBEARER) | None (local plaintext listener inside the Docker network only) |
| Redis credentials | Memorystore AUTH string in Secret Manager → External Secrets → env `REDIS_PASSWORD` | Per-role ACL passwords in `.env` (generated) |
| KMS KEK | Cloud KMS (never leaves KMS) | Dev keyset file outside the repo |

- `application.yaml` contains `${ENV_VAR}` references only.
- `.env.example` documents the variable names with empty values.
- A `gitleaks` scan runs in CI (part 07).

## 8. Security controls checklist

| Rule | Where it is enforced |
|---|---|
| Parameterised SQL only | `JdbcClient` named parameters and arrays (part 02); an ArchUnit rule forbids `Statement.execute*` and string-built SQL in repositories |
| Allow-list validation, fail closed | DTO constraints + domain constructors (§1); egress allowlist (§4); header rules (§1.2) |
| Explicit schema, no mass assignment | Records, `FAIL_ON_UNKNOWN_PROPERTIES`, field-by-field mappers |
| Bearer secrets hashed, constant-time comparison | API keys: HMAC with pepper + `MessageDigest.isEqual` (§2); target signature guidance (§5) |
| CSPRNG | `SecureRandom` for keys and secrets. Tink does its own nonces. Backoff jitter uses `RandomGenerator` on purpose, because it is not a security value. |
| No hardcoded secrets | §7, `gitleaks` in CI, `.env` and dev keys gitignored |
| AuthN and object-level AuthZ on every request | §2 filter, scopes, tenant-scoped repositories, `404` across tenants |
| Rate limiting (distributed, fail closed) | §3 |
| Security headers; generic errors; redacted logs | §2 configuration; RFC 9457 advice; `Redactor` (part 04 §3.3); logging allow-list (part 06) |
| CSRF and cookies | Not applicable: stateless bearer-header API with no cookies (§2) |
| TLS 1.2+ with verification; no automatic redirects | §4 |
| AEAD with unique nonces | Tink XChaCha20-Poly1305 (192-bit random nonces) (§5) |
| No dangerous deserialisation | Protobuf and Jackson trees only; no `ObjectInputStream`; Jackson default typing **off** |
| Safe regex on untrusted input | No regex in the hot path; hand-written parsers (§6); redaction patterns length-capped |
| SSRF | §4 (allowlist, deny ranges, validated-IP connect, no redirects, ports) |
| Distributed lock correctness and safety | Fence-aware Lua acquire, compare-and-delete release, `SecureRandom` tokens, Redis TLS and ACLs (§6A, part 04 §3.2) |
| Header injection | §1.2 and §6.1 (CR/LF rejected; reserved headers; Netty header validation on) |
| Template injection | No expression language; fixed reference grammar (§6) |
| Pinned dependencies with integrity | Gradle locking + `verification-metadata.xml` checksums; container images pinned by digest |
| Resources closed deterministically | try-with-resources for JDBC; Reactor `using`/`doFinally` for connections and buffers; `SmartLifecycle` shutdown |
