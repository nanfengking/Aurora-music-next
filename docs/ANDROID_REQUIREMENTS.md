# Android feature parity and acceptance

Updated: 2026-10-12. Target devices: Xiaomi 14 Ultra and Xiaomi Pad 8 Pro.
This is an active migration, not a completion or production-quality claim.
Windows and Android share one repository; the native platform adapters differ.
All edits remain in the project workspace.

## Original requirements retained

1. Phone and tablet: portrait, landscape, split screen, insets and large text.
2. System/light/dark themes, wallpaper palette, custom accent and restrained
   Android-style motion, including system reduced motion and low-power mode.
3. All relevant desktop features; explain platform-specific substitutions
   rather than silently dropping features.
4. Small resource footprint and measured battery/thermal performance.
5. Reliable native screen-off playback with appropriate HyperOS settings;
   do not promise survival of force-stop or arbitrary OS process termination.
6. Two native resizable home-screen widgets: artistic track view and lyric view.

## Desktop inventory and current Android implementation

| Feature | Android status | Remaining work / acceptance |
| --- | --- | --- |
| WebDAV browsing/index/search | Native scoped HTTPS client, bounded Android streaming XML parser, recursive scan, full-library SQL pagination | Post-fix real-server verification, authentication failures, Unicode paths, huge libraries |
| Albums/artists/favourites/genre | Paged collection views; exact filters also constrain the full queue | Tagless files and real album artwork |
| Manual/smart playlists | SQLite rules, favourite/artist/genre/recent-day/count/duration filters | Real migration and editing workflows |
| Local recommendations/history | Shared behavioral model; recent time, start/pause/complete/skip events and deduplication | Media3 callback/interruption behavior on devices |
| Natural-language playlists | Shared intent/duration core; whole-library streaming retrieval with bounded pool | Real service and metadata-quality tests |
| AI services and priorities | Keystore storage, four protocols including Responses, opt-in, cancellation, validated failover | Real gateways, unsupported models, billing/error UX |
| Lyrics/cover/backdrop | Three-tier matching; up to 10 encrypted, prioritized JSON resource APIs; secondary search/preview/explicit choice/reset; embedded cover protection; cover blur or manual backdrop; iTunes opt-in/off by default on both platforms | Real service and image-format tests; future local image/sidecar import workflow |
| Secondary song/audio details | Source metadata, ReplayGain track/peak tags and connected-output dialog, no technical clutter on home | Bit depth, actual tag extraction and precise route diagnostics where supported |
| Playback modes | Full SQL queue shuffle, repeat-one/all, speed/app volume, native sleep timer | Real queue boundary/focus/screen-off behavior |
| Player personalization | Theme/accent/motion/power settings, independent artwork backdrop; serialized optimistic edits, responsive mini/docked/full player, native font zoom, 18/24/30/36 lyric sizes and optional resume point; inactive phone/tablet player tree unmounted | Real native font/contrast, tablet gestures and RAM/thermal impact |
| Desktop floating windows | Android mini player, tablet split panel, system notification and widgets | Real resizing/split-screen and accessibility acceptance |
| ReplayGain | Native tag-based attenuation, opt-in/off by default; bounded MP3 TXXX, FLAC/Vorbis and M4A iTunes tag adapter, peak constraint, per-track reset | Real encoded files and audible/device transitions; no loudness analysis, boost or denoising |
| Next-track prefetch | Opt-in/off by default, native complete-file attempt for known sizes up to 64 MiB; shared 128 MiB streaming cache, scoped keys, bounded cancellation and current-buffer priority | Real Media3 cache reuse, network/metering/power/cancellation and low-storage acceptance |
| Crossfade | Native off/1/2/3-second manual/automatic overlap; stable MediaSession facade, one shared focus owner, linear attenuation, bounded shadow lifetime and cancellable sockets | Real mixing, focus/noisy/sleep/seek and queued-controller behavior; decoder/thermal/headroom measurements |
| Train/offline/storage | Private streaming download, guarded publication, strong-ETag/range resume, paged task controls and reservations; 128 MiB LRU stream cache, usage/clear UI, scoped hashed keys, offline-file bypass | Real cache-reader/WorkManager behavior, low-storage/restart/update and slow large-file recovery |
| Media/codec compatibility | Media3/platform decoders; no bundled desktop FFmpeg | AAC M4A and ALAC/ISOM/MP42/FLAC fixtures on targets; choose native decoder extension if needed |
| Languages/help | Four UI/widget languages; illustrated guides, public attribution | Actual system-locale/large-font/native error translation review |
| Upgrades/data | Additive schema upgrades, stable app ID, increased preview versionCode; fail-closed fixed-private-signature build, actual APK verifier and protected/manual pinned-action CI | Owner's production key setup, actual CI, retained-data device upgrade, export/import and debug-signature mismatch recovery; Android in-app update check/download/install still needs implementation |
| Background/widgets | MediaSessionService, focus/noisy handling, saved queue, two RemoteViews widgets; no background WebView polling | HyperOS power policy, 90-minute screen-off, process death, widget resize/actions |
| Security/publication | Encrypted secrets, bounded network payloads, source allowlist, fixture-only screenshots | Independent security review, dependency advisories, release history/privacy/signing review |

