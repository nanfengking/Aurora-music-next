# Public-release checklist

## Source

- [x] GPL-3.0-only selected; original and third-party notices separated.
- [x] Four-language UI and illustrated guides added.
- [x] Public screenshots use synthetic fixtures; no real-library copying in tests.
- [x] Personal endpoints and workspace paths removed from public material.
- [ ] Run privacy:check again after every release edit.
- [ ] Inspect all images and scan any existing repository history and releases.
- [ ] Confirm provenance and licence compatibility of all subsequently imported code.

## Function and installation

- [x] Current type/build, recommendation, codec, mocked API and UI/localization checks recorded in UI_REVIEW.md.
- [ ] Test real WebDAV endpoints, permission failures, network interruption, range handling and cancellation.
- [ ] Test actual selected API accounts/models with explicit permission and budget.
- [ ] Verify background audio, Windows media controls, sleep/resume and very large libraries.
- [ ] Test clean install, upgrade retention and uninstall on a separate Windows account.
- [ ] Sign the executable/installer for public distribution and publish verified hashes.

## Release gates still open

- [ ] Upgrade vulnerable runtime/build dependencies; rebuild SQLite and rerun the complete suite.
- [ ] Reassess the current audit report, IPC/network boundaries and media handling.
- [ ] Assemble complete matching FFmpeg/dependency source and build materials.
- [ ] Retain all packaged dependency notices and verify archive contents.

`npm run source:zip` exports only allowlisted source/docs after a heuristic privacy check. It does not mark the unchecked items complete. Installers belong in Releases after clearance, not in the source repository. This preparation does not publish anything automatically.
