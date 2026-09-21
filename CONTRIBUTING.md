# Development and contribution

Use Windows x64, Node.js 22 LTS (22.12 or newer in the 22.x line), npm and PowerShell. Other operating systems and architectures are not validated. Review [SECURITY](SECURITY.md) before exposing a development server or distributing a build.

## Install and build

```powershell
npm ci
npm run rebuild:native
npm run setup:ffmpeg
npm run verify
npm run dev
```

The native rebuild targets Electron's ABI, not the system Node ABI. If no compatible prebuilt SQLite module is available, install the Visual Studio C++ build tools and Python required by node-gyp. Do not reuse native modules from a different Electron version.

FFmpeg is intentionally excluded from Git and the source ZIP. The setup script downloads a pinned upstream archive, verifies SHA-256 and installs only the executable needed for local playback. It does not complete binary redistribution compliance; read [SOURCE](resources/ffmpeg/SOURCE.md). It leaves an existing executable unchanged.

`npm run build` generates the three translated catalogues, checks types and emits production files. `npm run pack:win` builds an NSIS installer for local testing; **it is not an approval to publicly distribute it**. Preserve the app ID and package name when upgrading an existing installation.

## Regression tests

Install the test driver without changing the lockfile:

```powershell
npm install --no-save --package-lock=false --ignore-scripts playwright@1.62.1
npm run test:recommendations
npm run test:i18n
npm run test:features
npm run test:ui
npm run test:locales
```

The tests use the project's Electron, not a downloaded browser. All profiles are temporary project-local directories. They synthesize audio, artwork, metadata and listening history; never seed tests from AppData. API adapters are mocked at the network boundary and do not spend paid credits. The WebDAV codec test uses a loopback server. Test profiles are removed after execution; screenshots in test-results are ignored.

`test:locales` regenerates public screenshots under docs/images in four languages. Review the images before committing. Unchanged song titles and Chinese original demo lyrics in English/French screenshots are intentional.

After packaging, set `AURORA_UI_REVIEW_PACKAGED=1` for UI/features tests to load the packaged ASAR with the project's Electron runtime. This does not test installation, uninstallation, signing or upgrade retention.

## Architecture

- electron/: process lifecycle and typed preload bridge.
- src/main/: SQLite, WebDAV, bounded API adapters, resource lookup and local decode.
- recommendation.ts: local weighted behaviour, full-library retrieval and duration fitting.
- hybridPlaylist.ts: optional AI planning → local retrieval → AI ordering → local validation.
- src/services/audioEngine.ts: playback and actual-listening event accounting.
- src/pages/ and src/components/: interface; src/styles/: existing visual system.
- src/i18n/catalog.json: Simplified Chinese keys and English/French translations.
- scripts/build-translations.cjs: placeholder validation and Traditional Chinese conversion.
- src/i18n/messages.json: generated runtime catalogue; commit it for reproducibility.

Translate app-owned UI messages with t; translate main-process status at explicit boundaries with tm. Never translate arbitrary DOM, song titles, lyrics or saved playlist names. Preserve API protocol fields and database values.

## Source publishing

```powershell
npm run privacy:check
npm run source:zip
```

The exporter uses an allowlist and rejects unexpected file types/symlinks. It excludes local dependencies, outputs, executables, databases, caches, old screenshots and logs. Unzip into a new directory and upload the contents to GitHub; do not initialise a repository around an unfiltered working directory.

Keep the GPL licence and upstream notices. New contributions must be compatible with GPL-3.0-only. Confirm provenance of any imported code or artwork. Do not include personal endpoints or live secrets in examples.
