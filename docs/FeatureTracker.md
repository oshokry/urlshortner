# Feature Tracker

Keep this file as the running history of feature additions. Append a numbered entry for each feature; retain earlier entries and update their validation/status when necessary. Each entry should capture the request, affected components, behavior/UI, implementation decisions, tests and known limitations.

| ID | Feature | Type | Implementation status |
|---|---|---|---|
| F001 | Disable a short link | Brownfield enhancement | Implemented; 39 tests passed locally |

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
