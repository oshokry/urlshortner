# Greenfield engineering summary

## Normalized requirements and assumptions

The supplied brief asks for a URL shortener with APIs, analytics, reliability and an AI-assisted engineering process. The current request narrows implementation to the greenfield scenario under interview time pressure. The brief does not specify traffic, persistence technology, alias ownership, link lifetime or the analytics definition.

| Ambiguity | Prototype decision | Validation |
|---|---|---|
| What is a click? | A persisted GET redirect attempt; bots/retries count; HEAD does not | HTTP and concurrent click tests |
| Do repeated destinations share a code? | No; create produces a new link unless a custom alias conflicts | Creation and collision tests |
| How long do links live? | Forever by default; optional integer TTL 1–31,536,000 seconds | Boundary/invalid TTL tests |
| Can expired aliases be reused? | No; reserve them to avoid redirecting an old shared link to a new owner | Expired-alias conflict test |
| Who can see analytics? | Anyone with the code in this local demo | Explicit documented limitation; add ownership before public deployment |
| Is a database required? | No; single-process local journal for portable demo | Reopen, lock and recovery tests |
| What are scale/SLO targets? | Unspecified; no production throughput or availability claim | Requires requirements and benchmark phase |

## Decomposition and dependency order

| Step | Intent and constraints | Acceptance criterion | Depends on |
|---|---|---|---|
| 1 | Normalize semantics and scope | Explicit assumptions and exclusions | Brief |
| 2 | Immutable model + storage boundary | Atomic creation and visit operations | 1 |
| 3 | Persistent implementation | Restart preserves state; conflicting writers rejected | 2 |
| 4 | Business rules | Safe HTTP(S) URLs; bounded collisions; expiry boundary | 2–3 |
| 5 | HTTP contract | Correct statuses/headers; bounded input; JSON responses | 4 |
| 6 | Browser demo | Create, open, lookup and refresh analytics | 5 |
| 7 | Quality gates | Compile without warnings; unit/concurrency/HTTP/recovery tests | 3–6 |
| 8 | Review package | Setup, API schema, trade-offs, AI record, demo script | 1–7 |

## State and control flow

`Link(code, url, createdAt, expiresAt, clicks, lastClickedAt)` is immutable. Codes are case-sensitive. UTC timestamps use `Instant`. Eight random base62 characters offer ~218 trillion values; uniqueness comes from the atomic insert, not probability alone.

Create: validate input → choose supplied/random code → synchronized uniqueness check → append creation event → force journal → publish immutable link → 201. Random collisions retry, custom collisions return 409.

Redirect: synchronized lookup → check expiry against supplied time → append visit event → force journal → replace immutable snapshot → 302 with no-store. The link's destination never changes. Missing links return 404; expiry at or before now returns 410. Metadata stays available after expiry.

## Storage format

UTF-8, one tab-delimited event per line, always terminated with LF:

```text
C<TAB>code<TAB>base64url(destination)<TAB>createdAt<TAB>expiresAt-or-empty<LF>
V<TAB>code<TAB>visitedAt<LF>
```

Base64 encoding prevents URL delimiters from corrupting the event format. Startup acquires an exclusive process lock, truncates only an incomplete final line, then replays complete records. Complete malformed records fail startup. Writes are forced before in-memory publication. An I/O error poisons the writer so another write cannot append after a partial record. Keep a backup before manually repairing a corrupted journal.

This is not a general-purpose transactional database: no checksums for detecting every silent corruption, version migrations, compaction, backup tooling or multi-node protocol. Replay and memory grow with data; disk use grows with clicks. A crash after durable append but before response may persist an operation the client did not observe. Initial file/directory durability also depends on the OS/filesystem; this prototype is not a power-loss certification.

## Safety and reliability choices

- Localhost binding by default; no secrets required; no outbound destination fetches.
- Allow HTTP(S) with a parsed host; reject credentials, control characters, encoded CR/LF, bad ports and excessively long URLs.
- Small form payloads; duplicate and unknown fields rejected. JSON responses use centralized string escaping. UI renders API content with `textContent`.
- Same-origin assets and a restrictive CSP; no inline script execution. No CORS opt-in.
- 30-token global creation burst, refilling 1/second. This is a demo guard, not per-user fairness or distributed abuse control. Invalid create attempts consume tokens too.
- Eight worker threads and bounded queue (128); caller-runs backpressure. A reverse proxy with connection/body/header timeouts is still needed against slow clients; queue bounds alone do not solve denial of service.
- Logs contain status and elapsed duration, not URLs or payloads; stored destinations themselves may contain sensitive query strings.
- `/health` is process liveness. A production readiness endpoint should account for writable storage; current health may stay up during a storage fault.
- Redirects wait for durable analytics. On disk failure this reduces redirect availability. Async analytics is a deliberate future trade-off, not silently best-effort in this demo.

## Quality gates and observed results

- Java 17 compilation with `--release 17 -Xlint:all -Werror`: passed.
- 27 automated tests: passed. Includes genuine local HTTP requests, 24 concurrent alias creations (one winner), 100 concurrent visits (no lost updates), deterministic expiry boundaries, forced collision retries, persistence, partial-tail recovery and corrupt-record refusal.
- No external runtime/build libraries, so no third-party Java dependency inventory to resolve. This does **not** replace JDK/container patching or a security review.
- GitHub Actions runs the same suite on Java 17. Workflow execution must be checked after repository publication; local success is not evidence of a remote CI run.
- No production load test, automated vulnerability scan, Windows execution or disk/power-loss fault campaign is claimed. Concurrency tests verify correctness, not a throughput SLO.
- Browser assets and endpoints were verified over HTTP. A full graphical browser check could not run because the execution environment has no Chromium binary; manually rehearse the browser workflow before the interview.

## Production evolution, in priority order

1. Confirm traffic, latency, availability, privacy, retention, ownership and abuse requirements.
2. Adopt the team's framework and supported JSON/validation stack; identity, owner checks, TLS/reverse proxy, CSRF strategy for browser auth, quotas and URL-abuse controls.
3. Implement transactional database storage with unique code index; define idempotency for create retries; add schema migration, backups and restore drills.
4. Add real readiness, structured metrics/traces and alerts. Run load, disk failure, shutdown and recovery tests.
5. Cache redirect lookups if measured traffic needs it. Consider durable async analytics with documented eventual consistency and duplicate-handling semantics.

The subsequent [feature tracker](FeatureTracker.md) documents deactivation and expiry extension with their impact analysis, implementation and validation. The full ambiguous scenario remains separate assignment work; this does not claim to deliver all three scenarios.
