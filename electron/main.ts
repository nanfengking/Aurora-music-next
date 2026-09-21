/// <reference types="node" />

import { app, BrowserWindow, nativeTheme, protocol, ipcMain } from 'electron'
import { join } from 'path'
import { closeDatabase, initDatabase } from '../src/main/database'
import { registerIpcHandlers } from '../src/main/ipc'
import { registerLocalProtocol } from '../src/main/protocol'

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
    width: 1320,
    height: 860,
    minWidth: 980,
    minHeight: 640,
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

    createWindow()

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow()
      }
    })
  })
}

app.on('before-quit', closeDatabase)

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