Daily recommendations use a bounded indexed/sample pool; AI/local scene-playlist
retrieval walks all catalog pages. No claim that everyday recommendations score
all library rows or that an AI receives every track at once.

## Automated evidence currently available

- Windows build/type checks and Android web build/sync pass.
- Shared recommendation suite: 17 cases; audio-history regression passes.
- Hybrid API suite: 14 fixture-only cases (priority errors, invalid/oversized
  responses, cancellation budgets, raw Responses parsing, duration fits, privacy
  payload and rare tracks at the end of a 50,001-track library).
- Browser mobile smoke passes: widths 360/393/412/600/840/1200/1600; main-page
  overflow checks, full-library paging/search and queues, album/artist/favourite/
  genre filters, nested player/details/options dialogs, lyrics/seek, playlists,
  four languages/themes, API ordering and key clearing, local/hybrid generation,
  cancellation and no paused/background playback polling. A 200% computed-text
  stress check on Listen passes; this is not Android system font verification.
  Backdrop fixtures verify four-language preference persistence, clear artwork
  retention, no resource lookup, power-saving overrides and phone/tablet render
  tree switching without playback/seek commands. These are mocked UI checks,
  not measured RAM/battery savings or hardware GPU evidence.
- Download UI regression covers 65 synthetic tasks, 60-row paging, progress,
  pause/resume/retry/cancel and no closed/background download polling.
- Twelve JVM download-core cases use a real loopback HTTP server: interrupted
  body, strict Range/If-Range and ETag checks, safe full restart, invalid ranges,
  416 completion, changed account scope, weak validators, malformed metadata,
  cancellation of a blocked socket and unpublished-file recovery/cleanup.
- Host SQLite executes schema v1–10 and queries extracted from the Java source;
  checks retained history/favourites/queue order, remaining reservations,
  pause/generation guards and no-progress retry budget. This is supplemental
  SQL evidence, **not** execution of Android's SQLite adapter.
- Cache identity and lyric-size bounds are covered by JVM tests. UI fixtures
  check 36px lyrics at phone/tablet widths, rapid preference saves, restore-off
  submission, cache-clear cancellation and active-reader feedback without
  download deletion. Native reader/file/cache behavior is **not** proven by mocks.
- One hundred thirty-one JVM unit cases pass with zero failures. Debug APK assembly,
  test-APK assembly and lint pass (0 errors, 28 warnings, including dependency update notices).
  Passing is not a warning-free or device-runtime claim.
- Twenty ReplayGain JVM cases cover attenuation/peak bounds, user volume,
  default bypass, track isolation, late cached-tag precedence, and real Media3
  metadata-entry classes for MP3/FLAC/M4A tag conventions. These are not encoded
  file extraction or audio-device tests. Browser fixtures check default-off,
  save/reopen, upper-limit volume, secondary-only tags and four-language layout.
  Native preference validation and SQLite metadata/migration fixtures pass on
  an isolated Android 16 x86_64 emulator, not Xiaomi hardware.
- Twelve prefetch-policy/cancellation JVM cases cover default-off/paused/loading/
  buffering/error/power/network/repeat-one gates, metered opt-in, known-size and
  disk/cache bounds, overflow, target deduplication and cancellation callbacks.
  Cache source-fingerprint and checked HTTP-total regressions also pass. Browser
  fixtures cover default-off, save/reopen and all four localized switches. New
  Android fixtures for real SimpleCache reuse, partial/cancelled writes, source
  DTO/MediaItem transport and preference validation pass on that emulator.
- Seventeen crossfade/focus-intent math cases verify off/paused/load/error/power/
  live/repeat-one/short-track gates, speed-adjusted timing, linear volume bounds,
  ducking, explicit pause vs focus regain and deferred completion thresholds.
  Six cancellation-group cases cover lifetime isolation, closed-binding removal,
  repeated/reentrant/concurrent cancellation. Five generated-silent-WAV native
  fixtures cover off, prepared handoff, blocked-preparation pause, fast replacement
  and the session facade's next/pause path. These fixtures and native preference
  validation pass on that emulator. They do not verify production
  service history wiring, real OS focus or provider/decoder behavior.
