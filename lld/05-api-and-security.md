# Kairos LLD Part 05: API and Security

Back to [LLD index](../LLD.md).

## 1. REST conventions

| Concern | Rule |
|---|---|
| Base path | `/v1`. Organization-owned resources live under `/v1/orgs/{orgId}/…`. JSON only (`application/json`); other content types → `415`. |
| Binding | Java `record` DTOs. Jackson runs with `FAIL_ON_UNKNOWN_PROPERTIES = true`, `FAIL_ON_NULL_FOR_PRIMITIVES = true` and `FAIL_ON_TRAILING_TOKENS = true`, plus nesting depth, string length and document size limits through `StreamReadConstraints`. **No payload is spread into entities.** Mappers copy named fields only (mass-assignment safe). |
| Validation | Bean Validation on DTO shape (size, pattern, range), then domain constructors validate semantics (cron, zone, template, DAG). Fails closed with `400`. |
| Errors | RFC 9457 `application/problem+json`: `type`, `title`, `status`, `detail` (generic), `instance`, `traceId`, `errors[]` (field path and code). **Rejected values, stack traces and SQL errors are never echoed back.** `5xx` responses carry only a generic message and the `traceId`. |
| Pagination | `?limit` (1–200, default 50) and `?cursor`. The cursor is base64url of `(sortKey, id)`, strictly validated on decode. |
| Request size | Body ≤ 1 MB (8 MB for `/jobs:batch`). Header and URL limits are set in the server configuration. |
| Concurrency | `PUT` requires `If-Match: <version>` (optimistic locking via `version`); a mismatch returns `412`. |
| Idempotency | `Idempotency-Key` on `POST …/jobs`, `…/jobs:batch`, `…/workflows`, `…/dlq:redrive`, `…/runnow` and `POST /v1/orgs`. The record lives in the database that owns the resource (the cell chosen by the job's name, or the control database) and is written in the same transaction (part 01 `api_idempotency`). `jobs:batch` groups items by cell and commits per cell. |

### 1.1 Resources (DTO outlines)

```
SignupRequest      { email: ≤ 254, lower-cased, domain in kairos.auth.signup-domains;
                     displayName: 1..100; password: 12..128 chars, not on the common-password list }
LoginRequest       { email, password }                         → { accessToken, expiresIn, refreshToken }
RefreshRequest     { refreshToken }                            → a new token pair (rotation)
PasswordReset      { token, newPassword }
OrgRequest         { slug: ^[a-z][a-z0-9-]{2,62}$, displayName: 1..100 }
InvitationRequest  { email, role: ADMIN|DEVELOPER|OPERATOR|VIEWER }
MemberUpdate       { role }                                    (OWNER only through transfer-ownership)
ApiKeyRequest      { name: 1..64, scopes: [scope] ⊆ creator's permissions, expiresAt? }
EgressRuleRequest  { type: HOST|SUFFIX|CIDR, value, ports: [1..65535] ⊆ kairos.egress.allowed-ports }
SecretRequest      { name: ^[a-z][a-z0-9-]{1,62}$, kind: BASIC|BEARER|API_KEY|OAUTH2_CLIENT, value (write-only) }

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

## 2. Identity and access

### 2.1 Accounts

**Sign up** (`POST /v1/auth/signup`):
1. Validate the DTO. The email domain must be in `kairos.auth.signup-domains`. The password must be 12–128 characters, not on the bundled common-password list (top 100K) and not equal to the email address.
2. Hash the password with **Argon2id** (`m = 64 MiB, t = 3, p = 1`, 16-byte salt from `SecureRandom`, 32-byte output), stored as a PHC string.
3. Insert the user (`email_verified_at = NULL`). Create a `VERIFY_EMAIL` token: 32 bytes from `SecureRandom`, base64url in the link, SHA-256 in `auth_tokens`, valid for 24 hours. Email the link through `NotificationPort`.
4. **Always** respond `202 Accepted` with the same body, whether or not the email already exists, so the endpoint cannot be used to discover accounts. An existing account is sent an "account already exists" email instead.

**Verify email** (`POST /v1/auth/verify-email {token}`): hash the token, then in one transaction set `used_at` on the matching unexpired `VERIFY_EMAIL` row and set `email_verified_at`. Unverified users can only call resend and log out.

**Log in** (`POST /v1/auth/login`):

```
1. Rate limits (§3): per IP and per email. Exceeded → 429.
2. user = findByEmail(lower(email))
3. if user is null: run Argon2id against a fixed dummy hash (equal time), then respond 401 "invalid email or password"
4. if user.locked_until > now() or user.status != ACTIVE: respond with the same generic 401
5. if !argon2.verify(user.password_hash, password):
     failed_logins + 1; after 5 failures locked_until = now() + 15 min × 2^(lockouts so far), at most 24h → generic 401
6. reset failed_logins; rehash if the Argon2 parameters have changed
7. create a session: family_id = new UUID, refresh token = 32 random bytes, store SHA-256 + expiry (30 days)
8. issue the access token (§2.2); update last_login_at; audit LOGIN_SUCCEEDED
```

**Password reset.** `POST /v1/auth/password/forgot` always answers `202`. If the user exists, a `RESET_PASSWORD` token (30 minutes, single-use) is emailed. `POST /v1/auth/password/reset` verifies the token, sets the new hash, marks the token used, and **revokes every session** of the user, all in one transaction.

**Password change** (`POST /v1/me/password`) requires the current password and also revokes every other session.

### 2.2 Access and refresh tokens

| Token | Format | Lifetime | Storage |
|---|---|---|---|
| Access | JWT, ES256. Claims: `iss`, `aud = kairos-api`, `sub` (user id), `sid` (session id), `iat`, `exp`, `jti`, `pa` (present only for platform admins). Organization roles are **not** in the token; they are checked per request. | 15 minutes | Not stored |
| Refresh | 32 random bytes, base64url, opaque | 30 days (sliding, capped by the family's absolute limit of 90 days) | SHA-256 in `user_sessions` |

- **Signing keys.** An ES256 keyset (Tink) is sealed by the KMS key and stored in `platform_keysets`. Each key has a `kid`, and keys rotate every 30 days. Public keys are published at `/.well-known/jwks.json`, and validation accepts the current and the previous key.
- **Validation.** Signature, `iss`, `aud`, `exp` (with 30s leeway), then a session check: `sid` must refer to an `ACTIVE` session family. That check is cached for 30s, so a revocation takes effect within 30s.
- **Refresh rotation** (`POST /v1/auth/refresh`), in one transaction:

```sql
-- lock the presented token's row
SELECT session_id, user_id, family_id, status, expires_at
FROM user_sessions WHERE token_hash = :hash FOR UPDATE;
-- ACTIVE and not expired  -> mark it ROTATED, insert a new ACTIVE row in the same family, return the new pair
-- ROTATED (already used)  -> token reuse: revoke the whole family and audit REFRESH_TOKEN_REUSE; respond 401
UPDATE user_sessions SET status = 'REVOKED' WHERE family_id = :family AND status <> 'REVOKED';
-- REVOKED or expired      -> 401
```

- **Log out** revokes the family. `DELETE /v1/me/sessions/{id}` revokes one chosen family.

### 2.3 Organizations, roles and invitations

**Permission model** (`dev.kairos.domain.identity.PermissionMatrix`):

| Permission | Roles that have it |
|---|---|
| `org:read`, `jobs:read`, `runs:read`, `endpoints:read`, `workflows:read`, `dlq:read`, `stats:read` | OWNER, ADMIN, DEVELOPER, OPERATOR, VIEWER |
| `runs:operate` (run-now, pause, resume, cancel), `dlq:manage` | OWNER, ADMIN, DEVELOPER, OPERATOR |
| `endpoints:write`, `jobs:write`, `workflows:write` | OWNER, ADMIN, DEVELOPER |
| `secrets:manage`, `egress:manage`, `members:manage`, `keys:manage`, `audit:read` | OWNER, ADMIN |
| `org:admin` (rename, transfer ownership, delete) | OWNER |

- **Per-request authorization** (`OrgAccessEvaluator`, used from `@PreAuthorize("@org.can(#orgId, 'jobs:write')")`):
  1. Resolve the principal.
  2. For a **user**, load the membership `(orgId, userId)` from a Caffeine cache with a 30s TTL. The cache is invalidated on role changes in the same pod; other pods catch up within the TTL.
  3. For an **API key**, require `key.tenantId == orgId` and the scope.
  4. Require the organization to be `ACTIVE`; suspended organizations are read-only.
  5. On any mismatch, respond `404`, so outsiders cannot probe whether an organization exists.
- **Ownership safeguards.** Role changes and member removals run in a transaction that locks the organization's memberships (`SELECT … FOR UPDATE`). They are refused if the organization would be left without an `OWNER`. Admins cannot grant `OWNER` or modify an owner.
- **Invitations.**
  - Creating one generates a 32-byte token (stored as SHA-256) with a 7-day expiry, and emails a link.
  - `POST /v1/invitations/accept {token}` requires a logged-in user whose **verified** email equals the invitation email. In one transaction it inserts the membership and marks the invitation `ACCEPTED`.
- **Creating an organization.**
  - `POST /v1/orgs` inserts the tenant (with default quotas from configuration) and the creator's `OWNER` membership in one transaction.
  - It also creates the organization's Tink keysets (`SECRETS`, `OUTPUTS`, `SIGNING`).
  - A user can create at most `kairos.org.max-per-user` organizations (default 10).

### 2.4 API keys

```
Key text:   sk_{env}_{keyId}_{secret}
            env    ∈ {dev, test, prod}               (fixed per deployment, checked)
            keyId  = 16 chars [A-Za-z0-9]            (SecureRandom)
            secret = 43 chars base62 = 256 bits      (SecureRandom)
Stored:     secret_hash = HMAC-SHA256(pepper[pepper_version], secret)   -- 32 bytes
```

- Created by an OWNER or ADMIN, through `POST /v1/orgs/{orgId}/api-keys`. The requested scopes must be a subset of the creator's permissions.
- The plaintext is returned once, with `Cache-Control: no-store`.
- The pepper (32 random bytes, versioned for rotation) comes from Secret Manager, or from the generated `.env` locally. It is never logged.
- **Verification:**
  1. Check the exact `Bearer sk_…` shape with a fixed-field split (no regex).
  2. Load the row from a 60s cache (negative results cached for 10s).
  3. Check revocation and expiry.
  4. Compare `HMAC(pepper[version], secret)` with `MessageDigest.isEqual`, which is constant time.
  5. Build `ApiKeyAuthentication(tenantId, keyId, scopes)`.
  6. Record `last_used_at` in a batch update.

### 2.5 Request authentication pipeline

`BearerAuthenticationFilter` (a `OncePerRequestFilter`) reads `Authorization: Bearer <token>`:
- a token starting with `sk_` is an **API key** (§2.4);
- any other token is validated as a **JWT** (§2.2).

The principal is `UserPrincipal(userId, sessionId, platformAdmin)` or `ApiKeyPrincipal(tenantId, keyId, scopes)`. Routes are restricted as follows:

| Route group | Who can call it |
|---|---|
| `/v1/auth/**` | Unauthenticated (rate-limited) |
| `/v1/me/**`, `POST /v1/orgs`, `/v1/invitations/accept` | Users only |
| `/v1/orgs/{orgId}/**` | Users or API keys, through `OrgAccessEvaluator` |
| `/v1/admin/**` | Users with `platform_role = PLATFORM_ADMIN` |

**Spring Security configuration:**

| Setting | Value |
|---|---|
| Sessions | `STATELESS`; no cookies are issued |
| CSRF | Disabled. Credentials travel only in the `Authorization` header and never in cookies, so a browser cannot attach them cross-site. A future browser console must switch to `Secure; HttpOnly; SameSite=Strict` cookies and add CSRF tokens. |
| Authorization | Health endpoints only on the management port; route rules as above; method security with `OrgAccessEvaluator` |
| Security headers | `Strict-Transport-Security: max-age=31536000; includeSubDomains`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` |
| CORS | Disabled |

**Object-level authorization.** Every service method takes the `TenantId` from the authorized path variable after `OrgAccessEvaluator` has approved it. Every repository query includes `tenant_id = :tenant`, which ArchUnit enforces. Cross-organization ids return `404`.

### 2.6 Platform administration

- **Bootstrap.** At first start, `kairos-api` creates the bootstrap administrator from `KAIROS_BOOTSTRAP_ADMIN_EMAIL` and a one-time password hash, both read from Secret Manager. The account must change its password on first login. Later administrators are promoted by an existing administrator, and every promotion is audited.
- **Responsibilities.** Administrators approve or reject `SUFFIX` and `CIDR` egress rules, set quotas, suspend or reactivate organizations, and disable users. Suspending an organization sets every `schedules.status` in its cells to `PAUSED`, through a fan-out to the cells.

## 3. Rate limiting (fail closed)

| Bucket | Key | Default limit | Applied |
|---|---|---|---|
| Per IP, before authentication | `ip:{client IP}` | 50 requests/s; 20 failed authentications per minute | Before token or key verification |
| Sign up | `signup:ip:{ip}` | 5 per hour | `POST /v1/auth/signup` |
| Log in | `login:ip:{ip}` and `login:email:{sha256(email)}` | 20/min per IP; 10 per 15 min per email | `POST /v1/auth/login` (in addition to account lockout) |
| Email-sending endpoints | `mail:email:{sha256(email)}` | 3 per hour | Resend verification, forgot password, invitations |
| Per API key and route class | `key:{keyId}:{read\|write\|redrive}` | read 500/s, write 200/s, redrive 5/s | After authentication |
| Per organization | `tenant:{id}` | from `tenants.quotas` | After authentication |

- **Implementation.** Bucket4j with its Lettuce-based Redis proxy manager (compare-and-swap), keys `kairos:rl:{bucket}` with expiry. Using Redis keeps rate limiting off Postgres.
- **Client IP.** Taken from the gateway's `X-Forwarded-For` header, but only when the request comes from a trusted proxy CIDR (configured). Otherwise the socket address is used.
- **If the limiter store is unreachable,** the request gets `503`. It fails closed.
- `429` responses carry `Retry-After`.

## 4. Egress guard: SSRF defence for internal targets (HLD §16)

**Egress rules lifecycle** (`egress_rules`, managed by OWNER and ADMIN):

| Rule type | On creation | Becomes `ACTIVE` when |
|---|---|---|
| `HOST` | `PENDING_VERIFICATION`, with a random `verification_token` returned to the caller | `POST …/egress-rules/{id}/verify` makes `kairos-watcher`'s `EgressVerifier` fetch `https://{host}:{port}/.well-known/kairos-verification` through the egress guard (deny list applied, no redirects, 2 KB limit, 5s timeout). The body must equal the token (constant-time comparison). |
| `SUFFIX`, `CIDR` | `PENDING_APPROVAL` | A platform administrator approves it (`POST /v1/admin/egress-rules/{id}/approve`), with a mandatory note |

- `REJECTED` and `REVOKED` rules are terminal. Revoking a rule is immediate: the executor's `EgressRuleCatalog` refreshes every 5s, and the next call is blocked as `POLICY_BLOCKED`.
- A suffix must have at least three labels (for example `*.payments.svc.cluster.local`, never `*.local`). A CIDR must be within `kairos.egress.internal-cidrs` and no wider than `/16`.

**At save time** (`EgressPolicy.check(urlTemplate, activeRules)`):

| Check | Rule |
|---|---|
| Scheme | `https` |
| Port | Must be in the matching rule's `ports` |
| URL shape | No userinfo, no fragment. The host is a literal: a DNS name or an IP literal, never a template. |
| Host | Must match an **ACTIVE** `HOST` rule (exact) or `SUFFIX` rule (label-boundary suffix match), or, for an IP literal, an ACTIVE `CIDR` rule |
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
    allow if host matched an ACTIVE HOST/SUFFIX rule and ip in kairos.egress.internal-cidrs
    allow if ip in an ACTIVE CIDR rule of the organization
    otherwise deny
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
| Password storage | Argon2id (64 MiB, t = 3), per-user salt, rehash on parameter change (§2.1) |
| Bearer secrets hashed, constant-time comparison | API keys (HMAC with pepper), refresh tokens, verification, reset and invitation tokens (SHA-256); `MessageDigest.isEqual` (§2) |
| Session management | Short-lived access tokens; refresh rotation with reuse detection; revoke on logout, password change and reset (§2.2) |
| Account enumeration and brute force | Uniform signup and forgot-password responses; dummy-hash timing on unknown emails; rate limits and progressive lockout (§2.1, §3) |
| CSPRNG | `SecureRandom` for keys and secrets. Tink does its own nonces. Backoff jitter uses `RandomGenerator` on purpose, because it is not a security value. |
| No hardcoded secrets | §7, `gitleaks` in CI, `.env` and dev keys gitignored |
| AuthN and object-level AuthZ on every request | §2.5 filter, `OrgAccessEvaluator`, role permissions and key scopes, organization-scoped repositories, `404` across organizations |
| Rate limiting (distributed, fail closed) | §3 |
| Security headers; generic errors; redacted logs | §2 configuration; RFC 9457 advice; `Redactor` (part 04 §3.3); logging allow-list (part 06) |
| CSRF and cookies | Not applicable to a header-only API; the rules for a future browser console are fixed in §2.5 |
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
