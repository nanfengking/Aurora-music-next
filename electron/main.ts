/// <reference types="node" />

import { app, BrowserWindow, nativeTheme, protocol, ipcMain, dialog } from 'electron'
import { join } from 'path'
import { closeDatabase, initDatabase, DatabaseVersionError } from '../src/main/database'
import { registerIpcHandlers } from '../src/main/ipc'
import { registerLocalProtocol } from '../src/main/protocol'
import { registerWindowControls, rememberWindow, savedWindowBounds } from './playerWindow'
import { registerUpdateHandlers } from '../src/main/updates'

// Media elements require a privileged, stream-capable custom scheme. This must
// be declared before Electron's ready event.
protocol.registerSchemesAsPrivileged([{
  scheme: 'local-protocol',
  privileges: {
    standard: true,
    secure: true,
    supportFetchAPI: true,
    stream: true,
    bypassCSP: false
  }
}, {
  scheme: 'webdav-audio',
  privileges: {
    standard: true,
    secure: true,
    supportFetchAPI: true,
    stream: true,
    bypassCSP: false
  }
}])

// 桌面音乐播放器需要在首个用户动作后持续自动切歌；避免 Chromium
// 在队列续播、媒体键或后台窗口状态下再次要求用户手势。
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')

function createWindow(): BrowserWindow {
  const mainWindow = new BrowserWindow({
    ...savedWindowBounds(),
    minWidth: 280,
    minHeight: 116,
    frame: false,
    show: false,
    backgroundColor: '#18181a',
    autoHideMenuBar: true,
    title: 'Aurora Music Next',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      sandbox: false,
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false
    }
  })

  mainWindow.setMenuBarVisibility(false)
  rememberWindow(mainWindow)

  mainWindow.once('ready-to-show', () => mainWindow.show())
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  mainWindow.webContents.on('will-navigate', (event) => event.preventDefault())

  // 开发模式加载 dev server，生产模式加载文件
  if (process.env.ELECTRON_RENDERER_URL) {
    mainWindow.loadURL(process.env.ELECTRON_RENDERER_URL)
  } else {
    mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }
  return mainWindow
}

const hasSingleInstanceLock = app.requestSingleInstanceLock()

function reportStartupFailure(error: unknown): void {
  // Startup can fail before the renderer and its language catalogue exist.
  // Keep this native dialog local and actionable; never expose raw DB errors.
  const messages = {
    'zh-CN': ['Aurora 无法启动', '曲库由较新版本创建。请更新 Aurora 后再打开；原资料未被迁移。', '无法打开曲库。未清除你的资料；请保留现有文件，检查存储权限或升级后重试。'],
    'zh-TW': ['Aurora 無法啟動', '曲庫由較新版本建立。請更新 Aurora 後再開啟；原資料未被遷移。', '無法開啟曲庫。未清除你的資料；請保留現有檔案，檢查儲存權限或升級後重試。'],
    en: ['Unable to start Aurora', 'This library was created by a newer version. Update Aurora before opening it; your data has not been migrated.', 'The library could not be opened. Your data has not been cleared. Keep the existing files, check storage permissions, or update Aurora and retry.'],
    fr: ['Démarrage impossible : Aurora', 'Cette bibliothèque provient d’une version plus récente. Mettez Aurora à jour ; vos données n’ont pas été migrées.', 'Impossible d’ouvrir la bibliothèque. Vos données n’ont pas été effacées. Conservez les fichiers, vérifiez les autorisations de stockage ou mettez Aurora à jour.'],
  }
  const locale = app.getLocale().toLowerCase()
  const language = locale.startsWith('zh') ? (/(tw|hk|mo|hant)/.test(locale) ? 'zh-TW' : 'zh-CN')
    : locale.startsWith('fr') ? 'fr' : 'en'
  const [title, newer, generic] = messages[language]
  try { dialog.showErrorBox(title, error instanceof DatabaseVersionError ? newer : generic) }
  finally {
    try { closeDatabase() } finally { app.exit(1) }
  }
}

if (!hasSingleInstanceLock) {
  app.quit()
} else {
  app.on('second-instance', () => {
    const window = BrowserWindow.getAllWindows()[0]
    if (!window) return
    if (window.isMinimized()) window.restore()
    window.show()
    window.focus()
  })

  app.whenReady().then(() => {
    app.setAppUserModelId('com.aurora.webdavplayer.next')
    nativeTheme.themeSource = 'system'
    ipcMain.handle('appearance:setTheme', (_event, theme: string) => {
      if (theme === 'light' || theme === 'dark' || theme === 'system') nativeTheme.themeSource = theme
    })
    // 初始化数据库
    const db = initDatabase()
    // 注册自定义协议（必须在 ready 后、数据库初始化后调用）
    registerLocalProtocol()
    // 注册 IPC handlers
    registerIpcHandlers(db)
    registerWindowControls()
    registerUpdateHandlers()

    createWindow()

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow()
      }
    })
  }).catch(reportStartupFailure)
}

app.on('will-quit', closeDatabase)

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