- Real signing fixtures use isolated short-lived test keys: five configuration
  guards reject missing/partial config, wrong certificate/password and a real
  Android debug key; a fixture-signed APK passes apksigner plus actual manifest
  comparison, and the upload verifier rejects a different certificate. Workflow
  YAML is parsed to check manual/main-only triggers, read-only permissions,
  full-commit action pins, narrow secret scope and key cleanup before upload.
  This is not a production key, a remote CI run or a device upgrade test.
- Source privacy heuristics pass; images are synthetic and reviewed separately.
  This does not audit all secrets, dependency vulnerabilities or remote Git history.
- Resource JVM tests cover public HTTPS/DNS/DNS64 guards, three request tiers,
  bounded JSON/body normalization, key redaction/origin-change rules, source
  priorities/opt-in defaults and image-key isolation. JSON-java is test-only;
  these are not Android framework execution or real API-service tests.
- Resource browser tests cover secondary preview/explicit apply/reset,
  unchanged other resources, late-result rejection after close/backgrounding,
  encrypted-settings DTO/key clearing and cancelled iTunes opt-in. Three nested
  dialogs preserve their parent, close controls remain visible on scroll, and
  a failed manual lyric save retains the draft. Native calls are mocked.

- Twelve new host XML cases cover prefixed/default DAV namespaces, UTF-8/UTF-16,
  Chinese paths, escaped/numeric entities, CDATA, failed propstats, malformed and
  foreign hrefs, DTD/unknown-entity rejection and payload/depth/text limits.
  kXML2 is test-only; production uses Android's built-in parser. Four Android
  parser tests pass using Android's actual parser on that emulator.
- Twenty audio cases cover checked range arithmetic, safe nested error
  classification, bounded redirect hops, credential stripping (including return
  redirects), anonymous-header omission, MIME/encoding guards, private/mixed DNS
  rejection and cancellation.
  Four use a real loopback HTTPS server with an ephemeral test certificate:
  validated redirect ranges, strong-ETag resume with fresh signed links,
  cancellation during redirected headers and private out-of-scope rejection.
  Other redirect policy fixtures intercept requests; they are not real-provider
  tests. The ephemeral certificate and key are generated under ignored build
  test-temp storage and removed by JUnit; production TLS trust is unchanged.
- Browser regression additionally covers four-language HTTP error messages,
  raw/private-message hiding, no failed-playback polling and one-click retry.
  Native calls remain mocked; this is not evidence of working Media3 decoding.

## WebDAV hotfix after initial device feedback

An initial user device test failed while parsing a directory because the previous
JAXP code set an Apache-specific feature unsupported by Android. Preview.1 removes
those calls, uses `android.util.Xml` token parsing, rejects DTD/custom entities,
retains server/path scope checks and bounds input without building a DOM tree.
Errors are mapped to concise messages in all four languages, without returning
server XML or parser configuration URLs. The user's credentials were not used.
The user subsequently reported visible songs but Source error for every format.
This narrows the immediate investigation to the shared reading/playback path,
not just M4A; it is not a verified HTTP/decoder diagnosis.

Preview.2 fixes a confirmed compatibility gap: the audio client rejected all
download redirects. Playback and offline download now share bounded manual
HTTPS redirects. Only the original scoped directory can receive credentials;
after leaving it, public URL/DNS guards apply and authorization/cookies are
permanently removed. Range/If-Range survive redirects; redirect responses close
before the next call and cancellation tracks each new call. Download links are
not stored in partial metadata or Media3 cache redirect metadata. Audio remains
streamed, without full-song memory loading or re-encoding.

The player unwraps known causes into safe codes plus Media3's numeric code,
with four-language guidance for auth/HTTP/network/TLS/range/container/decoder
failures. Failed playback offers one-click prepare/retry; app/widget buttons
agree, and failed progress/checkpoint timers stop. Diagnostics never need an
account password or signed URL. Real provider playback, seeking, offline
download and airplane-mode testing remain unverified: no device is attached
and the user's credentials were not used.

Preview.3 adds opt-in ReplayGain attenuation, not a new diagnosis of the server
failure. Tags are read from the current selected input format, saved in nullable
schema-v10 columns and displayed only in song details. Limits are -60 to +60 dB
and peak greater than 0 and at most 64; invalid tags are ignored. Volume is the
user setting times min(1, tag amplitude, 1/peak), never an amplification. Missing
tags leave volume unchanged. A track change resets the factor; a late database
reload cannot overwrite freshly parsed current-track tags. No additional audio
requests, waveform analysis, re-encoding or background WebView timer was added.

Preview.4 adds optional native next-track prefetch, not a new diagnosis of the
server failure. It waits five eligible seconds after current loading stops,
attempts the complete next file only for known sizes up to 64 MiB, and skips
completed offline songs or queued/running downloads. It shares playback's
evictable cache, never marks a track as offline and uses no AI request or WebView
poll. Expected HTTP totals must match the indexed size. Cache keys hash account
scope, original address and source size/modification stamp; signed CDN URLs and
credentials are not persisted as cache identities. Older stream-v2 fragments
are not reused with stream-v3 keys but remain under the same LRU limit; complete
offline files and personal data are unchanged.

