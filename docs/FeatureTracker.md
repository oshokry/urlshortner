# Feature Tracker

Keep this file as the running history of feature additions. Keep the most recent updates at the top of both the index and the detailed entries. Add a numbered entry for each feature; retain earlier entries and update their validation/status when necessary. Each entry should capture the request, affected components, behavior/UI, implementation decisions, tests and known limitations.

| ID | Feature | Type | Implementation status |
|---|---|---|---|
| F002 | Extend expiry | Brownfield enhancement | Implemented; 52 tests passed locally |
| F001 | Disable a short link | Brownfield enhancement | Implemented; 39 tests passed locally |

## F002 — Extend expiry

**Added:** 2026-10-21  
**UI:** **Extend expiry by** selector and button in Link analytics: 1 minute, 1 hour, 1 day or 1 week. The updated timestamp appears immediately. The action is disabled for disabled/non-expiring links and until a newly entered lookup code is refreshed.  
**API:** `PATCH /api/links/{code}` with form field `ttlSeconds`; returns updated metadata with HTTP 200.  
**Status:** Implemented and locally tested. See Git history for publication details.

### Request and acceptance criteria

Extend an existing link without changing its code, destination or analytics, within 100 changed production-code lines excluding tests. Persist the new expiry across restarts. Active links gain time from their current expiry; expired links gain time from now. Disabled links remain disabled. Non-expiring links are unchanged.

### Existing code and implementation sequence

| Step | Affected components | Acceptance/validation |
|---|---|---|
| 1 | Immutable Link and LinkStore contract | Preserve code, destination, creation time and analytics |
| 2 | Synchronized journal store and replay | Persist E event before publishing; replay extensions; serialize concurrent additions |
| 3 | Service and HTTP adapter | Validate 1–31,536,000 additional seconds; bound body; accept only ttlSeconds |
| 4 | Existing analytics UI | Duration selector, pending state, refreshed expiry, disabled controls for ineligible links |
| 5 | Tests and API/README documentation | Regression, boundary, concurrency, persistence and failure checks |

### Demo

Create a link with a 1-minute lifetime. In Link analytics, select **1 hour** and click **Extend expiry**. Its existing expiry moves forward one hour while its short link and clicks stay unchanged. Restart and look it up again. An expired link can be renewed in the same way; a permanently disabled link cannot.

```powershell
curl.exe -i -X PATCH http://localhost:8080/api/links/my-demo --data-urlencode "ttlSeconds=3600"
```

Success returns 200 and the updated Link JSON. Invalid/missing fields return 400, missing codes 404, non-expiring links 409, disabled links 410, oversized bodies 413, unsupported media types 415, and storage failure 503.

### Decisions and compatibility

- Reuse the existing lock and append-only journal; add `E<TAB>code<TAB>newExpiry<LF>`. Disk force precedes the visible state change. Failed writes leave the old expiry intact.
- The new expiry is `max(currentExpiry, requestTime) + ttlSeconds`. This cannot shorten the lifetime. The maximum applies to each addition, not the total lifetime.
- Requests are additive, not idempotent. A repeated successful PATCH extends again. If the response is lost, inspect the expiry before retrying. The UI does not retry automatically and requires Refresh after an error.
- Earlier C/V/D journals remain readable. An older binary cannot replay E records; roll forward with fixes rather than downgrading against an updated journal.
- No ownership/authentication is added to this local demo. Public deployment requires authorization for expiry changes.

### Validation and AI assistance

AI inspected the existing expiry checks, immutable metadata, journal replay, HTTP validation and UI selection flow; reused those boundaries; generated and ran the regression tests; and measured the diff against F001.

**60 changed production-code lines: 56 added + 4 removed across six Java files. Including 27 OpenAPI lines, the total is 87.** Tests and prose documentation are excluded. Compilation passes with warnings treated as errors. **52 Java tests pass locally**, including 13 new extension tests covering exact expiry boundaries, active/expired links, duration bounds, disabled/never-expiring rejection, concurrent additions, replay, failed writes and HTTP validation. Graphical browser and Windows execution are not claimed for this change.

