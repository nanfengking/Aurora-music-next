import { app, BrowserWindow, ipcMain, shell } from 'electron'
import { autoUpdater } from 'electron-updater'
import type { UpdateStatus } from '../types/update'

let status: UpdateStatus = { phase: 'idle', currentVersion: app.getVersion() }
let checking = false
let downloading = false

function publish(patch: Partial<UpdateStatus>) {
  status = { ...status, ...patch }
  for (const window of BrowserWindow.getAllWindows()) {
    if (!window.isDestroyed()) window.webContents.send('updates:status', status)
  }
}

export function registerUpdateHandlers(): void {
  autoUpdater.autoDownload = false
  autoUpdater.autoInstallOnAppQuit = false
  autoUpdater.allowPrerelease = false
  autoUpdater.disableWebInstaller = true

  autoUpdater.on('checking-for-update', () => publish({ phase: 'checking', message: undefined }))
  autoUpdater.on('update-available', info => publish({ phase: 'available', nextVersion: info.version, progress: 0, message: undefined }))
  autoUpdater.on('update-not-available', () => publish({ phase: 'up-to-date', nextVersion: undefined, progress: undefined, message: undefined }))
  autoUpdater.on('download-progress', info => publish({ phase: 'downloading', progress: Math.round(info.percent), message: undefined }))
  autoUpdater.on('update-downloaded', info => publish({ phase: 'ready', nextVersion: info.version, progress: 100, message: undefined }))
  autoUpdater.on('error', () => publish({ phase: 'error', message: '更新检查或下载失败，请稍后重试；也可以从 GitHub Releases 下载新版安装包直接覆盖安装。' }))

  ipcMain.handle('updates:getStatus', () => ({ success: true, data: app.isPackaged && process.platform === 'win32'
    ? status : { ...status, phase: 'unsupported', message: '仅已安装的 Windows 正式版支持应用内更新。' } }))
  ipcMain.handle('updates:check', async () => {
    if (!app.isPackaged || process.platform !== 'win32') return { success: false, error: '仅已安装的 Windows 正式版支持应用内更新。' }
    if (checking || downloading) return { success: true, data: status }
    checking = true
    try {
      await autoUpdater.checkForUpdates()
      return { success: true, data: status }
    } catch {
      publish({ phase: 'error', message: '未能获取 GitHub 更新信息。请确认已发布包含安装包和 latest.yml 的正式 Release。' })
      return { success: false, error: status.message }
    } finally { checking = false }
  })
  ipcMain.handle('updates:download', async () => {
    if (status.phase !== 'available' || downloading) return { success: false, error: '请先检查更新。' }
    downloading = true
    publish({ phase: 'downloading', progress: 0 })
    try {
      await autoUpdater.downloadUpdate()
      return { success: true, data: status }
    } catch {
      publish({ phase: 'error', message: '更新下载失败，请稍后重试。' })
      return { success: false, error: status.message }
    } finally { downloading = false }
  })
  ipcMain.handle('updates:install', () => {
    if (status.phase !== 'ready') return { success: false, error: '更新尚未下载完成。' }
    setImmediate(() => autoUpdater.quitAndInstall(false, true))
    return { success: true }
  })
  ipcMain.handle('updates:openReleases', async () => {
    await shell.openExternal('https://github.com/nanfengking/Aurora-music-next/releases')
    return { success: true }
  })
  ipcMain.handle('system:openSoundSettings', async () => {
    if (process.platform !== 'win32') return { success: false, error: '仅 Windows 支持打开声音设置。' }
    await shell.openExternal('ms-settings:sound')
    return { success: true }
  })
}
