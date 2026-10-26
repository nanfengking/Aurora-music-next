# Security status — source preview

## Release gate

The current release is a preview. It is not a security-audited production release. The runtime/build update moves Windows to Electron 42.11.13, Vite 6.4.4, electron-vite 3.1.0 and electron-builder 26.17.0. The dependency audit recorded on 2026-10-14 fell from 30 findings (11 moderate, 18 high, 1 critical) to 11 moderate, with no high or critical findings in that snapshot. Advisory databases change; those counts are dated evidence, not a vulnerability-free guarantee. Current changes are listed in the [release notes](docs/releases/1.5.0-preview.12.md).

The baseline used Electron 31.7.7, Vite 5.4.21 and vulnerable build-chain tar copies. The candidate uses Electron 42.11.13, Vite 6.4.4, electron-vite 3.1.0 and electron-builder 26.17.0. Remaining advisory families involve uuid and sprintf-js in platform-generation/downloader/build tools; dependency classification and inspected call sites narrow exposure but do not erase findings. Examples of baseline advisories:
- [Electron context-isolation advisory](https://github.com/electron/electron/security/advisories/GHSA-h7rp-cf8h-j98x).
- [Electron custom-protocol advisory](https://github.com/advisories/GHSA-v3j7-r9gq-3gjw).
- [Vite Windows development-server advisory](https://github.com/advisories/GHSA-v6wh-96g9-6wx3).

Before a public binary release: revalidate current version support/advisories, keep the ABI-matched SQLite build, review remaining toolchain exposure, rerun the complete regression and test installation/upgrade on a clean Windows account. Do not expose the development server to untrusted networks or remove navigation restrictions. Local native/ASAR/fixture migration tests are not proof of retained-data installer upgrades or physical-device acceptance.

The app currently disables nodeIntegration, enables contextIsolation, denies new windows/navigation and bounds several remote responses. Those controls do not replace runtime updates or a full IPC/network security audit. Runtime risks including malformed media, untrusted remote content and resource-download handling require further review.

## Reporting

Do not include credentials, private WebDAV URLs, original media, databases or personal paths in public issues. Use GitHub's private vulnerability reporting once the maintainer has enabled it; otherwise privately contact the maintainer through a channel they publish. No private contact address is preconfigured in this source.

## Public-release checklist

- Update affected runtime/build dependencies and recheck advisories.
- Verify the exact packaged files, dependency licences and signed installer on clean Windows.
- Prepare complete FFmpeg corresponding-source materials; see [third-party notices](THIRD_PARTY_NOTICES.md).
- Run the source privacy check; manually inspect screenshots and repository history.
- Enable secret scanning/dependency alerts where available after repository creation.
- Do not announce “no vulnerabilities” based only on a clean heuristic privacy scan.

中文说明：当前为预览版。Android 沿用既有预览签名，正式签名迁移、真机长时运行与安装升级仍需验证。Windows 安装包待 FFmpeg 对应源码和构建材料补齐后提供。请勿在公开问题中附上账号、密钥、私有地址、数据库或听歌记录。