Pause, current loading/buffering/errors, track/policy/network changes, system
power saving and disabling cancel pending work and active sockets. The shared
Wi-Fi/unmetered preference applies; disabling it can use mobile data. Native
callbacks are registered only while enabled and playing. One worker runs at a
time, each attempt has a 90-second deadline, and idle worker threads expire after
10 seconds. Completed or failed non-cancelled attempts do not retry on every
buffer refill; a cancelled attempt may resume cached fragments after eligibility
returns. Cache clearing suppresses prefetch until the current track changes.

Prefetch leaves 8 MiB of cache headroom and at least 32 MiB of physical free-space
reserve, skips low-space cases and excludes active-reader keys from its initial
eviction. CacheWriter uses a 32 KiB transfer buffer; playback and prefetch use
2 MiB write fragments. The 128 MiB bound covers committed audio fragments, not
in-flight fragments, index overhead or process RAM. Cache eviction and actual
native read/cancel races still require runtime acceptance; compile-only fixtures
are not proof of Android behavior or low battery/thermal impact.

Preview.5 adds actual two-stream crossfade, not sequential fade-out/fade-in.
It is off by default and offers the desktop's 1/2/3-second options. The second
native player is created only near a known, eligible boundary or on a manual
selection; preparation has an eight-second timeout and failure falls back to
ordinary playback. Old playback is trimmed to one non-repeating item, preventing
an invisible outgoing player from advancing into another song. Only two players
can exist; replacing a request releases its predecessor. Each player has an
8 MiB allocator target, not a whole-song buffer or a bound on total process RAM.
The music player disables video-track decoding even if an audio container also
contains a video track; verify audio-only playback using real mixed containers.

