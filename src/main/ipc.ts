import { ipcMain } from 'electron'
import type Database from 'better-sqlite3'
import { existsSync } from 'fs'
import { getProviders, saveProviders } from './aiProviders'
import { prepareCompatibleAudio, isDecodedPath, clearDecodedCache } from './decoder'
import { registerAssetHandlers } from './assets'
import { encryptSecret, decryptSecret } from './secrets'
import { recommendationFeed } from './recommendation'
import { generateHybridPlaylist } from './hybridPlaylist'
import type { AiProvider } from '../types/providers'
import { WebDavClient, CacheManager, startSync, getSyncProgress, cancelSync, downloadSingleSong, indexWebDavLibrary, registerWebDavFile } from './webdav'
import type {
  AiPlaylistResult,
  RecommendationFeed,
  DeepSeekConfigInput,
  DeepSeekConfigStatus,
  IpcResponse,
  Song,
  Playlist,
  PlayHistory,
} from '../types/music'
import type { WebDavConfig, WebDavFile } from './webdav'

let db: Database.Database
let cacheMgr: CacheManager

const DEFAULT_DEEPSEEK_URL = 'https://api.deepseek.com'
const DEFAULT_DEEPSEEK_MODEL = 'deepseek-chat'
const PLAY_HISTORY_LIMIT = 20_000
const PLAY_ACTIONS = new Set<PlayHistory['action']>(['start', 'complete', 'skip', 'pause'])

function requireId(value: unknown, label: string): string {
  const id = typeof value === 'string' ? value.trim() : ''
  if (!id || id.length > 200) throw new Error(`${label}无效`)
  return id
}

function normalizePlaylistSongIds(value: unknown): string {
  const parsed = typeof value === 'string' ? JSON.parse(value) : value
  if (!Array.isArray(parsed)) throw new Error('歌单歌曲列表格式无效')
  const ids = [...new Set(parsed.map((id) => requireId(id, '歌曲 ID')))]
  if (ids.length > 5_000) throw new Error('单个歌单最多包含 5000 首歌曲')
  return JSON.stringify(ids)
}

async function withWebDavClient<T>(
  config: WebDavConfig,
  action: (client: WebDavClient) => Promise<IpcResponse<T>>,
): Promise<IpcResponse<T>> {
  try {
    return await action(new WebDavClient(config))
  } catch (error: unknown) {
    return { success: false, error: error instanceof Error ? error.message : String(error) }
  }
}

function getSetting(key: string): string {
  const row = db.prepare('SELECT value FROM settings WHERE key = ?').get(key) as { value: string } | undefined
  return row?.value || ''
}

function setSetting(key: string, value: string): void {
  db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').run(key, value)
}

function deepSeekStatus(): DeepSeekConfigStatus {
  const providers = getProviders(db).filter(p => p.enabled && p.configured)
  return {
    configured: providers.length > 0,
    baseUrl: providers[0]?.baseUrl || DEFAULT_DEEPSEEK_URL,
    model: providers[0]?.model || DEFAULT_DEEPSEEK_MODEL,
  }
}

function normalizeDeepSeekUrl(value: string): string {
  const parsed = new URL(value || DEFAULT_DEEPSEEK_URL)
  if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('DeepSeek 地址必须使用 HTTP 或 HTTPS')
  const clean = parsed.toString().replace(/\/$/, '')
  return clean.endsWith('/chat/completions') ? clean : `${clean}/chat/completions`
}

function listeningHistory(): PlayHistory[] {
  return db.prepare('SELECT * FROM play_history WHERE timestamp >= ? ORDER BY timestamp DESC, id DESC LIMIT ?')
    .all(Date.now() - 180 * 86_400_000, PLAY_HISTORY_LIMIT) as PlayHistory[]
}

