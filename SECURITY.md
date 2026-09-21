# Security status — source preview

## Release gate

This is a development/source preview, not a security-audited production binary. On 2026-09-21, the final npm audit check reported **20 findings: 3 moderate, 16 high and 1 critical**. Audit counts can change with registry advisory updates. These include development/build transitive dependencies; they are not proof that every advisory is exploitable in the packaged player. No dependency-major upgrade or automatic forced audit fix was applied during the localization/privacy work.

Important flagged components include Electron 31.7.7, Vite 5.4.21 and transitive tar in the build toolchain. Examples:
- [Electron context-isolation advisory](https://github.com/electron/electron/security/advisories/GHSA-h7rp-cf8h-j98x).
- [Electron custom-protocol advisory](https://github.com/advisories/GHSA-v3j7-r9gq-3gjw).
- [Vite Windows development-server advisory](https://github.com/advisories/GHSA-v6wh-96g9-6wx3).

Before a public binary release: select supported patched versions, rebuild SQLite for the new Electron ABI, rerun all tests, review npm audit again, and test installation/upgrade on a clean Windows account. Do not expose the development server to untrusted networks or remove navigation restrictions.

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

中文说明：本次已经清理公开材料中的个人信息并测试功能，但没有把依赖告警冒充为已修复。正式发布安装器前仍需完成依赖升级、回归、签名/安装测试与第三方源码分发准备。源码可以用于协作审阅；不要把它标注为已完成安全审计。
