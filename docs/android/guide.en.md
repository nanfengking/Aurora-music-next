# Aurora Music Android — preview guide

[简体中文](../../README.android.md) · [繁體中文](guide.zh-TW.md) · [English](guide.en.md) · [Français](guide.fr.md)

Version 1.5.0-preview.11, versionCode 17. Targets: Xiaomi 14 Ultra and Xiaomi Pad 8 Pro. The player now fills the screen with smaller artwork and a larger lyric region; details, resource lookup and playback settings are in More actions. App-owned choices and switches follow the theme. Existing audio/cache/widget fixes are retained. Isolated Android 16 tests cover short background-service playback, widget receiver controls and public MP3/AAC decoding, seeking and cache, not your WebDAV account, long screen-off playback or Xiaomi acceptance. The reported specific M4A issue is deferred at your request, not declared fixed. Install over the previous preview and retest. This is not a stable release.

This preview centers the play/pause icon inside its circular button and aligns all three transport controls. Playback, decoding and cache behavior are unchanged.

<img src="../images/android/phone-listen.png" width="280" alt="Phone layout with synthetic demo songs and Chinese UI">

![Tablet layout, synthetic demo songs](../images/android/tablet-listen.png)

These are browser screenshots using mock native calls, not Xiaomi device screenshots. The pictured UI is Chinese; choose English in Settings → Language.

## Build in Android Studio

Install Node.js 22, JDK 21 and Android SDK Platform/Build Tools 36. At the source root containing `package.json`, `mobile/`, `src/` and `android/`, run:

```sh
npm ci
npm run android:sync
```

Open the **android folder** in Android Studio, choose Gradle JDK 21, install SDK 36 and let Gradle sync. In its terminal:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Windows use `.\gradlew.bat` instead. APK: `android/app/build/outputs/apk/debug/app-debug.apk`. After changing mobile/shared TypeScript, run `npm run android:sync` again before building.

Alternatively upload the unpacked source, including `.github/`, and run **Actions → Android debug APK**. Download and unpack the `aurora-android-debug` artifact. Uploading one source ZIP does not build it. Never upload signing keys, local databases, credentials, SDK/JDK or caches.

## Listen and travel offline

1. Settings: enter your HTTPS WebDAV music-directory URL and credentials. Connect, then scan the library in Cloud. Self-signed certificates are rejected. Playback/downloads allow up to five HTTPS redirects. Outside the original WebDAV directory, only public targets are allowed; Authorization/Cookie are removed permanently, so CDN requests never carry WebDAV credentials. HTTP downgrade, private targets and loops are blocked. Directory/resource-API restrictions are unchanged.
2. Browse songs/albums/artists/favourites, search the complete library, and create manual or rule-based playlists. Song details and audio metadata are secondary dialogs.
3. Before travelling, download over Wi-Fi, switch to Offline only and test with the network disabled. Only complete downloads are offline songs; the stream cache is not a full-download guarantee.
4. Settings → Downloads shows progress and pause/resume/retry/cancel controls, in pages of 60. Pause keeps partial files; cancel clears unfinished local files, not completed or cloud music. Resume requires a matching strong ETag and verified byte range; otherwise it restarts safely. Progress polling stops when the dialog is closed or the app is backgrounded. The Wi-Fi preference applies to new jobs. Retry is OS-scheduled, not guaranteed immediate; five consecutive errors without net progress stop automatic retries. Very slow large transfers still need device validation.
5. In HyperOS allow background activity, remove battery restrictions, and optionally lock the recent task. Native playback is not a promise to survive force-stop or all OS termination.
6. Long-press the home screen → Widgets → Aurora: track or lyric widget. Both are resizable. If direct pinning is unsupported, add them manually. Lyrics refresh at line boundaries with the screen awake.

## Personalize playback

<img src="../images/android/download-tasks.png" width="280" alt="Downloads with synthetic progress and mock native calls">

Settings offers system/light/dark, wallpaper colors or a custom accent, motion and low-power visuals. Low-power mode suppresses cover blur. Four interface/widget languages are available.

Artwork backdrop can independently remove the blur layer while keeping the clear cover. No extra image lookup; low-power visuals and system battery saver still take priority.

Lyric text size offers 18/24/30/36 alongside system text scaling. Rapid appearance changes are saved in order. Playback settings → Remember track & position can be disabled: this clears the resume point, not the active queue, playlists, favourites or listening history. Re-enabling saves a new point from current playback, not the old cleared position.

