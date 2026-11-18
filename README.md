# Magic Phone

Web-first Universal Audio Bridge for Windows — Magics.

Current version: **0.1.0 development preview**. The web panel and a read-only loopback agent are now implemented; Windows audio routing is not implemented. The app is not an end-user installer.

## Repository

- `app/web/index.html`: Persian RTL web panel with dark/light themes, local route profiles, browser audio-device list and agent health check.
- `app/main.go`: minimal local-only Go development agent, serving the embedded web panel and read-only status.
- `app/main_test.go`: HTTP security regression tests.
- `docs/WEB_FIRST_FA.md`: architecture, validation, release gates and next steps.
- `history/2026-11-18/`: historical Phone Link and Chrome pilot, not the new application runtime.

## Development

Go 1.23+; from `app/` run `go test ./...` then `go run .` for a local developer preview at http://127.0.0.1:18764/.

No external dependencies, audio capture, driver installation, cloud connection, or remote command execution in this version. The routing button is intentionally disabled.

The future website is planned at phone.magics.site. Public-web to local-agent access requires a separate permission and pairing design. The final Windows installer, signed virtual audio driver, and real two-way desktop audio routing are not yet delivered.

See [Persian architecture and validation](docs/WEB_FIRST_FA.md). Historical sources remain untouched. Do not commit local contact lists, secrets, pairing data or logs; do not use the previously flagged v1.1 installer.
