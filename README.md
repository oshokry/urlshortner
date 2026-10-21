# Shortly — Java URL shortener

A runnable **greenfield interview prototype**: create short links, redirect to their destinations, and inspect persistent click analytics. Java 17, standard library only. No Maven, database installation, API keys, or network access needed to build or run.

## Start here — Windows

Install/use a **JDK 17 or newer** and confirm `java -version`. From this folder in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\run.ps1
```

Open **http://localhost:8080**. Keep the terminal open; Ctrl+C stops the service.

The downloadable ZIP also includes a prebuilt executable JAR. With Java 17+ installed, you can skip compilation and run:

```powershell
java -jar build/urlshortner.jar
```

The repository contains source; the prebuilt JAR is a convenience in the downloadable package only.

On macOS/Linux (JDK 17+):

```bash
bash scripts/test.sh
bash scripts/run.sh
```

The scripts invoke the JDK compiler module directly and compile with `--release 17 -Xlint:all -Werror`. A JRE without the compiler module is insufficient. No preview features are used. Windows scripts are supplied; execution was validated on Linux with Java 17.

## Two-minute demo

1. Create `https://example.com` with alias `interview` and no expiry.
2. Open the short link, return to the app, and refresh analytics: **1 redirect**.
3. Create the same alias again: **409 Conflict**, preserving the original destination.
4. Create another link with **1 minute** expiry; after expiry it returns **410 Gone**.
5. Stop and restart the server; look up `interview`: its URL and click count remain.

Start with [the interview walkthrough](docs/INTERVIEW.md) for the explanation and likely questions.

## What works

- Random eight-character base62 codes; custom aliases; atomic uniqueness and bounded collision retry.
- Configurable expiry (1 second–365 days); expired aliases remain reserved.
- HTTP 302 redirects with no-store caching; HEAD checks do not count as clicks.
- Total redirects and last-click timestamp, retained after restart.
- Synchronized, disk-forced append journal; process file lock; incomplete-tail recovery.
- HTTP(S) URL validation, credential/newline rejection, body size limit, creation rate limit.
- Bounded worker queue, security headers, payload-free request logs, liveness endpoint.
- Browser UI, API/schema contract, regression tests, and GitHub Actions workflow.

## API

| Method | Endpoint | Behavior |
|---|---|---|
| POST | `/api/links` | Create; form-encoded body; returns 201 and JSON |
| GET | `/r/{code}` | Count and redirect, 302; 404 unknown; 410 expired |
| HEAD | `/r/{code}` | Resolve without counting |
| GET | `/api/links/{code}` | Metadata and analytics as JSON, including expired links |
| GET | `/health` | Process liveness; 200 `{"status":"up"}` |
| GET | `/` | Browser demo |

Creation intentionally uses the standard **`application/x-www-form-urlencoded`** format, avoiding a hand-written JSON parser. Responses are JSON. The full contract is in [docs/openapi.yaml](docs/openapi.yaml).

```bash
curl -i http://localhost:8080/api/links \
  --data-urlencode 'url=https://example.com/articles?id=42&ref=demo' \
  --data-urlencode 'alias=my-demo' \
  --data-urlencode 'ttlSeconds=3600'

curl -i http://localhost:8080/r/my-demo
curl http://localhost:8080/api/links/my-demo
```

Windows: use `curl.exe` and put each command on one line. Do not use `-L` when demonstrating the redirect response; it follows the destination.

```json
{
  "code": "my-demo",
  "shortUrl": "http://localhost:8080/r/my-demo",
  "url": "https://example.com/articles?id=42&ref=demo",
  "createdAt": "2026-10-21T12:00:00Z",
  "expiresAt": "2026-10-21T13:00:00Z",
  "clicks": 0,
  "lastClickedAt": null,
  "expired": false
}
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | Listening port |
| `HOST` | `127.0.0.1` | Local-only by default |
| `BASE_URL` | `http://localhost:<port>` | Public origin for returned links; not inferred from untrusted Host headers |
| `DATA_FILE` | `data/links.journal` | Persistent journal; ignored by Git |

PowerShell example: `$env:PORT='8081'` then run the script. Bash example: `PORT=8081 bash scripts/run.sh`.

## Architecture and evidence

```mermaid
flowchart TD
    A[Browser or API client] --> B[HTTP adapter]
    B --> C[ShortenerService]
    C --> D[LinkStore interface]
    D --> E[JournalLinkStore]
    E --> F[In-memory index]
    E --> G[Append-only disk journal]
```

| File | Responsibility |
|---|---|
| `ShortenerServer.java` | Routing, protocol, limits, JSON responses |
| `ShortenerService.java` | Validation, IDs, expiry and business rules |
| `LinkStore.java` | Atomic storage contract |
| `JournalLinkStore.java` | Durable events, replay and thread safety |
| `Link.java` | Immutable link snapshot |
| `Ui.java` | Small same-origin browser UI |

- [Engineering decisions, task decomposition and limitations](docs/ENGINEERING.md)
- [AI assistance record and engineer review checklist](docs/AI_ASSISTANCE.md)
- [Interview walkthrough](docs/INTERVIEW.md)
- [API contract](docs/openapi.yaml)

**Validation:** 27 automated tests passed on Java 17, including real HTTP requests, concurrent creation/click updates, expiry boundaries, persistence, crash-tail recovery, corrupt-journal refusal, collision exhaustion, validation, and throttling. Compilation treats warnings as errors. CI is configured but its remote run must be verified after publishing.

## Scope and limitations

This submission implements the requested **greenfield portion**, not the assignment's full three-scenario exercise. It demonstrates production-minded choices in a small prototype; it is **not ready for public production deployment**. No authentication, owner-level analytics authorization, abuse screening, distributed storage, TLS termination, journal compaction or deletion/retention policy is included. Data and analytics are visible to anyone who knows a code. Bind locally for the demo.

The journal serializes and syncs every write; this trades throughput for a clear durability model. Total redirects are not unique visitors and do not prove the destination page loaded. A crash after commit but before HTTP delivery can leave a committed operation without a received response. Health is liveness only, not disk readiness. Review [the documented next steps](docs/ENGINEERING.md) before treating this as a production design.
