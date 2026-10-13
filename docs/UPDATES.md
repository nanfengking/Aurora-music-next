# Windows 更新与覆盖安装

Aurora Music Next 1.4.0 及后续版本沿用同一 `appId`（`com.aurora.webdavplayer.next`）和 NSIS 安装器。下载较新版本的 `Aurora-Music-Next-<version>-Setup.exe` 后，直接运行即可覆盖同系列旧版，不必先卸载。使用相同的安装路径；音乐库、歌单、WebDAV 配置及缓存存放在当前 Windows 用户数据目录，不在安装目录内。仍建议首次升级前备份重要配置。

1.5.0 起，设置中提供「检查更新」。它只从项目的 [GitHub Releases](https://github.com/nanfengking/Aurora-music-next/releases) 查询；发现新版后，用户分别确认下载和安装，不会在后台静默安装。仅安装包版本支持，开发模式不触发。应用内更新依赖同一次构建生成的安装包、blockmap 和 `latest.yml`；只上传源码 ZIP 不会使更新功能可用。

发布者每次发布时应先递增 `package.json` 版本，运行 `npm run pack:win`，验证 `release/` 中的安装包、blockmap 与 `latest.yml`，然后把这三个文件上传到同一个公开、非草稿 GitHub Release。不要混用不同构建的安装包和清单。`pack:win` 不会自动上传。发布前仍需完成[安全与许可证清单](QA_CHECKLIST.md)，尤其是代码签名、依赖审计和 FFmpeg 对应源码材料。

旧版 `Aurora Music` 1.1.x 使用不同的 `appId` 和产品名，不能保证被 `Aurora Music Next` 识别为同一安装。它可能需要一次性手动卸载；卸载前请备份旧版数据。不要删除用户数据目录来“解决”更新问题。
