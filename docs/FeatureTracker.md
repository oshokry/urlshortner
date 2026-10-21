# Feature Tracker

Keep this file as the running history of feature additions. Keep the most recent updates at the top of both the index and the detailed entries. Add a numbered entry for each feature; retain earlier entries and update their validation/status when necessary. Each entry should capture the request, affected components, behavior/UI, implementation decisions, tests and known limitations.

| ID | Feature | Type | Implementation status |
|---|---|---|---|
| F006 | Visit saved links | Frontend enhancement | Implemented; build and browser check passed |
| F005 | Saved links beside analytics | Frontend layout | Implemented; desktop/mobile layout checks passed |
| F004 | List saved links | Brownfield enhancement | Implemented; 80 tests passed locally |
| F003 | Separate React frontend | Architecture refactor | Implemented; 65 tests passed locally |
| F002 | Extend expiry | Brownfield enhancement | Implemented; 52 tests passed locally |
| F001 | Disable a short link | Brownfield enhancement | Implemented; 39 tests passed locally |

## F006 — Visit saved links

**Latest update — 2026-10-21:** Renamed the hyperlink text from Visit to Link. The Visit column heading and navigation behavior are unchanged.

**Added:** 2026-10-21  
**Request:** Add a Visit column to Saved links, linking to the shortened URL and opening separately.  
**Status:** Implemented. Frontend-only; no Java, API or persistence changes.

### Behavior and implementation

- Added a **Visit** column with a **Link** hyperlink for every saved link.
- Uses the API's `shortUrl`, so navigation goes through the Java redirect endpoint and its existing analytics, expiry and disabled-link checks.
- Opens in a new browser tab/window via `target="_blank"` with `rel="noopener noreferrer"`. The browser controls whether a new tab or window is used; the shortener page stays open.
- Active links redirect and count a visit. Expired or disabled links return the existing 410 response. Refresh the list to see updated counts.
- Updated `frontend/src/LinkList.jsx` and README instructions.

### Validation and rollout

The frontend production build passed. A headless Chromium check with stubbed API/short-URL responses verified the exact shortened-URL target, separate tab, preserved original page, null window opener and no page overflow at 390px. Backend behavior was unchanged and its suite was not rerun. Run `git pull` and refresh the browser with the frontend development server running. No Java restart is needed; built deployments must rebuild their frontend assets.

## F005 — Saved links beside analytics

**Added:** 2026-10-21  
**Request:** Move Saved links to the right of Link analytics.  
**Status:** Implemented and pushed. Frontend layout change only; no Java, API or persistence changes.

### Behavior and affected components

- Desktop panels appear left to right: **Create a short link → Link analytics → Saved links**.
- At widths of 1100px or less, the panels stack vertically. Wide tables scroll inside Saved links without overflowing the page.
- `frontend/src/App.jsx` places all three panels in one grid. `frontend/src/styles.css` widens the page and defines responsive columns.
- Updated positional text in `LinkList.jsx`, its matching test expectation, and the README.

### Validation and rollout

The frontend production build passed. Headless browser layout checks passed at **1800px, 1280px, 1000px and 390px**, including a populated table with a long destination URL. Checks confirmed that Saved links sits to the right of analytics on desktop, stacks below on smaller screens, and causes no page overflow. The full backend suite was not rerun for this frontend-only change.

Run `git pull` and refresh the browser while the frontend development server is running. No Java restart is needed. For a built deployment, rebuild and redeploy the frontend assets.

## F004 — List saved links

**Added:** 2026-10-21  
**Request:** Implement scenario 3, List links, with Java backend and React UI. Preserve the small-change budget and keep this tracker newest first.  
**UI:** **Saved links** table with **Refresh links**. Displays code, destination, clicks, expiry and status. Selecting a code fetches fresh metadata into the analytics panel, including when reselecting the same code.  
**API:** `GET /api/links` returns an array of existing Link metadata objects; HTTP 200 and `[]` for an empty store.  
**Status:** Implemented and locally validated. See Git history for publication details.

### Behavior and design

- Return every saved link, including active, expired, disabled and never-expiring entries. Sort by creation timestamp descending, then code ascending for deterministic ties.
- Take an immutable snapshot under the store lock. Reads do not append journal events or increment click counts. Existing data and API operations retain their behavior; no journal migration is needed.
- Load the table on demand. Refresh explicitly after creates, redirects, expiry changes or disable operations; there is no polling. Clear old rows during refresh, show loading/empty/error states and allow retry. Selection errors preserve the table and current analytics. Pending list requests disable duplicate actions.
- Reuse the existing metadata serializer and form/API client. GET listing bypasses the creation rate limiter; unsupported collection methods advertise `Allow: GET, POST`.
- This prototype returns the entire inventory without filtering, pagination or authentication. Sorting holds the store lock and costs O(n log n); response size grows with saved links. Add owner checks and bounded pagination before a large or public deployment.

