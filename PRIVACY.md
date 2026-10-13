# Privacy and publication review

## Public source boundary

The allowlisted source export contains project code, generic examples, licences and synthetic documentation images. Personal workspace paths and relay presets have been removed from public documentation. Screenshot tests no longer read an existing user's library. All visible demo artists, tracks, artwork and history are fictional.

The allowlisted export excludes node_modules, build outputs, installers, FFmpeg executables, SQLite files, runtime profiles, audio, personal artwork, logs, environment files and credentials. A heuristic scanner checks text for common token formats, private keys, embedded URL credentials and personal Windows paths. It reports file/line/rule only, never the suspected value. This is not a comprehensive secret detector.

Manually inspect every public image and all new files. Scan any existing Git history, branches, release attachments and hosting settings separately: the exporter does not audit a remote repository or erase previously published data.

## Runtime data

- The library index, playlist names, favourites, listening history and media cache remain in Electron's user-data directory.
- WebDAV passwords and API keys saved through the app use Electron safeStorage for the current Windows user. The whole database and media files are **not** encrypted.
- Same-account malware or a compromised application may still access credentials. Encryption is not a replacement for OS security.
- Do not upload a user-data folder, database, unreviewed crash report or logs when reporting a bug.

On Android, library metadata, playlists, listening history and downloaded media live in the app's private storage. WebDAV credentials and the complete AI/resource-provider configurations are encrypted with an Android Keystore key; their cleartext secrets are not returned by the public settings bridge. The SQLite library, media and cover files are not encrypted. Source exports exclude Android SDK/JDK caches, local machine configuration, build outputs, `.signing/` and signing keys. Keep a release signing key privately for upgrades; never attach it to a public issue. The optional manual signed-build workflow only receives a key if the repository owner explicitly configures GitHub environment Secrets; Base64 is not encryption. It uploads only a verified APK and public verification metadata, not keys or credentials. No production signing key was created or uploaded by this task.

Optional Android ReplayGain attenuation reads track gain/peak tags already exposed by the native player. It stores the numeric tags locally and changes only native playback volume. It sends no audio or tags to an API, adds no separate file scan, and does not modify or re-encode the source music. It is off by default, never boosts, and is not denoising.

Optional Android next-track caching is off by default. Enabling it makes additional audio-range requests to your configured WebDAV server and eligible public HTTPS redirect targets while native playback is active. It uses the same scoped credential/redirect protections and Wi-Fi/unmetered preference as downloads; disabling the network limit can use mobile data. It calls no AI or metadata service and uses the shared evictable streaming cache, not a separate offline copy. Pausing, buffering, power saving or disabling cancels unfinished prefetch, but cannot undo requests already received by the server. Cache keys hash account scope, source address and source size/modification time; plaintext credentials and signed CDN URLs are not identities. Complete offline downloads and personal library data are untouched.

Optional Android crossfade is also off by default. Enabling it can prepare the next audio stream before the current one ends, using the ordinary playback network policy (including mobile data), with the same scoped WebDAV/redirect and cache protections. It calls no AI, never uploads audio, re-encodes music or marks cache as an offline download. At most two native players exist temporarily; cancelled preparation releases its network calls and does not create a listening-start event. Automatic transitions defer the outgoing completion decision until that stream retires, rather than claiming completion at the start of the fade. Actual device callback/history behavior remains an acceptance item.

Android's local recommendation mode makes no AI calls. Opt-in resource lookup sends title/artist/album metadata to enabled configured services and LRCLIB; iTunes is a separate opt-in, off by default on both platforms. Windows no longer uses TheAudioDB’s public test key by default; opt-in hybrid AI uses the same bounded metadata payload as Windows. `store: false` in a Responses request is not a guarantee that a third-party gateway will not retain traffic. Foreground Android AI/resource requests are cancelled when the app backgrounds or their dialog is closed; providers may already have processed or billed a request before cancellation.

## Network boundaries

| Action | Data sent |
| --- | --- |
| WebDAV connection, browse, sync, play | Requests and credentials to the configured music server; local paths are not needed by the server. Android audio playback/downloads can follow public HTTPS download redirects without forwarding WebDAV credentials outside the original directory |
| Optional Android next-track caching | Additional requests for the next audio file while playing; same server/CDN credential boundaries, no AI, evictable local fragments and possible mobile-data use if the network limit is disabled |
| Optional Android crossfade | Earlier next-file playback requests, potentially over mobile data, using the ordinary playback network/credential policy; no AI, audio upload or separate offline copy |
| Local recommendations / local playlist mode | No AI request; scoring uses the indexed library and local behaviour |
| Optional AI playlist | Prompt, taste summary, counts and at most 240 candidate metadata records; no audio, file paths or raw event history |
| Explicit lyric / artwork search | Title, artist, album and search kind to the selected service; configured key only to that API |
| Image preview | Public HTTPS image download. Android can send a resource key only to the exact configured endpoint origin, never to a different CDN origin; Windows sends no image key |

Android audio redirect targets are checked for HTTPS and public hosts/DNS; credentials are permanently removed after leaving the configured WebDAV directory, including a redirect back. Signed CDN links are not saved as stream-cache identities or in partial-file metadata. Player-error messages and the app's `AuroraPlayback` error-code diagnostics expose bounded codes rather than raw URLs, credentials or server bodies. This does not scrub all Android/framework logs; never share unreviewed full Logcat output. A CDN can still see its own signed link, your IP and byte-range requests; these protections do not hide traffic from its operator.

The app has no analytics integration. This does not mean all operation is offline: WebDAV, configured AI and explicitly requested resource searches use the network. Service operators can retain requests under their own terms. Provider fallback can send the same request to more than one operator.

No live credentials are supplied with the source. If a key, password or signed link has appeared in a public screenshot or repository, revoke/rotate it at its service. Deleting it from the current source does not revoke it or remove Git history. Existing personal user-data files are deliberately not deleted by source cleanup.

中文摘要：公开源码不包含真实曲库、账号和缓存；测试只用虚构数据。Windows safeStorage／Android Keystore 保护保存的凭据，不会加密整个曲库或离线音频。AI／资源检索会向所选服务发送必要信息。已泄露密钥应撤销重发；旧 Git 历史和远端附件需另行检查。正式发布仍需人工审阅与安全测试。
