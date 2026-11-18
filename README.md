# Magic Phone

**Universal Audio Bridge — Windows Native**

Magic Phone is being developed as a professional Windows application that lets users connect audio input/output paths between applications and devices without requiring manual VoiceMeeter-style routing.

## Current repository state

The repository has been renamed to **magics-phone** and the previous Phone Link / Chrome pilot is preserved as historical project material.

The current GitHub import contains:

- `history/2026-11-18/HANDOFF_FA.md` — the original Persian project handoff.
- `history/2026-11-18/Magic-Phone-Source-Snapshot-v0.10-Bridge-v0.21.zip` — sanitized source snapshot from the latest uploaded project package.
- `history/2026-11-18/source-manifest.json` — archive manifest and privacy exclusions.

The historical snapshot is **not** the final product architecture and is not the installation baseline for the future release.

## Historical Phone Link pilot

The earlier pilot connected:

`ChatGPT / Chrome → local bridge → Microsoft Phone Link`

and used Windows UI Automation for controlled dialing. The pilot also proved a two-way audio path using VB-Audio Virtual Cable and VoiceMeeter.

The historical material records the tested behavior and previous versions. It is kept for reference and regression understanding, not as the final product.

## Future Magic Phone architecture

The planned product direction is a native Windows application with:

- A modern Persian RTL-first Windows UI.
- Built-in audio routing management.
- Per-application input/output selection.
- Routing between applications such as Chrome, Telegram, WhatsApp and Phone Link.
- A dedicated Magic Phone virtual audio layer where technically required.
- A professional installer such as **Magic Phone Setup.exe**.
- No requirement for the end user to manually install Python, VoiceMeeter or helper scripts.

The final installer and production virtual-audio driver are **not yet included**.

## Security and repository hygiene

Never commit:

- `contacts.json`
- `settings.json`
- `events.log`
- pairing tokens
- personal contact lists
- credentials
- user-specific runtime data

These rules are also enforced by the repository `.gitignore`.

## Development rule

Future product development should proceed forward from the actual GitHub `main` branch. Historical snapshots are reference material only and should not be used to overwrite newer working code.

No installer, production driver, or clean-machine installation success is claimed by the current repository state.
