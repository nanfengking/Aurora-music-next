# Third-party notices

Original Aurora Music Next code is Copyright © 2026 jinlaoshi, licensed GPL-3.0-only. [LICENSE](LICENSE) contains the complete licence. Third-party ownership and terms remain unchanged; the application licence does not relicense dependencies.

## Direct components in the lockfile

| Component | Version reviewed | Licence |
| --- | --- | --- |
| React / React DOM | 18.3.1 | MIT |
| Zustand | 5.0.14 | MIT |
| Howler | 2.2.4 | MIT |
| better-sqlite3 | 12.10.0 | MIT (SQLite has its own public-domain notice) |
| music-metadata | 11.12.3 | MIT |
| Electron | 31.7.7 | MIT; Chromium and bundled code have additional notices |
| electron-builder | 24.13.3 | MIT |
| electron-vite | 2.3.0 | MIT |
| Vite | 5.4.21 | MIT |
| @vitejs/plugin-react | 4.7.0 | MIT |
| TypeScript | 5.9.3 | Apache-2.0 |
| OpenCC JS | 1.4.1 | MIT AND Apache-2.0 |
| TypeScript declaration packages | See package-lock.json | MIT |

OpenCC JS is used at build time for Traditional Chinese conversion. Retain its MIT notice and the Apache-2.0 notices for its conversion dictionaries when redistributing the tool or dictionaries. This table is an overview, not a complete transitive dependency licence inventory. The source archive does not vendor node_modules. For binaries, retain each included dependency's notices and Electron's LICENSE / LICENSES.chromium.html.

## FFmpeg binary release is not cleared

The local Windows build uses Gyan's FFmpeg 9.0.2 essentials, whose supplied notice states GPL v3. Its original [licence](resources/ffmpeg/LICENSE) and [build configuration / upstream notice](resources/ffmpeg/README.txt) are retained. See [provenance and source checklist](resources/ffmpeg/SOURCE.md).

The source ZIP intentionally omits ffmpeg.exe. A download URL, hash and upstream commit alone are **not** a complete corresponding-source distribution for the binary. Before distributing an installer that includes it, supply the matching FFmpeg source, required external-library source and build materials, modifications (if any), relevant notices and an appropriate source-access mechanism under the applicable licences. Those complete materials have not been assembled or verified in this revision. Do not publish the bundled installer as licence-cleared.

Read [FFmpeg legal guidance](https://ffmpeg.org/legal.html) and the included GPL text. This checklist records an unresolved release requirement, not legal certification.

## Demo material and remote services

The geometric demo covers, synthetic tones, track names and four-line demo lyrics are original fixtures created for this project. Screenshots generated from those fixtures may be distributed with the project under GPL-3.0-only. They contain no copied commercial album covers or actual listening history.

DeepSeek, OpenAI, Anthropic, Gemini, LRCLIB and TheAudioDB are independent services, not project sponsors. Their names identify integrations. Returned lyrics/images remain subject to their owners' rights and service terms; an API result is not permission to redistribute it.