### Affected components and verification

| Component | Change | Verification |
|---|---|---|
| `LinkStore`, `JournalLinkStore`, `ShortenerService` | Immutable, ordered list snapshot | Empty state, deterministic ordering, all states, no side effects, concurrent writes, replay |
| `ShortenerServer`, OpenAPI | GET collection response using existing Link JSON | HTTP shape/order/headers, empty array, method contract, creation limiter independence |
| React `LinkList`, `App`, API client and CSS | Responsive table, refresh and fresh selection into analytics | React interaction/error tests; live browser flow and mobile width |

### Validation and size

**70 changed production-code lines: 66 added + 4 removed across eight Java/React/CSS files. Including 12 OpenAPI lines, the total is 82.** Tests and prose documentation are excluded. Java compilation treats warnings as errors. **60 Java tests, 17 React tests and 3 headless browser integration tests pass (80 total)**; frontend production build passes. The new browser scenario verifies listing, fresh selection, disable, refresh, page reload, retained counts and mobile overflow.

The browser tests use the real Java server through the built frontend proxy. Test journals now have unique names for repeatable runs. Local execution is Linux; Windows execution and remote CI success are not implied. Historical entries below retain their original validation counts.

### Demo

Create a link → **Refresh links** → select its code → extend or disable it → **Refresh links** again. Reload the page and load the list again. Restart Java and repeat to verify the journal-backed inventory remains available.

## F003 — Separate React frontend

**Added:** 2026-10-21  
**Request:** Keep Java for the backend and move the hardcoded UI into React. This architecture refactor is broader than the earlier 100-line feature exercises.  
**Status:** Implemented. The newest update stays first in this tracker; F001/F002 below retain their original implementation evidence.

### Components and behavior

- Removed `Ui.java` and the Java routes that served embedded HTML, JavaScript and CSS. Java now serves only API, redirects and health. No storage format or API contract changed; existing journal data is compatible.
- Added `frontend/` with React components for creation, analytics and link management, a small API client, independent CSS, Vite configuration and a pinned npm lockfile. Frontend edits no longer require Java compilation.
- Preserved create, copy, refresh, click analytics, expiry extension, permanent disable and confirmation. Editing a lookup code clears its displayed data and actions. Pending requests lock lookup controls; uncertain mutations require an explicit refresh before retrying.
- Browser traffic stays on the frontend origin through the local API proxy. No Java CORS relaxation or new authentication scheme was added.
- Added Windows/Linux frontend scripts, build/preview commands, React tests and browser integration tests. CI builds/tests both layers; Linux also runs Chromium integration tests.

### Run and deployment

Start Java with `scripts/run.ps1` and React in another terminal with `scripts/frontend.ps1` (or the `.sh` equivalents). Open **http://localhost:5173**. Port 8080 is API-only. Node.js 24.15+ LTS and JDK 17+ are required for the documented workflow. The frontend script uses `npm ci` on first run; reinstall after dependency changes.

`npm --prefix frontend run build` produces `frontend/dist`. Deploy those static files behind a web server that proxies `/api`, `/r` and `/health` to Java. Set `BASE_URL` to the public origin and apply the documented CSP at that web server. Vite dev/preview are local tools, not the production server.

### Validation

- 52 Java tests pass, including persistence, concurrency and all previous feature checks. The former UI route test now asserts Java returns 404 for `/`, `/app.js` and `/style.css`.
- 11 React component tests pass: creation/form fields, copy, conflict/error messages, expired-link renewal, uncertain-request handling, confirmation/cancel, disable retention, non-expiring/disabled restrictions, lookup changes, pending controls and missing-backend errors.
- Frontend production build and 2 headless Chromium integration tests pass against the real Java API through the frontend proxy, including redirects, expiry, confirmation/cancel, disable, reload, conflicts, native alias validation and a mobile-width overflow check. Total: **65 passing checks**. The standard Playwright browser download was unavailable in this environment; local execution used an npm-packaged Chromium binary with a temporary test configuration. CI uses Playwright’s standard Chromium.
- Windows scripts and CI are configured; local execution was on Linux. Remote CI results are separate evidence.

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
