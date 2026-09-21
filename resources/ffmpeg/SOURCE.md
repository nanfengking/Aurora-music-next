# FFmpeg provenance and binary-release requirements

Local executable: Gyan FFmpeg 9.0.2 essentials, Windows x64 static build. The supplier's README.txt declares GPL v3 and lists build features and external library revisions.

- Archive: https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip
- Archive SHA-256: `60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba`
- Supplier-reported FFmpeg source revision: https://github.com/FFmpeg/FFmpeg/commit/946fcce07b
- Upstream legal information: https://ffmpeg.org/legal.html

`npm run setup:ffmpeg` retrieves and verifies this archive for local development. Git and the source ZIP exclude the executable. The local binary is kept so development playback remains usable.

Before publicly distributing an installer containing this build, collect and provide complete corresponding source and required build materials for the exact FFmpeg binary and included libraries, preserve relevant notices, and verify the chosen distribution method meets the licences. Merely linking the FFmpeg commit or this download does not finish that work. No claim is made that these pending materials have been assembled.

Changing the binary requires reviewing the build options, external components, hashes, notices and source materials again.