Settings shows streaming-cache and artwork disk usage. Clear streaming cache releases unused fragments but retains active readers; switch tracks before clearing those. The fixed 128 MiB limit concerns disk, not application RAM. Offline files bypass this cache, so no duplicate streaming copy is made. Clearing preserves offline songs, artwork and personal library data. Cache is not a substitute for a complete pre-trip download.

<img src="../images/android/storage-settings.png" width="280" alt="Separate offline storage and streaming cache, mock data">

Tap the bottom track to open the full-screen player. Tap a lyric to seek, or Lyrics to return to the current line. More actions holds song details, lookup and playback settings. Back closes one layer at a time; cancelling a choice preserves the current value. Cover blur requires Artwork backdrop on and Low-power visuals off; system battery saver still wins, with no additional image lookup.

<img src="../images/android/phone-player.png" width="280" alt="Full-screen lyric player, synthetic songs and mock native calls, not a device screenshot">

Rescanning can infer common `周杰伦-星晴.m4a` and `Artist - Song.mp3` filenames. Only unchanged filename titles with an empty artist are repaired; tags, edited names, favourites, lyrics and downloads are retained, including tags written concurrently. Albums are not invented. Ambiguous multiple hyphens and unspaced English names remain unchanged. This is filename inference, not verified audio metadata.

Open More actions → Playback settings, or Playback & audio devices in Settings. Choose repeat-one/all, full-playlist shuffle, 0.5–2× speed, app volume and a sleep timer, then Save. The timer is native, pauses playback when due, and does not survive process termination. Reopening the app does not automatically start audio.

Tag-based normalization is off by default. Enable it and Save to attenuate using existing ReplayGain track gain/peak tags. It never boosts, denoises, re-encodes or analyzes loudness; untagged tracks stay unchanged and app volume remains your upper limit. MP3 TXXX, FLAC/Vorbis comments and M4A iTunes freeform tags are supported. Album gain, R128, Opus header gain and Sound Check are not reapplied. Missing, invalid or out-of-bounds tags are ignored, not invented as 0 dB. Tags appear only in secondary song details. Switching tracks resets the factor; disabling restores your app volume. Actual file extraction and volume transitions still need device tests.

Cache the next track ahead is also off by default; enable and Save in the same dialog. Native work starts after current playback stops loading and remains eligible for five seconds, attempting the complete next file. Only online tracks with a known size of at most 64 MiB qualify; completed offline songs and queued/running downloads are skipped. It shares the 128 MiB committed stream-cache quota, never duplicates a full offline copy, calls no AI and never marks a track as downloaded. Cache can be evicted; use complete downloads for travel.

Crossfade offers off/1/2/3 seconds; Save to apply. A second native player exists only near track end or during a manual change. Preparation times out after eight seconds and falls back to ordinary switching. Repeat-one, live, tiny or unknown-duration tracks do not auto-overlap. Pause, seeking, disabling or battery saver ends the fade; pausing preparation keeps your selection without autoplay. Both streams share focus and ducking; linear requested gains sum to no more than app volume. This is a control invariant, not measured distortion-free mixing. Buffers remain bounded, but the extra decoder increases temporary resource use and may use mobile data; no full-track memory decoding or offline marking is added.

It follows the download Wi-Fi/unmetered policy; disabling that limit can use mobile data. Pause, track changes, current loading/buffering, system power saving, network changes and disabling the switch cancel the transfer. Each attempt has a 90-second deadline and no continuous failure retry. Clearing the cache suppresses prefetch for the current track. Writes use fragments of at most 2 MiB; uncommitted fragments and index files add disk overhead, not a RAM cap. Updated keys include source size/modification time, so old streaming fragments may need refetching and remain subject to eviction; complete offline files are unchanged. Native reuse and cancellation still require device validation.

<img src="../images/android/playback-options.png" width="280" alt="Secondary playback settings, synthetic data">

Connected audio outputs are not proof of the active route, Bluetooth codec or actual output sample rate. Use the system media panel to change routes. Source metadata does not guarantee recording quality; no gain boost/denoising is applied by default.

## Tracks are visible but will not play

Install this build over the previous one. Do not uninstall, clear app data or delete the library. Try MP3, then M4A. Tap the bottom track to open the player and read the translated message plus `AUDIO_…` / Media3 code. Press Play once to retry; failed playback does not keep polling in the background.

- `AUDIO_HTTP_401` / `403`: download refused. Directory listing is not proof of download permission; check the account and WebDAV download service, then reconnect.
- `AUDIO_NETWORK` / `TIMEOUT` / `TLS`: network, timeout or certificate failure. Certificate verification stays enabled.
- `AUDIO_REDIRECT_…` / `NOT_AUDIO` / `RANGE`: redirect, an error/page instead of audio, or an invalid byte range.
- `AUDIO_FORMAT` / `DECODER`: container/device decoding failure, distinct from network failure.