The stable session player uses
[ForwardingSimpleBasePlayer](https://developer.android.com/reference/androidx/media3/common/ForwardingSimpleBasePlayer)
so UI, notification, widget and headset seeks use one transition path. With
crossfade enabled, a single
[Media3 AudioFocusManager](https://developer.android.com/reference/androidx/media3/common/audio/AudioFocusManager)
runs system calls on a lazy background looper and controls both streams; a user
pause cancels resumption intent. Default-off playback retains ExoPlayer's own
focus manager. Linear gains include app volume, per-track attenuation and shared
ducking. Pause, seek, error, disabling or power saving releases optional streams;
socket cancellation does not concurrently close an I/O-owned cache lease.
Automatic history completion is deferred until the outgoing stream retires.
Fade updates run every 50 ms only during at most three seconds of overlap;
boundary preparation uses a scheduled native callback, not a WebView poll.
These are implementation invariants, not proof of real mixed audio, no clipping,
reliable screen-off transitions or reduced battery use.

Preview.6 adds the desktop artwork-backdrop toggle, with low-power visuals and
system battery saver still taking priority. Disabling removes the image-bearing
blur layer, not only its opacity; the clear cover remains. Layout media queries
unmount the hidden tablet player on phones and hidden mini player on tablets,
avoiding duplicated dormant lyric/artwork trees. No playback command is sent by
resize. This is a DOM reduction, not a measured native RAM/battery improvement.

Release builds now require an existing private key and fixed public SHA-256
certificate fingerprint; debug/incorrect/missing identities fail closed.
The manual protected-environment workflow runs only reviewed main, pins action
commits, limits secrets to restoration/signing and cleans the key before upload.
The actual APK signature, ID/version/debuggable flag and hash are verified. No
production key was generated, copied or uploaded; no remote job was dispatched.
See the [four-language signing guide](android/release-signing.md). A new release
certificate cannot automatically upgrade existing debug installations.

### Preview.7 cache-writer hotfix

The user reported `AUDIO_SOURCE · Media3 2000` on the latest installed preview.
Both playback and prefetch configured a custom `CacheDataSink.Factory` without
binding its cache. Media3 1.11.1 calls `checkNotNull(cache)` when creating the
sink; playback wraps that failure as an IOException before any audio request.
A JVM regression against the real sink factory failed with that exact null
pointer before the fix and passed after the cache was explicitly bound. Both
paths now share the corrected writer configuration. This is a confirmed code
defect matching the symptom, not proof that it was the only device/server cause.
All 130 JVM cases pass; actual remote playback still requires a device retest.
No network/security guard, cache limit, credentials or user data was changed.

Previous build: Android 1.5.0-preview.7, versionCode 13; debug APK 9,018,131 bytes.
Deliverable: `release/Aurora-Music-Android-1.5.0-preview.7-debug.apk`.
SHA-256: `26EE6BCD3592AA1977B9488A674A7F2989AA7D18CD54D63F98E37F121F838CF0`.
Previous preview.2–6 deliverables are kept unchanged.
The debug signing certificate matches the preceding local build; application ID
is unchanged. A different-source installed APK may still use a different key:
do not uninstall or erase data to bypass a signature mismatch.
The packaged index, CSS and JavaScript hashes match the current mobile output.
This fingerprint identifies the local build, not a stable release or signing key;
it changes on rebuild. No Windows installer was repackaged in this hotfix.

### Preview.8 native runtime verification and anonymous audio hotfix

The first opt-in native HTTPS playback run reproduced two 401 failures with
public Media3 samples. The same public URL returns 200 without Authorization,
but returns 401 with an empty Authorization header. AudioHttp now omits an empty
header before issuing the request; nonempty credentials, scope validation,
redirect/DNS rules and TLS verification remain unchanged. A new JVM regression
failed with expected 200/actual 401 before this change and passes afterward.
This is an additional anonymous-access defect, not evidence that the user's
authenticated WebDAV account failed for the same reason.

The opt-in production-service fixture exposed a separate widget crash:
`ReceiverCallNotAllowedException` at `MusicWidgetProvider.onReceive`. A manifest
receiver's restricted context was passed to MediaController's service binding.
The controller now uses the application context; the existing bounded timeout,
main-thread handling and release remain unchanged. The original APK failed the
real broadcast test before this narrow fix; the same test passes afterward.

Previous build: Android 1.5.0-preview.9, versionCode 15; debug APK 9,018,195 bytes.
Deliverable: `release/Aurora-Music-Android-1.5.0-preview.9-debug.apk`.
SHA-256: `CEE40813BF48F6560DD5903F7C7B34F161A9294B398DBDD6AF93208F2B8CE3A4`.
The actual APK identity/version and certificate were checked; its certificate
matches previews.6–8. In-place installation over preview.8 succeeded on the
isolated emulator. This does not prove retention of a populated Xiaomi library.
Earlier APKs and Windows installers are unchanged; no production key was made.

Actual runtime evidence, 2026-10-12:

- Android 16, API 36, x86_64, AOSP image revision 2, emulator 37.1.11 with WHPX;
  owned isolated AVD `AuroraNativeChecksAPI36`, serial `emulator-5580`.
- All 43 native tests executed with **zero failures and no skipped cases**:
  18 SQLite/queue/migration/history cases, six real cache/offline cases,
  five generated-WAV transition cases, four Android XML parser cases,
  two artwork-store cases, two RemoteViews/settings cases, three AI-wire cases,
  two explicitly opted-in network playback cases, and one explicitly opted-in
  production-service/controller/widget-control case.
- The network cases use [Media3's official public samples](https://github.com/androidx/media/blob/release/demos/main/src/main/assets/media.exolist.json):
  MP3 and AAC in fragmented MP4 (the latter is served as `video/mp4`). They run
  the unmodified production StreamCache/ScopedHttpDataSource path with platform
  TLS, real codecs and volume zero; assert decoded output, advancing position,
  a seek and committed bounded cache bytes. No private server or user account
  is used. This is not an ALAC, all-M4A or real-WebDAV-provider acceptance test.
- The new network-test cleanup observes actual source closure. Media3 can close
  a loader asynchronously after player.release returns; tests wait for that
  closure before checking committed fragments or deleting their private fixture
  databases. The earlier premature-cleanup failure was a fixture issue, not a
  reason to weaken production cache safety or delete user data.
- The service case uses the actual MainActivity, MediaController and
  PlaybackService with generated silent local WAVs. It checks advancing playback
  and a real foreground service after closing the activity and releasing the
  test controller; real PendingIntent broadcasts exercise pause/resume/next/
  previous through both widget receivers and verify native title/artist/lyric
  snapshots. This is short emulator playback, not a sustained screen-off test
  or launcher-hosted RemoteViews click/render/resize acceptance.
- The service fixture is disabled by default, requires the owned-fixture opt-in
  and an AOSP emulator fingerprint, refuses all connection/provider settings
  and existing non-fixture libraries, and retains only its synthetic emulator
  state. It never deletes a production database during asynchronous shutdown.
  Its teardown attempts activity/controller/service shutdown even if another
  step throws and closes its own MusicStore in a finally block.
- Fresh full JVM run: 131 tests, 22 suites, zero failures/errors/skips. Debug and
  test APK assembly pass; lint has zero errors and 28 warnings. Source privacy
  heuristics pass for 318 allowlisted files; this is not a complete privacy audit.
- SDK, system image and emulator data remain in ignored project directories.
  Existing SDK licenses and the previous debug signing key were reused; no new
  license was accepted, no user Studio configuration was changed, and neither
  test code nor test media is added to the distributed app APK.

Browser native calls remain **mocked**. The native service test adds real
service/controller/broadcast evidence, but not focus interruptions, headset
routes, launcher-hosted widget rendering/resize, Xiaomi/HyperOS policies or
thermal/power performance.
Both physical target devices, actual private WebDAV playback and the complete
desktop feature set still require acceptance work.

The ordinary instrumentation suite is offline and skips the two network cases
and the production-service fixture. On an authorized physical test device,
`-e audioNetwork true` enables the public-media tests; the service fixture still
refuses physical devices. The full 43-case run requires a disposable, owned
AOSP API 36 x86_64 emulator with no credentials/library, named
`AuroraNativeChecksAPI36`. Check its AVD identity before installing the matching
app and test APKs. Only then run:

```text
adb -s <owned-aosp-emulator-serial> shell am instrument -w -r -e audioNetwork true -e serviceFixture true -e fixtureOwner AuroraNativeChecksAPI36 com.aurora.musicnext.test/androidx.test.runner.AndroidJUnitRunner
```

Do not treat adb's process exit alone as passing: check the final `OK (43 tests)`
and all 43 successful case results, with no failure/assumption status. A fixture
refusal or offline default skip must be reported as a skip, not a tested success.

## Preview.11: centered transport controls (2026-10-13)

Current build: Android 1.5.0-preview.11, versionCode 17; debug APK 9,020,783 bytes.
Deliverable: `release/Aurora-Music-Android-1.5.0-preview.11-debug.apk`.
SHA-256: `99AD8EA5E51373184122D012F72CC26BC1D086A55520ABA861CD10D4B3A7ED70`.
The verified original debug certificate matches previews.6–10. This enables
same-signature updates; installation on a populated Xiaomi device was not tested
in this round. No signing key is included in public source.

- Fixed the play/pause SVG's measured 15px left offset inside its 70px circle.
  Explicit grid centering and zero padding keep the icon centered without adding
  animation, dependencies, decoding changes or background work.
- The regression failed before the fix with a -15px horizontal offset. The full
  mobile browser suite then passed for play and pause and the existing five
  phone/landscape/tablet sizes. It checks real rendered SVG/button bounds, a
  shared horizontal centerline and a circular button, with a 0.5px tolerance.
  Native interfaces remain mocked; this is not a hardware playback test.
- Windows verification, Android web sync, debug assembly, 131 JVM tests in 22
  suites and lint passed. JVM failures/errors/skips: zero. Lint retains 28
  warnings, zero errors. Native instrumentation was not rerun for this CSS-only
  transport change; the prior preview.10 evidence below remains historical.
- Four guides and the synthetic player screenshot are updated. Public upload
  uses the same 320-file allowlist; local SDK/JDK, signing keys, account data,
  cache and APKs are excluded. Git ignores also protect generated/private Android
  directories during future commits.
- The first local build attempt set the debug certificate after Gradle had
  finalized that field and failed during configuration. The project-local helper
  was moved to plugin initialization and the subsequent build passed. It is
  private build tooling, not a distributed source or release-signing change.
- The previously reported specific M4A issue remains deferred, not fixed here.
- The initial GitHub build failed before compilation because SDK setup requested
  the obsolete `tools` package. Both workflows now explicitly request only
  `platform-tools`; Platform 36 and Build Tools 36 remain in their separate
  installation step. Cloud results must be checked independently of local tests.
- The next cloud run exposed duplicate Kotlin classes in the generated Cordova
  library's own instrumentation APK. The debug workflow now qualifies each task
  with `:app:`, as the signed workflow already does. Aurora's JVM tests, lint and
  instrumentation APK are still built; dependency libraries remain compiled as
  app dependencies, without building their unrelated test applications.

## Preview.10: full-screen player and safe filename fallback (2026-10-13)

Previous build: Android 1.5.0-preview.10, versionCode 16; debug APK 9,167,939 bytes.
Deliverable: `release/Aurora-Music-Android-1.5.0-preview.10-debug.apk`.
SHA-256: `82C70B411279471B8D26C705608E4B8366B2C45492A4539D26270F658D18016D`.
Its original debug certificate matches previews.6–9. In-place installation of
the tested build over preview.9 succeeded on the owned emulator, not a populated
Xiaomi device. All three packaged web assets match the current mobile build.

- Full-screen player: smaller phone artwork, a primary scrollable lyric region,
  existing configurable lyric size, lightweight SVG transport controls, and
  details/lookup/playback settings grouped in More actions. The action sheet
  joins the same back stack as dialogs; cancelled actions do not open another
  layer, and selecting an action waits for the old history traversal to finish.
- Theme-owned choice dialogs and switches replace OEM selects in settings and
  provider editors. Keyboard focus is separate from committing a value; Escape
  and back preserve the old value. Playback preferences still require Save.
  No new decoder, timer, polling loop or automatic resource request was added.
- Existing artwork blur and reduced-motion/power priority remain; blur needs
  Artwork backdrop on and Low-power visuals off, and system saver still wins.
- Filename fallback recognizes spaced artist-title names and conservative
  Chinese unspaced names. Rescans repair only empty artists with an unchanged
  full filename title. An atomic SQL predicate protects tags written between the
  earlier read and later scan update. Ambiguous names and unknown albums are
  not fabricated; favourites, manual names/resources and downloads are retained.
- Three new real SQLite cases cover filename inference, data retention and the
  interleaved tag write. Each defect was observed failing before its fix. The
  interleaving is reproduced with a fixture-only SQLite trigger; it is not a
  stress-test claim for all possible worker scheduling.
- Fresh final API 36 run: **46 successful native cases, zero failures/skips**,
  including public-media and owned production-service fixtures. Full JVM run:
  131 tests in 22 suites, zero failures/errors/skips. Debug/test assembly passes;
  lint retains 28 warnings and zero errors.
- Mobile browser regressions pass at 360/412px phones, 852×393 landscape,
  840×1180 and 1200×840 layouts, plus the existing locale/large-lyric tests.
  Native interfaces are mocked there; these are not physical Xiaomi tests.
  Four guides and the new player image use synthetic, credential-free data.
- Source privacy heuristics pass for 320 allowlisted files; this is not a
  complete binary/history privacy audit. The old source ZIPs were not replaced.
- Fixture signing verification passes: five missing/invalid/debug-key guards,
  actual signed APK identity/certificate verification and mismatch rejection.
  The first invocation used the system's old Java without the project Gradle
  cache and failed before the guards; rerunning with the project JDK/cache passed.
  Disposable fixture keys were removed by the test, and its test-only release
  APK/metadata plus the temporary debug-signing helper were removed afterward
  (four files, 7,005,349 bytes). No production release key was created or changed.
- Cleanup removed 539,156,271 bytes (141 files): downloaded JDK/SDK archives and
  the redundant SDK extraction staging copy. Installed tool files were compared
  by SHA-256 before removal; post-cleanup Android build passes. Source, prior
  APKs, Windows installers, keys, music and user data were not deleted. Removed
  downloads/staging can be fetched or regenerated; active SDK/JDK/caches remain.

The user's specific M4A failure is explicitly deferred at their request. No
claim is made that this build repairs that file or all M4A encodings. The larger
AI playlist-editor redesign is also deferred. Launcher rendering/resize, both
physical Xiaomi targets, long screen-off/power/heat and complete desktop parity
remain open acceptance work.

## Real-device acceptance gate (not complete)

Record OS/HyperOS version, physical device, app build/signature, media encoding,
network/server conditions and measured results, not just pass/fail impressions.

1. On each target: portrait/landscape, 600px split screen, gestures/insets,
   keyboard/focus, system fonts 100/150/200%, four languages, dark and wallpaper
   palettes, reduced-motion/power-save switches.
2. Add/modify playlists and migrate a prior database without uninstalling.
   Test a queue exceeding 40 tracks, shuffle traversal without missing IDs,
   last-to-first repeat, repeat-one next/previous, speed and sleep timer.
3. Screen-off playback for at least 90 minutes; Wi-Fi to mobile/loss/recovery,
   downloaded vs streamed songs, reboot/process death/force-stop. A force-stop
   is an explicit stop, not a keep-alive defect to bypass.
4. Phone call/navigation audio focus, Bluetooth reconnect, wired/USB unplug,
   volume/system route panel and notification/widget controls.
5. Both widget types: add, resize, theme/locale change, lyric timing, no widgets,
   no lyrics/cover, launcher restart and denied pinning.
6. M4A/AAC, ALAC, FLAC, tagless files, large artwork/LRC, corrupt audio and
   unreachable servers; failures must not corrupt queue/history or invent quality.
   With ReplayGain off/on: negative/positive/absent/malformed tags, high peaks,
   fast tagged-to-untagged switches, changed source tags, app volume and system
   volume. Verify no boost, previous-track gain leakage or extra network scan;
   positive tags will not make quiet tracks louder. Validate actual extraction
   from MP3 TXXX, FLAC/Vorbis and M4A freeform tags on both targets.
7. Incomplete/failed downloads, full storage, pending reservations, app restart,
   same-signature update and pause/cancel/resume. Test strong/weak/no ETag,
   changed remote files and account credentials, ignored/invalid ranges,
   cancellation during completion, very slow large files and OS-scheduled retry.
   Clear streaming cache during playback: confirm active readers survive and
   offline songs/artwork/history stay intact. Verify offline playback does not
   create streaming copies; toggle resume rapidly and restart without autoplay.
8. Record native RSS/Java heap + WebView PSS, CPU/battery and thermal deltas for
   idle, 30-minute stream, long offline/screen-off and large-library scrolling.
   Compare runs under matching brightness/network/audio volume. APK/web bundle
   size is not an estimate of runtime RAM or battery use.
9. Resource APIs: private/mixed DNS, redirects, invalid/oversized JSON/images,
   service failover, wrong versions, same-origin vs CDN key handling, expiration,
   closing/backgrounding, provider changes and image quota. Confirm preview is
   read-only, explicit choice survives new audio tags, reset is narrowly scoped,
   cancellation does not poison lookup caching, and iTunes makes no default call.
10. With prefetch off, verify no next-file requests or idle worker/callbacks. With
    it on, verify the next complete file is reused by real Media3 playback, not
    duplicated or marked offline. Test unknown/zero/over-64-MiB sizes, size changes,
    source fingerprints across MediaController transport, repeat-one, missing
    next item, completed/manual downloads, low storage, Wi-Fi/metered changes,
    power saving, blocked response cancellation and the 90-second deadline.
    Pause/seek/buffering/fast track changes must prioritize current audio and
    cancel obsolete requests. Clearing must protect active/offline data without
    immediately refilling the cache for the same current track. Measure battery,
    CPU and cache/RAM separately; a switch or mocked success proves no savings.
11. Crossfade off/on at 1/2/3 seconds: compare manual select/next/previous and
    automatic boundaries, full SQL queue wraps/shuffle, repeat-one/short/live/
    unknown durations, speed, fast consecutive requests and slow/failed next
    sources. Verify one/two-player lifetime limits, no hidden outgoing repeat,
    cancellation during blocked headers, mute/app volume/ReplayGain and real
    waveform peaks. Test transient duck/loss/gain, permanent loss/denial, user
    pause during loss, headphone unplug, sleep timer, power mode and screen-off.
    Check actual service history: no starts for priming, no duplicate completions,
    interrupted tails are not reported complete, and restore targets the incoming
    track. Measure extra decoder/RAM/CPU/battery cost; do not infer it from APK size.

## Reproduce

At the project root: `npm run android:sync`, `npm run test:mobile`,
`npm run test:recommendations`, `npm run test:hybrid`, `npm run build`,
`npm run test:android-sql`, `npm run privacy:check`.

With JDK 21/SDK 36 and dependencies already available, `npm run test:android-signing`
executes offline Gradle guards and a real fixture-signed APK verification. Its
release APK uses disposable keys and must not be distributed. Owner-signed
builds use the signing guide, not fixture keys. Debug builds remain unchanged.

In Android Studio open `android/`, select JDK 21 and install SDK 36. Run
`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug`
(use `gradlew.bat` on Windows). With an authorized test device attached, run
`./gradlew :app:connectedDebugAndroidTest`; test databases have isolated names
and must never erase the production library.
The generated-WAV crossfade harnesses assume normal power mode, make no server
request and deliberately bypass OS focus. The separate opt-in service fixture
uses actual service/controller binding and the default native focus path, but
does not validate focus interruptions or sustained screen-off behavior. These
must also be checked through the app on both target devices.

Implementation references:
[Media3 playlists](https://developer.android.com/media/media3/exoplayer/playlists),
[MediaSessionService](https://developer.android.com/media/media3/session/background-playback),
[Media3 player errors and prepare/retry](https://developer.android.com/media/media3/exoplayer/listening-to-player-events),
[Responses migration](https://developers.openai.com/api/docs/guides/migrate-to-responses).
Large queues repeat through SQL-backed window extension; do not enable native
repeat-all on only a partial 40-track window.

Resume follows [HTTP Range and If-Range](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-range)
with strong ETags only. A changed/missing validator triggers a complete restart;
credentials/URLs are never saved in partial-file metadata. WorkManager obeys
network constraints and the system scheduler; the task UI must not claim instant
retries or unlimited background execution. Reliable resumable transfers checkpoint
before eight minutes; slow non-resumable transfers still need device acceptance.
See [Android persistent-work execution limits](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

Streaming cleanup uses the [Media3 SimpleCache API](https://developer.android.com/reference/androidx/media3/datasource/cache/SimpleCache),
not recursive directory deletion. Manual clearing skips keys with open readers;
normal LRU eviction is still bounded at 128 MiB. Only HTTP(S) enters that cache,
and authorization scope, address and source size/modification stamp are hashed
into each new key. Prefetch uses the same cache through the
[Media3 CacheWriter API](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheWriter),
with cancellable sockets and small write fragments. Cache index
initialization/release runs on I/O threads through reference-counted leases.
Resume checkpoints use schema v8 epochs: old asynchronous writes/clears cannot
recover or erase a newer enabled resume point. Preference changes do not wait
for database I/O on the UI thread. Full queue contents and history are retained.

The goal remains unfinished while unexplained parity gaps or mandatory
real-device/performance checks remain.
