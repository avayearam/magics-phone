# Magic Phone

Web-first Universal Audio Bridge for Windows — Magics.

## Status: v0.3.0 — experimental routing foundation

Native Windows x64 WASAPI **endpoint-to-endpoint** routing engine has been coded, with two route slots, explicit start/stop control, read-only discovery, API protections, and a Persian local web laboratory. Audio transfer is **disabled by default** and **not Windows-runtime verified**. No final installer has been released.

The source routing supports an input endpoint or the **whole mix** of a speaker output endpoint as the source and another output endpoint as the destination. This is not yet per-application audio capture, automatic microphone selection inside another app, or a tested two-way app bridge.

## Layout

- `app/main.go` — loopback-only web service on `127.0.0.1:18764`.
- `app/discovery_windows.go` — Windows x64 audio endpoint and session enumeration.
- `app/routing.go`, `app/routing_test.go` — route supervisor, input verification, local request guards and tests.
- `app/routing_windows.go` — experimental Windows WASAPI shared-mode transfer (strict matching formats, bounded queue).
- `app/routing_other.go` — unsupported platform implementation.
- `app/web/index.html` — Persian RTL UI with explicit device scan and route controls.
- `app/web/discovery.test.cjs` — simulated browser behavior checks.
- `docs/ROUTING_PHASE3_FA.md` — full Persian scope and testing disclosure.
- `history/` — retained legacy prototypes. They are **not** used by the new audio runtime.

## Developer-only tests

Requires Go 1.23+ and Node.js:

```sh
cd app
go test -race -v ./...
node web/discovery.test.cjs
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -o magic-phone-dev.exe .
```

Runtime testing should be done only in a controlled Windows development environment. The environment variable `MAGIC_PHONE_EXPERIMENTAL_AUDIO=1` activates experimental routing endpoints; the default remains disabled. This is not a supported end-user installation procedure.

## Security

No Python, encoded PowerShell, third-party driver download, hidden installer, or Magic Core credentials in this version. The localhost control endpoints check origin and a per-process random token, reject cross-site changes, and require a manual UI start. The audio itself is not uploaded anywhere.

Do not upload contacts, secrets, runtime settings, or previously blocked v1.1 installer binaries. No Windows execution, live routing, driver signing, Defender scan, deployment or production verification is claimed.

**Magic Core integration is explicitly deferred.**
