import { BrowserWindow, ipcMain, screen } from 'electron'
import { getDb } from '../src/main/database'
import type { WindowAction } from '../src/types/player'

const DEFAULT_BOUNDS = { width: 1320, height: 860 }
const PRESETS = { mini: [360, 132], lyrics: [540, 650], full: [1320, 860] } as const

export function savedWindowBounds(): Electron.Rectangle {
  const work = screen.getPrimaryDisplay().workArea
  let saved: Partial<Electron.Rectangle> = DEFAULT_BOUNDS
  try {
    const row = getDb().prepare('SELECT value FROM settings WHERE key = ?').get('player_window') as { value: string } | undefined
    if (row) saved = JSON.parse(row.value)
  } catch { /* Old or invalid geometry uses the default. */ }
  if (!saved || typeof saved !== 'object') saved = DEFAULT_BOUNDS
  const candidate = { x: Number(saved.x) || work.x, y: Number(saved.y) || work.y, width: Number(saved.width) || 1320, height: Number(saved.height) || 860 }
  const area = screen.getDisplayMatching(candidate).workArea
  const width = Math.min(area.width, Math.max(280, candidate.width))
  const height = Math.min(area.height, Math.max(116, candidate.height))
  return { width, height, x: Math.max(area.x, Math.min(candidate.x, area.x + area.width - width)), y: Math.max(area.y, Math.min(candidate.y, area.y + area.height - height)) }
}

export function rememberWindow(window: BrowserWindow): void {
  let timer: ReturnType<typeof setTimeout> | undefined
  const save = () => {
    clearTimeout(timer)
    if (window.isDestroyed() || window.isMinimized()) return
    getDb().prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').run('player_window', JSON.stringify(window.getNormalBounds()))
  }
  const schedule = () => { clearTimeout(timer); timer = setTimeout(save, 500) }
  window.on('resize', schedule)
  window.on('move', schedule)
  window.on('close', save)
  window.on('closed', () => clearTimeout(timer))
}

export function registerWindowControls(): void {
  ipcMain.handle('window:action', (event, action: WindowAction) => {
    const window = BrowserWindow.fromWebContents(event.sender)
    if (!window) return
    if (action === 'minimize') return window.minimize()
    if (action === 'close') return window.close()
    if (action === 'maximize') return window.isMaximized() ? window.unmaximize() : window.maximize()
    if (!Object.prototype.hasOwnProperty.call(PRESETS, action)) return
    const [width, height] = PRESETS[action as keyof typeof PRESETS]
    if (window.isMaximized()) window.unmaximize()
    const area = screen.getDisplayMatching(window.getBounds()).workArea
    const [x, y] = window.getPosition()
    const w = Math.min(width, area.width), h = Math.min(height, area.height)
    window.setBounds({ width: w, height: h, x: Math.max(area.x, Math.min(x, area.x + area.width - w)), y: Math.max(area.y, Math.min(y, area.y + area.height - h)) })
  })
  ipcMain.handle('window:pin', (event, pinned: boolean) => {
    if (typeof pinned === 'boolean') BrowserWindow.fromWebContents(event.sender)?.setAlwaysOnTop(pinned)
  })
}