/** 注册所有 IPC handlers */
export function registerIpcHandlers(database: Database.Database): void {
  db = database
  cacheMgr = new CacheManager()
  registerAssetHandlers(db)
  ipcMain.handle('ai:getProviders', () => ({ success: true, data: getProviders(db) }))
  ipcMain.handle('ai:saveProviders', (_event, providers: AiProvider[]) => {
    try { return { success: true, data: saveProviders(db, providers) } }
    catch (error) { return { success: false, error: error instanceof Error ? error.message : '配置保存失败' } }
  })
  ipcMain.handle('file:prepareCompatibleAudio', async (_event, id: string): Promise<IpcResponse<string>> => {
    try { return { success: true, data: await prepareCompatibleAudio(requireId(id, '歌曲 ID')) } }
    catch (error) { return { success: false, error: error instanceof Error ? error.message : '兼容解码失败' } }
  })

  // ===== 歌曲 CRUD =====
  ipcMain.handle('music:getAllSongs', async (): Promise<IpcResponse<Song[]>> => {
    try {
      const rows = db.prepare("SELECT * FROM songs WHERE sourceType = 'webdav' ORDER BY title COLLATE NOCASE ASC").all() as Song[]
      return { success: true, data: rows }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  ipcMain.handle('music:getSongById', async (_event, id: string): Promise<IpcResponse<Song>> => {
    try {
      const row = db.prepare('SELECT * FROM songs WHERE id = ?').get(id) as Song | undefined
      return { success: true, data: row }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  ipcMain.handle('music:getRecommendations', async (_event, requestedLimit = 24): Promise<IpcResponse<Song[]>> => {
    try {
      const limit = Math.max(1, Math.min(100, Number(requestedLimit) || 24))
      const rows = db.prepare("SELECT * FROM songs WHERE sourceType = 'webdav'").all() as Song[]
      return { success: true, data: recommendationFeed(rows, listeningHistory(), limit).songs }
    } catch (err: unknown) {
      return { success: false, error: err instanceof Error ? err.message : String(err) }
    }
  })

  ipcMain.handle('music:getRecommendationFeed', (): IpcResponse<RecommendationFeed> => {
    try {
      const songs = db.prepare("SELECT * FROM songs WHERE sourceType = 'webdav'").all() as Song[]
      return { success: true, data: recommendationFeed(songs, listeningHistory(), 30) }
    } catch { return { success: false, error: '本地偏好暂时无法读取' } }
  })

  ipcMain.handle('music:setFavorite', async (_event, songId: string, favorite: boolean): Promise<IpcResponse> => {
    try {
      const result = db.prepare('UPDATE songs SET isFavorite = ? WHERE id = ?').run(favorite ? 1 : 0, songId)
      if (result.changes === 0) return { success: false, error: '歌曲不存在' }
      return { success: true }
    } catch (err: unknown) {
      return { success: false, error: err instanceof Error ? err.message : String(err) }
    }
  })

  // ===== 歌单 CRUD =====
  ipcMain.handle('music:getPlaylists', async (): Promise<IpcResponse<Playlist[]>> => {
    try {
      const rows = db.prepare('SELECT * FROM playlists ORDER BY updatedAt DESC').all() as Playlist[]
      return { success: true, data: rows }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  ipcMain.handle('music:savePlaylist', async (_event, playlist: Playlist): Promise<IpcResponse> => {
    try {
      const id = requireId(playlist?.id, '歌单 ID')
      const name = String(playlist?.name || '').trim().slice(0, 80)
      if (!name) throw new Error('歌单名称不能为空')
      const description = playlist?.description == null ? null : String(playlist.description).trim().slice(0, 500)
      const songIds = normalizePlaylistSongIds(playlist?.songIds)
      db.prepare(`
        INSERT INTO playlists (id, name, description, songIds, createdAt, updatedAt)
        VALUES (?, ?, ?, ?, COALESCE(NULLIF(?, ''), datetime('now')), datetime('now'))
        ON CONFLICT(id) DO UPDATE SET name = excluded.name, description = excluded.description,
          songIds = excluded.songIds, updatedAt = datetime('now')
      `).run(id, name, description, songIds, String(playlist?.createdAt || ''))
      return { success: true }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  ipcMain.handle('music:deletePlaylist', async (_event, id: string): Promise<IpcResponse> => {
    try {
      db.prepare('DELETE FROM playlists WHERE id = ?').run(id)
      return { success: true }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  ipcMain.handle('music:addToPlaylist', async (_event, playlistId: string, songId: string): Promise<IpcResponse> => {
    try {
      const row = db.prepare('SELECT songIds FROM playlists WHERE id = ?').get(playlistId) as { songIds: string } | undefined
      if (!row) return { success: false, error: 'Playlist not found' }
      const ids: string[] = JSON.parse(row.songIds)
      if (!ids.includes(songId)) {
        ids.push(songId)
        db.prepare('UPDATE playlists SET songIds = ?, updatedAt = datetime(\'now\') WHERE id = ?')
          .run(JSON.stringify(ids), playlistId)
      }
      return { success: true }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  ipcMain.handle('music:removeFromPlaylist', async (_event, playlistId: string, songId: string): Promise<IpcResponse> => {
    try {
      const row = db.prepare('SELECT songIds FROM playlists WHERE id = ?').get(playlistId) as { songIds: string } | undefined
      if (!row) return { success: false, error: 'Playlist not found' }
      const ids: string[] = JSON.parse(row.songIds).filter((id: string) => id !== songId)
      db.prepare('UPDATE playlists SET songIds = ?, updatedAt = datetime(\'now\') WHERE id = ?')
        .run(JSON.stringify(ids), playlistId)
      return { success: true }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  // ===== 播放历史 =====
  ipcMain.handle('music:getPlayHistory', async (_event, limit = 100, offset = 0): Promise<IpcResponse<PlayHistory[]>> => {
    try {
      const safeLimit = Math.max(1, Math.min(500, Math.trunc(Number(limit) || 100)))
      const safeOffset = Math.max(0, Math.trunc(Number(offset) || 0))
      const rows = db.prepare(
        'SELECT * FROM play_history ORDER BY timestamp DESC LIMIT ? OFFSET ?'
      ).all(safeLimit, safeOffset) as PlayHistory[]
      return { success: true, data: rows }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  ipcMain.handle('music:recordPlayAction', async (_event, record: Omit<PlayHistory, 'id'>): Promise<IpcResponse> => {
    try {
      const songId = requireId(record?.songId, '歌曲 ID')
      if (!PLAY_ACTIONS.has(record?.action)) throw new Error('播放事件无效')
      if (!db.prepare('SELECT 1 FROM songs WHERE id = ?').get(songId)) throw new Error('歌曲不存在')
      const progress = Math.max(0, Math.min(1, Number(record?.progress) || 0))
      const timestamp = Date.now()
      const inserted = db.prepare(
        'INSERT INTO play_history (songId, action, timestamp, progress) VALUES (?, ?, ?, ?)'
      ).run(songId, record.action, timestamp, progress)
      if (record.action === 'start') {
        db.prepare('UPDATE songs SET playCount = COALESCE(playCount, 0) + 1, lastPlayedAt = ? WHERE id = ?')
          .run(timestamp, songId)
      } else if (record.action === 'skip') {
        db.prepare('UPDATE songs SET skipCount = COALESCE(skipCount, 0) + 1 WHERE id = ?')
          .run(songId)
      }
      if (Number(inserted.lastInsertRowid) % 250 === 0) {
        db.prepare(`DELETE FROM play_history WHERE id NOT IN (
          SELECT id FROM play_history ORDER BY timestamp DESC LIMIT ?
        )`).run(PLAY_HISTORY_LIMIT)
      }
      return { success: true }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  // ===== 设置 =====
  ipcMain.handle('music:getSettings', async (): Promise<IpcResponse<Record<string, string>>> => {
    try {
      const rows = db.prepare("SELECT * FROM settings WHERE key NOT IN ('webdav_password', 'deepseek_api_key', 'ai_providers', 'asset_config')").all() as { key: string; value: string }[]
      const settings: Record<string, string> = {}
      for (const row of rows) {
        settings[row.key] = row.value
      }
      return { success: true, data: settings }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  ipcMain.handle('music:setSettings', async (_event, key: string, value: string): Promise<IpcResponse> => {
    try {
      if (['webdav_password', 'deepseek_api_key', 'ai_providers', 'asset_config'].includes(key)) {
        return { success: false, error: '敏感设置必须通过专用接口保存' }
      }
      db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').run(key, value)
      return { success: true }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  // ===== DeepSeek 智能歌单 =====
  ipcMain.handle('ai:getConfig', async (): Promise<IpcResponse<DeepSeekConfigStatus>> => {
    try {
      return { success: true, data: deepSeekStatus() }
    } catch (err: unknown) {
      return { success: false, error: err instanceof Error ? err.message : String(err) }
    }
  })

  ipcMain.handle('ai:saveConfig', async (_event, config: DeepSeekConfigInput): Promise<IpcResponse<DeepSeekConfigStatus>> => {
    try {
      const baseUrl = (config.baseUrl || DEFAULT_DEEPSEEK_URL).trim().replace(/\/$/, '')
      normalizeDeepSeekUrl(baseUrl)
      const model = (config.model || DEFAULT_DEEPSEEK_MODEL).trim()
      if (!model) throw new Error('模型名称不能为空')
      if (config.apiKey.trim()) setSetting('deepseek_api_key', encryptSecret(config.apiKey.trim()))
      setSetting('deepseek_base_url', baseUrl)
      setSetting('deepseek_model', model)
      return { success: true, data: deepSeekStatus() }
    } catch (err: unknown) {
      return { success: false, error: err instanceof Error ? err.message : String(err) }
    }
  })

  ipcMain.handle('ai:generatePlaylist', async (_event, rawPrompt: string, useAi = true): Promise<IpcResponse<AiPlaylistResult>> => {
    try {
      const prompt = String(rawPrompt || '').trim()
      if (prompt.length < 2) throw new Error('请更具体地描述想听的音乐')
      if (prompt.length > 500) throw new Error('描述请控制在 500 字以内')

      const rows = db.prepare("SELECT * FROM songs WHERE sourceType = 'webdav'").all() as Song[]
      if (!rows.length) throw new Error('音乐库为空，请先从 WebDAV 播放或同步歌曲')
      return { success: true, data: await generateHybridPlaylist(db, rows, listeningHistory(), prompt, useAi !== false) }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err)
      return { success: false, error: message.includes('timeout') ? 'DeepSeek 请求超时，请稍后重试' : message }
    }
  })

  // ===== 文件 URL（自定义协议） =====
  ipcMain.handle('file:getAudioUrl', async (_event, filePath: string): Promise<IpcResponse<string>> => {
    try {
      if (typeof filePath !== 'string' || !filePath || !existsSync(filePath)) {
        return { success: false, error: '本地缓存文件不存在' }
      }
      const registered = db.prepare('SELECT 1 FROM songs WHERE localCachePath = ?').get(filePath)
      if (!registered && !isDecodedPath(filePath)) return { success: false, error: '拒绝读取未登记的本地文件' }
      const normalized = filePath.replace(/\\/g, '/')
      const encoded = normalized
        .split('/')
        .map((segment, index) => index === 0 && /^[a-zA-Z]:$/.test(segment)
          ? segment
          : encodeURIComponent(segment))
        .join('/')
        .replace(/^\/+/, '')
      // Keep the Windows drive colon literal. Encoding it as %3A causes
      // Chromium's media URL safety check to reject the request.
      const url = `local-protocol://audio/${encoded}`
      return { success: true, data: url }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      return { success: false, error: msg }
    }
  })

  // ===== WebDAV =====
  ipcMain.handle('webdav:testConnection', async (_event, config: WebDavConfig): Promise<IpcResponse<boolean>> => {
    return withWebDavClient(config, (client) => client.testConnection())
  })

  ipcMain.handle('webdav:listFiles', async (_event, config: WebDavConfig, remotePath: string): Promise<IpcResponse<WebDavFile[]>> => {
    return withWebDavClient(config, (client) => client.listFiles(remotePath))
  })

  ipcMain.handle('webdav:startSync', async (_event, config: WebDavConfig, files: WebDavFile[]): Promise<IpcResponse> => {
    return withWebDavClient(config, (client) => startSync(client, files, cacheMgr))
  })

  ipcMain.handle('webdav:cancelSync', async (): Promise<IpcResponse> => {
    cancelSync()
    return { success: true }
  })

  ipcMain.handle('webdav:getSyncProgress', async () => {
    return { success: true, data: getSyncProgress() }
  })

  ipcMain.handle('webdav:getCacheInfo', async (): Promise<IpcResponse> => {
    try {
      return {
        success: true,
        data: {
          totalSize: cacheMgr.getTotalSize(),
          maxSize: cacheMgr.getMaxSize(),
          fileCount: cacheMgr.getFileCount(),
        },
      }
    } catch (err: unknown) {
      return { success: false, error: String(err) }
    }
  })

  ipcMain.handle('webdav:setCacheMaxSize', async (_event, bytes: number): Promise<IpcResponse> => {
    try {
      cacheMgr.setMaxSize(bytes)
      cacheMgr.enforceLimit()
      return { success: true }
    } catch (err: unknown) {
      return { success: false, error: String(err) }
    }
  })

  ipcMain.handle('file:getWebDavAudioUrl', async (_event, songId: string): Promise<IpcResponse<string>> => {
    try {
      const song = db.prepare('SELECT id, remotePath FROM songs WHERE id = ?').get(songId) as { id: string; remotePath: string } | undefined
      if (!song?.remotePath) return { success: false, error: '歌曲不存在或缺少 WebDAV 路径' }
      return { success: true, data: `webdav-audio://track/${encodeURIComponent(song.id)}` }
    } catch (err: unknown) {
      return { success: false, error: err instanceof Error ? err.message : String(err) }
    }
  })

  ipcMain.handle('webdav:registerFile', async (_event, file: WebDavFile): Promise<IpcResponse<Song>> => {
    try {
      return { success: true, data: registerWebDavFile(file) }
    } catch (err: unknown) {
      return { success: false, error: err instanceof Error ? err.message : String(err) }
    }
  })

  ipcMain.handle('webdav:indexLibrary', async (_event, config: WebDavConfig, remotePath = ''): Promise<IpcResponse> => {
    return withWebDavClient(config, (client) => indexWebDavLibrary(client, remotePath))
  })

  ipcMain.handle('webdav:clearCache', async (): Promise<IpcResponse> => {
    try {
      const decoded = clearDecodedCache()
      const result = cacheMgr.clear()
      return { success: true, data: { ...result, freed: result.freed + decoded } }
    } catch (err: unknown) {
      return { success: false, error: String(err) }
    }
  })

  ipcMain.handle('webdav:downloadSingle', async (_event, config: WebDavConfig, song: Song): Promise<IpcResponse<string>> => {
    return withWebDavClient(config, (client) => downloadSingleSong(client.config, song))
  })

  ipcMain.handle('webdav:prefetch', async (_event, config: WebDavConfig, song: Song): Promise<IpcResponse<string>> => {
    return withWebDavClient(config, (client) => downloadSingleSong(client.config, song))
  })

  ipcMain.handle('webdav:saveConfig', async (_event, config: WebDavConfig): Promise<IpcResponse> => {
    try {
      const normalized = new WebDavClient(config).config
      db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').run('webdav_url', normalized.url)
      db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').run('webdav_username', normalized.username)
      db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').run('webdav_password', encryptSecret(normalized.password))
      return { success: true }
    } catch (err: unknown) {
      return { success: false, error: String(err) }
    }
  })

  ipcMain.handle('webdav:loadConfig', async (): Promise<IpcResponse<WebDavConfig | null>> => {
    try {
      const rows = db.prepare('SELECT key, value FROM settings WHERE key LIKE ?').all('webdav_%') as { key: string; value: string }[]
      if (rows.length === 0) return { success: true, data: null }
      const map: Record<string, string> = {}
      for (const r of rows) map[r.key] = r.value
      const password = decryptSecret(map.webdav_password || '')
      return {
        success: true,
        data: { url: map.webdav_url || '', username: map.webdav_username || '', password },
      }
    } catch (err: unknown) {
      return { success: false, error: String(err) }
    }
  })
}
