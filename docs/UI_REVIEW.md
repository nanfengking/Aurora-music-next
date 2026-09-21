# Verification record — 1.4.0 source preview

Run date: 2026-09-21. Tests run on Windows x64 using the existing Electron 31.7.7 runtime. Real user profiles, keys and music were not used.

## Passed

- TypeScript checks and production builds.
- 17 pure recommendation cases: evidence, decay, diversity, full-library tail retrieval, filters, duration, unknown metadata and fallback.
- Audio event accounting: failed/stale loads, pause/resume, seeking, direct switches and completion.
- Authenticated loopback WebDAV with synthesized AAC mp42 and ALAC isom M4A: decode, play, seek and cache reuse.
- Three AI protocols with mocked requests: ordered fallback, invalid results, secret redaction and origin-change guards.
- 368-track fixture: full-library retrieval, 40-minute fit, offline zero calls, preference evidence and explicit playlist save.
- Custom lyric/cover/background adapters: preview, selection and apply.
- 125-track synthetic UI fixture: pagination, search, playback, clickable lyrics, favourites, queue, albums, playlist CRUD, light/dark persistence and cache-clear cancellation.
- Four language options: immediate switch, reload persistence, English/French UI checks, 980/1320/1600 target widths, bottom-right author signature.
- Translation unit checks: catalogue placeholders, dynamic errors, ordered provider failure chains and user-name preservation.
- User metadata integrity: song titles and original lyrics unchanged after language switches.

See [four-language guides](guides/en.md) for synthetic screenshots. The local test output records actual content dimensions, which can differ slightly from requested window sizes because of Windows scaling.

Packaged-ASAR feature/UI checks also passed using the same Electron runtime. One initial feature-test run encountered a transient Playwright main-process evaluation error ("Resulting promise was garbage collected"); a full rerun passed. This is recorded rather than silently treating the first run as successful. A local 1.4.0 preview installer was built, but its installation/upgrade flow has not been exercised and public-release gates remain open.

## Not established by these tests

- Real paid API accounts, every third-party gateway, or live resource coverage.
- Every audio codec/DRM variant, every NAS implementation, Windows version or ARM64.
- Clean-machine installer, upgrade/uninstall, code signing or SmartScreen reputation.
- Security clearance of dependencies, a complete penetration test, or FFmpeg binary redistribution compliance.
- Previously published Git history, external attachments or account-side secret revocation.

Do not expand these results into claims of universal compatibility or a completed security audit. Follow [QA_CHECKLIST](QA_CHECKLIST.md) and [SECURITY](../SECURITY.md).
