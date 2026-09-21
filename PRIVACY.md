# Privacy and publication review

## Public source boundary

The 1.4.0 source export contains project code, generic examples, licences and synthetic documentation images. Personal workspace paths and relay presets have been removed from public documentation. Screenshot tests no longer read an existing user's library. All visible demo artists, tracks, artwork and history are fictional.

The allowlisted export excludes node_modules, build outputs, installers, FFmpeg executables, SQLite files, runtime profiles, audio, personal artwork, logs, environment files and credentials. A heuristic scanner checks text for common token formats, private keys, embedded URL credentials and personal Windows paths. It reports file/line/rule only, never the suspected value. This is not a comprehensive secret detector.

Manually inspect every public image and all new files. Scan any existing Git history, branches, release attachments and hosting settings separately: the exporter does not audit a remote repository or erase previously published data.

## Runtime data

- The library index, playlist names, favourites, listening history and media cache remain in Electron's user-data directory.
- WebDAV passwords and API keys saved through the app use Electron safeStorage for the current Windows user. The whole database and media files are **not** encrypted.
- Same-account malware or a compromised application may still access credentials. Encryption is not a replacement for OS security.
- Do not upload a user-data folder, database, unreviewed crash report or logs when reporting a bug.

## Network boundaries

| Action | Data sent |
| --- | --- |
| WebDAV connection, browse, sync, play | Requests and credentials to the configured music server; local paths are not needed by the server |
| Local recommendations / local playlist mode | No AI request; scoring uses the indexed library and local behaviour |
| Optional AI playlist | Prompt, taste summary, counts and at most 240 candidate metadata records; no audio, file paths or raw event history |
| Explicit lyric / artwork search | Title, artist, album and search kind to the selected service; configured key only to that API |
| Image preview | An image download from a public HTTPS URL; no custom API key forwarded to the image host |

The app has no analytics integration. This does not mean all operation is offline: WebDAV, configured AI and explicitly requested resource searches use the network. Service operators can retain requests under their own terms. Provider fallback can send the same request to more than one operator.

No live credentials are supplied with the source. If a key, password or signed link has appeared in a public screenshot or repository, revoke/rotate it at its service. Deleting it from the current source does not revoke it or remove Git history. Existing personal user-data files are deliberately not deleted by source cleanup.

中文摘要：公开源码不包含真实曲库、账号和缓存；测试只用虚构数据。Windows 加密只保护保存的凭据，不会加密整个数据库。AI/资源检索会向所选服务发送必要信息。已泄露密钥应撤销重发；旧 Git 历史和远端附件需另行检查。