## F001 — Disable a short link

**Added:** 2026-10-21  
**UI:** Red **Disable <code>** button in Link analytics, code-specific confirmation, visible Disabled status, and retained analytics. The button is unavailable until a link is loaded and after it is disabled. Changing the lookup code requires refreshing before disabling.  
**API:** `DELETE /api/links/{code}`; repeat requests return 204.  
**Status:** Implemented and locally tested. See Git history for publication details.

### Request and acceptance criteria

Extend the working URL shortener with persistent deactivation, changing fewer than 100 production-code lines (tests excluded). Disable a link without deleting its metadata or analytics. Later GET and HEAD redirects return 410, without incrementing clicks. The disabled state survives restart, repeated disable requests succeed, and the original alias cannot be reassigned.

### Existing code and impact

The existing flow is HTTP adapter → service → synchronized journal store → immutable link snapshot. GET records visits in the store; HEAD checks metadata in the adapter. Both paths must reject disabled links. Startup replays journal events, so a memory-only flag would be insufficient. The UI reads metadata to display status.

| Step | Change | Validation |
|---|---|---|
| 1 | Add immutable disabled flag, default false for existing creation/replay | Existing 27 tests remain green |
| 2 | Add synchronized disable operation and durable `D` event | Restart, failed-write, idempotency and concurrency checks |
| 3 | Add DELETE endpoint and enforce disabled status on GET and HEAD | Real HTTP 204/410/404/405 checks |
| 4 | Show disabled status and a confirmation button in the UI | Java compilation; browser interaction still needs manual rehearsal |
| 5 | Update API contract and review evidence | Schema and change-count checks |

### Contract and demonstration

```powershell
curl.exe -i -X DELETE http://localhost:8080/api/links/interview
curl.exe -i http://localhost:8080/r/interview
curl.exe http://localhost:8080/api/links/interview
```

Expected: 204 with an empty body; 410; then metadata containing `"disabled":true` and the original click count. Repeating DELETE returns 204. Unknown or malformed codes return 404. Alternatively, look up a link in the UI and select **Disable this link**, then confirm the code shown. Restart and repeat the lookup to demonstrate persistence.

### Decisions and risks

- Add `D<TAB>code<LF>` to the existing journal, force it to disk, then publish the disabled snapshot. An append failure leaves the visible state unchanged.
- Keep expiry separate from deactivation. The response adds a `disabled` boolean; existing links default to false. Retain analytics and the alias instead of removing the map entry.
- Serialize disable and visit under the existing store monitor. Visits accepted before disable may finish their HTTP response afterward; requests resolved after disable cannot redirect or add clicks.
- Repeated disable requests do not append redundant events. No reactivation or bulk operation is included.
- Old journals remain readable by the new version. **Old binaries cannot replay the new D events.** Do not roll back the binary against a journal containing deactivations; preserve the data and roll forward with a fix.
- No authentication was added to this localhost prototype. A public service must authorize deactivation against link ownership. Deactivation is not data erasure.

### AI assistance and validation evidence

AI inspected the existing redirect, HEAD, persistence and analytics paths; implemented the change; added tests; and measured the diff against the pre-change local source. The implementation uses the existing storage boundary and locking model. It deliberately avoids a new service, database migration, or separate status registry.

**Production diff: 61 added + 16 removed = 77 changed lines across six Java files**, including the embedded UI. This conservative count includes replacements twice and includes blank/comment lines. Tests and Markdown/OpenAPI documentation are excluded from the production-code count.

**39 tests passed locally on Java 17**, including 12 new checks for deactivation. Compilation passed with all warnings treated as errors. Windows and graphical browser validation are not claimed for this change. Engineer review and production sign-off are separate from these automated checks.
