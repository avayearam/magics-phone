# Magic Phone

Universal Audio Bridge and Windows calling companion — Magics

Magic Phone is being developed as a Windows desktop product with two distinct parts:

1. **Phone Link command bridge (legacy, working pilot):** Chrome extension + local Windows bridge for explicit call commands. A local confirmation dialog is required before dialing.
2. **Native audio routing (current development):** a C# / WPF application exploring two separately selectable audio routes between Windows capture and render devices.

The project is intentionally split so that work on the new native audio product does not overwrite or destabilize the previously working Phone Link command pilot.

## Repository layout

```text
legacy/phone-link-bridge-v0.21/  # Chrome extension v0.8 + local bridge v0.21
src/desktop/                     # C# / WPF Audio Lab (development only)
installer/                       # WiX MSI definition (not release-ready)
docs/                            # Persian status, validation and security notes
```

## Current baseline

- Chrome extension: **0.8.0**
- Phone Link local bridge: **0.21**
- Native desktop Audio Lab: **2.0.0 development baseline**

The Phone Link pilot is retained as a separate legacy component because its dialing flow was confirmed by the user in live use. The new desktop Audio Lab is not yet a replacement for it.

## Build the desktop Audio Lab (developer machine only)

Requirements: Windows x64, .NET 8 SDK, network access to restore the pinned NuGet dependency, and WiX Toolset SDK 5.0.2.

```powershell
dotnet restore src/desktop/MagicPhone.csproj
dotnet publish src/desktop/MagicPhone.csproj -c Release -r win-x64 --self-contained true -o publish
dotnet build installer/Installer.wixproj -c Release
```

These are developer build commands, not end-user installation instructions. No compiled installer is committed in this repository.

## Important limitations

- The Audio Lab currently routes only explicitly selected capture devices to explicitly selected render devices. It does not yet create a virtual audio device or automatically bind individual applications such as Chrome, Telegram, WhatsApp, or Phone Link.
- It currently supports Float32 capture input only. Format conversion, independent clock drift handling, robust device hot-plug recovery, loop detection, and latency characterization remain open work.
- The WiX definition is an initial MSI packaging definition. It does not install a virtual audio driver and is not a finished `Magic Phone Setup.exe`.
- No Windows compile, clean-machine install, driver-signing, Defender review, or live audio routing test is claimed by this commit.

## Security and repository hygiene

- Do not commit `contacts.json`, `settings.json`, `events.log`, pairing tokens, personal contact lists, credentials, or user-specific logs.
- Do not upload or redistribute the previously flagged installer executable. Only source and documentation are kept here.
- The Phone Link bridge is loopback-only and requires explicit local confirmation before placing a call. A successful dial action is not proof that a call connected.
- Review dependency changes and installer changes before release. A signed, legally redistributable audio driver will be required if a virtual device is added.

## Offline tests for the legacy bridge

From `legacy/phone-link-bridge-v0.21/`:

```powershell
python -m unittest discover -s tests -v
node tests/test_extension.js
```

The initial source import was checked with 25 passing Python tests and the JavaScript parser/message-detection test. These are offline tests; they do not replace Windows Phone Link or Chrome live testing.

See `docs/PROJECT_STATUS_FA.md` for the Persian handoff and verified scope.