Report the code, format and device/OS version only. Filter Logcat by `AuroraPlayback` for the same safe code; do not share credentials, signed links or raw server responses. Local fixtures do not use your server. Retest real playback, seeking, offline download and airplane-mode playback.

## AI, lyrics and privacy

Local recommendations require no API. Add a service URL, exact model ID and key in Settings; order enabled services by priority. Supported protocols: Chat Completions, Responses, Anthropic and Gemini. Choose the protocol your provider actually supports; model IDs are not guessed.

Natural-language playlists default to local generation. Opt-in AI sends the prompt, taste summary and bounded candidate metadata, not audio, WebDAV URLs, raw history or credentials. Failover can send/bill requests at multiple providers. Closing the dialog or backgrounding cancels outstanding requests, not charges already incurred.

Lyrics try title + artist + album, then title + artist, then title. At each tier, enabled lyric endpoints are tried in priority order before LRCLIB. Settings → Lyrics & artwork services supports up to 10 resource APIs, each with an optional encrypted Bearer key, kind and priority. These are JSON resource endpoints, not AI model URLs. Saving sends no test request; saved keys are never returned to the UI. Re-enter keys after URL changes.

Embedded/owned/configured artwork is the default. iTunes is off by default and requires a separate opt-in; enabling grants no image rights. Read [Apple’s terms](https://performance-partners.apple.com/search-api) first. The default backdrop is the enlarged, blurred cover with no extra request; a configured background API allows separate manual images.

Song details → Search, preview & choose: edit metadata, search up to 8 results, then click to load one image preview. Confirm before replacing that resource; other resources, audio, playlists and history stay unchanged. Audio tags cannot overwrite chosen covers. Closing or backgrounding cancels and clears previews. Tokens expire after 10 minutes; search again. Reset removes only the corresponding chosen local image, not cloud music.

<img src="../images/android/asset-preview.png" width="280" alt="Secondary resource preview, original synthetic data and mock native calls">
<img src="../images/android/asset-settings.png" width="280" alt="Prioritized resource APIs and disabled-by-default iTunes, synthetic configuration">

GET parameters: `title`, `artist`, `album`, `kind`. Return a `results` array with `lyrics` or `imageUrl`. Public HTTPS only, with no private network, redirects or endpoint query parameters. Image requests may carry a key only on the exact endpoint origin, never to another CDN origin. JSON: 2 MiB; image: 8 MiB; stored JPEG longest edge ≤768px. Audio is not re-encoded. Automatic artwork cache: 64 MiB / 400 images total. Chosen images have a separate 64 MiB / 400-image quota and are never auto-evicted; replace or reset when full. See the [API contract](../API_INTEGRATION.md).

Lookup sends metadata with consent and may incur service charges. Responses `store: false` does not guarantee gateway retention. Keystore protects keys, not all media/library files. See [Privacy](../../PRIVACY.md).

## Update without uninstalling

The new manual **Android signed APK** workflow and build/APK identity guards require your private key and fixed public fingerprint. No production key was generated or uploaded for you. Actual CI execution and retained-data upgrades still need testing. Follow the [four-language signing guide](release-signing.md#english). A new release key normally cannot replace an old debug signature; do not uninstall first.

An update needs the same application ID and signing certificate, with an appropriate versionCode. Keep the same private release key. Different computers/CI debug builds may have different debug signatures. Do not automatically uninstall or clear data after a mismatch: find the original key and rebuild. Data export/import is not yet complete, so uninstalling can lose playlists, credentials and offline music.

## Limits and verification

ReplayGain attenuation, optional next-track caching and native crossfade are implemented but still need hardware acceptance. Fixed-signature builds are configured; production key setup, actual CI/upgrades and data exchange remain incomplete. Local artwork/sidecar lyric imports are additional future workflows. Android uses Media3/platform decoders, not the Windows FFmpeg bundle; `.m4a`/ISOM/MP42 alone do not prove AAC/ALAC playback.

Builds, native unit tests and fixture UI/API suites have passed. Native instrumentation tests have only been compiled. Screen-off reliability, codecs, widgets, real RAM/battery/thermal use and large system fonts must be tested on both targets. See the [full parity and acceptance checklist](../ANDROID_REQUIREMENTS.md) and [security release gates](../../SECURITY.md).
