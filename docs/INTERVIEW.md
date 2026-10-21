# Interview walkthrough — five minutes

## Opening (30 seconds)

“I scoped this to the greenfield URL-shortener flow. The service creates short links, redirects, and records analytics, including across restarts. I chose Java 17 and a layered monolith so we can run it immediately and inspect the entire flow. AI accelerated the implementation and tests; I remain responsible for reviewing the behavior and production trade-offs.”

Only claim personal review for code and decisions you have actually reviewed.

## Show it (two minutes)

1. Run `scripts/test.ps1` on Windows or `bash scripts/test.sh` on macOS/Linux. Show **27 passing tests**.
2. Run the app and open `http://localhost:8080`.
3. Create `https://example.com` with alias `interview` and no expiry.
4. Open the generated link. Return and refresh analytics. Explain “redirect requests, not unique visitors.”
5. Try the alias again; show the conflict rather than overwriting a destination.
6. Stop/restart and look up the code to prove persistence.

If a code already exists from rehearsal, reuse its analytics or choose a fresh alias.

## Explain the design (one minute)

- **Separation:** HTTP adapter → business service → storage interface. Clock and code supplier can be injected for deterministic tests.
- **Uniqueness:** secure random base62 ID; insert is atomic; collisions retry up to ten times. A custom alias collision is a 409.
- **Reliability:** synchronized journal append + disk force before publishing state. Single-process lock prevents two writers; recovery discards an incomplete final record and refuses complete corrupt records.
- **Analytics:** count a successfully persisted GET redirect attempt. HEAD does not count. No 301 caching that silently bypasses analytics or expiry.

## Show AI discipline (one minute)

Open `docs/AI_ASSISTANCE.md`. The record identifies the actual user request, scoped implementation, validation, and choices intentionally left out. Explain that this is an AI-generated first implementation requiring your review; do not claim a historical manual implementation or approval that did not occur.

Useful example: the code uses form-encoded input rather than introducing an untested custom JSON parser. Tests explicitly cover collision exhaustion, exact expiry boundaries, concurrent updates and crash-tail recovery.

## Likely questions

| Question | Defensible answer |
|---|---|
| Why no Spring Boot? | For this one-hour prototype, the JDK-only approach removes build/network/setup dependencies. A team service would usually adopt its standard framework, serializer, validation and observability stack. |
| Why not Redis/Postgres now? | Avoid provisioning for the demo. The storage interface creates a migration boundary; production would use a transactional database with a unique code constraint. |
| Does it scale horizontally? | Not as implemented. The file lock intentionally prevents shared-file multi-process writers. Move to shared durable storage and define cache/analytics consistency before scaling out. |
| How would you scale reads? | Cache code-to-destination plus expiry, bound cache lifetime by expiry, and publish click events asynchronously. Address event durability, retry deduplication and analytics lag explicitly. |
| Are clicks exactly once? | No. They count committed GET attempts, including retries and bots. A committed click may precede a dropped redirect response. |
| What if the disk fails? | Creation or redirect cannot claim success; storage errors return 503 where possible. Writes stop after an I/O failure. This prioritizes durable counts over redirect availability. Production might decouple analytics so redirects remain available. |
| What does 8-character base62 give you? | 62^8 ≈ 218 trillion possible codes. Randomness reduces enumeration risk; uniqueness is still enforced on insertion. It is not access control. |
| How do you prevent malicious URLs? | Only HTTP(S), no embedded credentials or newline injection. We never fetch the destination, so this code path is not an SSRF fetcher. Phishing/open-redirect abuse still requires reputation checks, ownership and operational controls. |
| Why 302 instead of 301? | Links can expire, and a cached permanent redirect can bypass server-side analytics and policy. |
| What is missing? | Ownership/auth, abuse prevention, database operations, retention, metrics/traces, readiness, TLS/reverse proxy and load/failure testing. |

## Before the call

Review `ShortenerService.java`, `JournalLinkStore.java` and the limitations. Run the demo once on your own machine. Be ready to explain why this is a deliberately scoped prototype rather than a claim that all production requirements are complete.
