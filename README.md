# Magic Phone

Web-first Universal Audio Bridge for Windows — Magics.

## Current development status: web UI v0.4.0 / local audio agent v0.3.0

The local web panel now offers **independent controls for both endpoint route slots A and B**, including explicit start/stop, current status, a stop-both control, local profile persistence and UI-level collision/feedback guards. It never automatically starts audio.

The native Windows x64 WASAPI engine remains **experimental and disabled by default**. Only device-to-device transfer is implemented: it is not per-application capture, automatic microphone selection inside other apps, or a verified two-way Chrome ↔ Phone Link bridge.

## Layout

- `app/main.go` — loopback-only web service on `127.0.0.1:18764`.
- `app/discovery_windows.go` — Windows x64 endpoint and audio session enumeration.
- `app/routing.go`, `app/routing_test.go` — supervisor, input validation, route control and tests.
- `app/routing_windows.go` — experimental Windows WASAPI shared-mode endpoint transfer.
- `app/routing_other.go` — unsupported platform.
- `app/web/index.html` — Persian RTL UI with independent two-slot controls and manual device scan.
- `app/web/discovery.test.cjs` — legacy v0.3 UI regression test, retained for historical comparison.
- `app/web/dual-route.test.cjs` — v0.4 two-slot UI simulation test.
- `docs/ROUTING_PHASE3_FA.md` — agent/audio engine scope.
- `docs/DUAL_ROUTE_UI_PHASE4_FA.md` — v0.4 UI scope and validation.
- `history/` — retained previous pilot prototypes; not the current runtime.

## Development-only checks

Requires Go 1.23+ and Node.js:

```sh
cd app
go test -race -v ./...
node web/dual-route.test.cjs
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -o magic-phone-dev.exe .
```

The `go test` and cross-compilation commands above describe developer checks for the whole project; **they were not rerun in the v0.4 UI-only change**. The v0.4 UI simulation and HTML structural checks passed. A real Chromium screenshot attempt timed out.

The environment variable `MAGIC_PHONE_EXPERIMENTAL_AUDIO=1` activates experimental routing only during developer testing. It is **not** a supported installation flow. No end-user EXE, signed driver, or Windows-tested installer has been published.

## Security

No Python, hidden PowerShell installer, third-party driver download, cloud connection, or Magic Core credentials are needed for the current agent. The local control endpoints verify origin, token and explicit UI action. Source audio is not uploaded anywhere. Do not commit personal contacts, pairing secrets or logs.

**Magic Core integration is deferred.**

## v0.5.0 — Windows discovery pilot

- Prevent retrying route slots or reusing render targets while a timed-out native audio worker is still alive.
- Allow cancelling failed-start workers without disguising their failure status.
- Open the local panel in the default browser after the Agent binds the loopback port (Windows ShellExecuteW; no PowerShell).
- Graceful Ctrl+C / SIGTERM shutdown attempts to stop both routing workers.
- Audio transfer still defaults to **off**. Portable Windows x64 diagnostic binaries are **unsigned and not yet Windows-runtime / Defender verified**; no official setup released.
- Independent source regression tests: `go test -race -count=1 -v ./...`; simulated UI test: `node web/dual-route.test.cjs`.

See [Persian v0.5 lab notes](docs/WINDOWS_LAB_V0.5_FA.md). Magic Core remains deferred.
