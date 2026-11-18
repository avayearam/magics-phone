# Magic Phone

Web-first Universal Audio Bridge for Windows — Magics.

Current version: **0.2.0 development preview**. The web panel and read-only Windows x64 Core Audio device/session discovery are implemented; Windows runtime verification and audio routing remain open. The app is not an end-user installer.

## Repository

- `app/web/index.html`: Persian RTL web panel with dark/light themes, local route profiles, browser audio-device list and agent health check.
- `app/main.go`: minimal local-only Go development agent, serving the embedded web panel and read-only status.
- `app/discovery_windows.go`: read-only native Windows x64 Core Audio inventory (active endpoints and available audio sessions).
- `app/discovery.go`, `app/discovery_other.go`: read-only API and explicit unsupported-platform response.
- `app/main_test.go`, `app/discovery_test.go`, `app/web/discovery.test.cjs`: HTTP security and simulated UI regression tests.
- `docs/WEB_FIRST_FA.md`: architecture, validation, release gates and next steps.
- `history/2026-11-18/`: historical Phone Link and Chrome pilot, not the new application runtime.

## Development

Go 1.23+; from `app/` run `go test ./...` then `go run .` for a local developer preview at http://127.0.0.1:18764/.

No external dependencies, audio capture, driver installation, cloud connection, or remote command execution in this version. Windows discovery is explicit and local; app sessions may be incomplete and do not imply that a program can be routed. Run `node web/discovery.test.cjs` from `app/` for the simulated UI test. The routing button is intentionally disabled.

The future website is planned at phone.magics.site. Public-web to local-agent access requires a separate permission and pairing design. The final Windows installer, signed virtual audio driver, and real two-way desktop audio routing are not yet delivered.

See [Persian architecture and validation](docs/WEB_FIRST_FA.md). Historical sources remain untouched. Do not commit local contact lists, secrets, pairing data or logs; do not use the previously flagged v1.1 installer.
