import { app } from 'electron'
import { basename, extname, join } from 'path'
import { createWriteStream, existsSync, mkdirSync, readdirSync, renameSync, statSync, unlinkSync, writeFileSync } from 'fs'
import { randomUUID } from 'crypto'
import { parseFile } from 'music-metadata'
import { getDb } from './database'
import type { IpcResponse, Song } from '../types/music'
import type { WebDavConfig, WebDavFile, SyncProgress } from '../types/webdav'
export type { WebDavConfig, WebDavFile, SyncProgress } from '../types/webdav'

const MUSIC_EXTENSIONS = new Set([
  '.mp3', '.flac', '.wav', '.wave', '.aac', '.m4a', '.mp4', '.ogg', '.oga', '.opus',
  '.webm', '.wma', '.ape', '.aiff', '.aif', '.alac', '.ac3', '.dts', '.dsf', '.dff',
  '.mka', '.amr', '.mid', '.midi', '.tta', '.tak', '.wv'
])
const CACHE_DIR = join(app.getPath('userData'), 'webdav-cache')
const COVER_DIR = join(app.getPath('userData'), 'covers')
const DEFAULT_CACHE_SIZE = 2 * 1024 * 1024 * 1024
const CONNECTION_TIMEOUT_MS = 15_000
const DIRECTORY_TIMEOUT_MS = 30_000
const DOWNLOAD_TIMEOUT_MS = 30 * 60_000
mkdirSync(CACHE_DIR, { recursive: true })
mkdirSync(COVER_DIR, { recursive: true })

function errorMessage(error: unknown): string {
  if (error instanceof Error && (error.name === 'TimeoutError' || error.name === 'AbortError')) {
    return 'WebDAV 请求超时，请检查服务器或网络连接'
  }
  return error instanceof Error ? error.message : String(error)
}

function decodeXml(value: string): string {
  return value
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&amp;/g, '&')
}

export class WebDavClient {
  public readonly config: WebDavConfig
  private readonly baseUrl: URL

  constructor(config: WebDavConfig) {
    if (!config || typeof config.url !== 'string' || !config.url.trim()) {
      throw new Error('请输入 WebDAV 地址')
    }
    const url = new URL(config.url.trim())
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error('WebDAV 地址必须使用 HTTP 或 HTTPS')
    if (url.username || url.password) throw new Error('请不要在 WebDAV 地址中包含用户名或密码')
    url.hash = ''
    this.baseUrl = url
    this.config = {
      url: url.href,
      username: String(config.username || ''),
      password: String(config.password || ''),
    }
  }

  private get headers(): Record<string, string> {
    const auth = Buffer.from(`${this.config.username}:${this.config.password}`).toString('base64')
    return { Authorization: `Basic ${auth}`, 'User-Agent': 'AuroraMusic/1.0' }
  }

  private buildUrl(remotePath = ''): string {
    const base = this.baseUrl
    if (!remotePath) return base.href.replace(/\/+$/, '') + '/'
    if (/^https?:\/\//i.test(remotePath)) {
      const remote = new URL(remotePath)
      if (remote.origin !== base.origin) throw new Error('拒绝访问 WebDAV 服务器之外的地址')
      return remote.href
    }
    if (remotePath.startsWith('/')) return base.origin + remotePath
    return base.href.replace(/\/+$/, '') + '/' + remotePath.replace(/^\/+/, '')
  }

  async testConnection(): Promise<IpcResponse<boolean>> {
    try {
      const response = await fetch(this.buildUrl(), {
        method: 'PROPFIND',
        headers: { ...this.headers, Depth: '0', 'Content-Type': 'application/xml; charset=utf-8' },
        signal: AbortSignal.timeout(CONNECTION_TIMEOUT_MS),
        body: '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>'
      })
      if (response.ok || response.status === 207) return { success: true, data: true }
      return { success: false, error: `连接失败（HTTP ${response.status}）` }
    } catch (error) {
      return { success: false, error: errorMessage(error) }
    }
  }

  async listFiles(remotePath = ''): Promise<IpcResponse<WebDavFile[]>> {
    try {
      const requestUrl = this.buildUrl(remotePath)
      const response = await fetch(requestUrl, {
        method: 'PROPFIND',
        headers: { ...this.headers, Depth: '1', 'Content-Type': 'application/xml; charset=utf-8' },
        signal: AbortSignal.timeout(DIRECTORY_TIMEOUT_MS),
        body: '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>'
      })
      if (!response.ok && response.status !== 207) return { success: false, error: `读取目录失败（HTTP ${response.status}）` }
      const files = this.parsePropfind(await response.text())
      const requestPath = new URL(requestUrl).pathname.replace(/\/+$/, '')
      return { success: true, data: files.filter(file => {
        try { return new URL(file.href, requestUrl).pathname.replace(/\/+$/, '') !== requestPath }
        catch { return file.href.replace(/\/+$/, '') !== requestPath }
      }) }
    } catch (error) {
      return { success: false, error: errorMessage(error) }
    }
  }

  async downloadFile(remotePath: string, localPath: string, onProgress?: (downloaded: number, total: number) => void): Promise<IpcResponse<string>> {
    const tempPath = `${localPath}.part`
    try {
      const response = await fetch(this.buildUrl(remotePath), {
        headers: this.headers,
        signal: AbortSignal.timeout(DOWNLOAD_TIMEOUT_MS),
      })
      if (!response.ok || !response.body) return { success: false, error: `下载失败（HTTP ${response.status}）` }
      const total = Number(response.headers.get('content-length')) || 0
      const reader = response.body.getReader()
      const output = createWriteStream(tempPath)
      let downloaded = 0
      try {
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          if (!output.write(Buffer.from(value))) await new Promise<void>(resolve => output.once('drain', resolve))
          downloaded += value.byteLength
          onProgress?.(downloaded, total)
        }
        await new Promise<void>((resolve, reject) => {
          output.once('error', reject)
          output.end(resolve)
        })
      } catch (error) {
        output.destroy()
        throw error
      }
      renameSync(tempPath, localPath)
      return { success: true, data: localPath }
    } catch (error) {
      try { if (existsSync(tempPath)) unlinkSync(tempPath) } catch { /* best effort */ }
      return { success: false, error: errorMessage(error) }
    }
  }

  private parsePropfind(xml: string): WebDavFile[] {
    const responses = xml.match(/<(?:[\w-]+:)?response\b[\s\S]*?<\/(?:[\w-]+:)?response>/gi) ?? []
    return responses.flatMap(block => {
      const value = (tag: string) => {
        const match = block.match(new RegExp(`<(?:[\\w-]+:)?${tag}\\b[^>]*>([\\s\\S]*?)<\\/(?:[\\w-]+:)?${tag}>`, 'i'))
        return match ? decodeXml(match[1].trim()) : ''
      }
      const rawHref = value('href')
      if (!rawHref) return []
      let href = rawHref
      try { href = decodeURIComponent(rawHref) } catch { /* retain server value */ }
      const isDirectory = /<(?:[\w-]+:)?collection\b/i.test(block) || href.endsWith('/')
      const name = value('displayname') || basename(href.replace(/\/$/, ''))
      if (!name) return []
      return [{
        href,
        name,
        size: Number(value('getcontentlength')) || 0,
        lastModified: value('getlastmodified'),
        isDirectory,
        isMusic: !isDirectory && MUSIC_EXTENSIONS.has(extname(name).toLowerCase())
      }]
    })
  }
}

export class CacheManager {
  private maxSize = DEFAULT_CACHE_SIZE

  constructor() { this.maxSize = this.loadMaxSize() }
  get cacheDir(): string { return CACHE_DIR }
  getMaxSize(): number { return this.maxSize }

  getTotalSize(): number {
    const row = getDb().prepare("SELECT COALESCE(SUM(cacheSize), 0) AS size FROM songs WHERE localCachePath IS NOT NULL").get() as { size: number }
    return Number(row.size) || 0
  }

  getFileCount(): number {
    const row = getDb().prepare('SELECT COUNT(*) AS count FROM songs WHERE localCachePath IS NOT NULL').get() as { count: number }
    return row.count
  }

  setMaxSize(bytes: number): void {
    if (!Number.isFinite(bytes) || bytes < 128 * 1024 * 1024) throw new Error('缓存上限不能低于 128 MB')
    this.maxSize = Math.round(bytes)
    getDb().prepare('INSERT OR REPLACE INTO cache_info (key, value) VALUES (?, ?)').run('max_size', String(this.maxSize))
  }

  evictIfNeeded(neededBytes: number, protectedSongId?: string): { freed: number; count: number } {
    if (neededBytes > this.maxSize) throw new Error('该歌曲大于当前缓存上限，请先在设置中调大缓存')
    const db = getDb()
    let total = this.getTotalSize()
    let freed = 0
    let count = 0
    const rows = db.prepare(`SELECT id, localCachePath, cacheSize FROM songs
      WHERE localCachePath IS NOT NULL AND id != COALESCE(?, '')
      ORDER BY COALESCE(lastAccessedAt, lastCachedAt, createdAt) ASC`).all(protectedSongId ?? null) as Array<{ id: string; localCachePath: string; cacheSize: number }>
    for (const row of rows) {
      if (total + neededBytes <= this.maxSize) break
      try { if (existsSync(row.localCachePath)) unlinkSync(row.localCachePath) } catch { continue }
      db.prepare('UPDATE songs SET localCachePath = NULL, filePath = remotePath, cacheSize = 0, lastCachedAt = NULL WHERE id = ?').run(row.id)
      total -= Number(row.cacheSize) || 0
      freed += Number(row.cacheSize) || 0
      count++
    }
    return { freed, count }
  }

  recordCache(songId: string, localPath: string, size: number): void {
    getDb().prepare(`UPDATE songs SET localCachePath = ?, filePath = ?, cacheSize = ?,
      lastCachedAt = datetime('now'), lastAccessedAt = datetime('now') WHERE id = ?`).run(localPath, localPath, size, songId)
  }

  touch(songId: string): void {
    getDb().prepare("UPDATE songs SET lastAccessedAt = datetime('now') WHERE id = ?").run(songId)
  }

  clear(): { freed: number; count: number } {
    const db = getDb()
    const rows = db.prepare('SELECT localCachePath, cacheSize FROM songs WHERE localCachePath IS NOT NULL').all() as Array<{ localCachePath: string; cacheSize: number }>
    let freed = 0
    let count = 0
    for (const row of rows) {
      try { if (existsSync(row.localCachePath)) unlinkSync(row.localCachePath) } catch { /* best effort */ }
      freed += Number(row.cacheSize) || 0
      count++
    }
    db.prepare('UPDATE songs SET localCachePath = NULL, filePath = remotePath, cacheSize = 0, lastCachedAt = NULL').run()
    for (const name of readdirSync(CACHE_DIR)) {
      try { unlinkSync(join(CACHE_DIR, name)) } catch { /* ignore non-files */ }
    }
    return { freed, count }
  }

  enforceLimit(): { freed: number; count: number } { return this.evictIfNeeded(0) }

  private loadMaxSize(): number {
    try {
      const row = getDb().prepare('SELECT value FROM cache_info WHERE key = ?').get('max_size') as { value: string } | undefined
      const value = Number(row?.value)
      return Number.isFinite(value) && value > 0 ? value : DEFAULT_CACHE_SIZE
    } catch { return DEFAULT_CACHE_SIZE }
  }
}

function ensureSong(file: WebDavFile): Song {
  const db = getDb()
  const existing = db.prepare('SELECT * FROM songs WHERE remotePath = ?').get(file.href) as Song | undefined
  if (existing) {
    const remoteChanged = Boolean(
      (file.size > 0 && existing.remoteSize && file.size !== existing.remoteSize) ||
      (file.lastModified && existing.remoteModified && file.lastModified !== existing.remoteModified)
    )
    if (remoteChanged && existing.localCachePath) {
      try { if (existsSync(existing.localCachePath)) unlinkSync(existing.localCachePath) } catch { /* best effort */ }
      db.prepare(`UPDATE songs SET localCachePath = NULL, filePath = remotePath,
        cacheSize = 0, lastCachedAt = NULL WHERE id = ?`).run(existing.id)
    }
    db.prepare(`UPDATE songs SET remoteSize = ?, remoteModified = ? WHERE id = ?`)
      .run(file.size || existing.remoteSize || 0, file.lastModified || existing.remoteModified || null, existing.id)
    return db.prepare('SELECT * FROM songs WHERE id = ?').get(existing.id) as Song
  }
  const id = randomUUID()
  const title = file.name.replace(/\.[^.]+$/, '')
  db.prepare(`INSERT INTO songs
    (id, title, artist, album, duration, filePath, format, sourceType, remotePath,
      remoteSize, remoteModified, cacheSize, createdAt)
    VALUES (?, ?, '未知艺术家', '', 0, ?, ?, 'webdav', ?, ?, ?, 0, datetime('now'))`)
    .run(id, title, file.href, extname(file.name).slice(1).toUpperCase(), file.href, file.size, file.lastModified || null)
  return db.prepare('SELECT * FROM songs WHERE id = ?').get(id) as Song
}

export function registerWebDavFile(file: WebDavFile): Song {
  if (file.isDirectory || !file.isMusic) throw new Error('只能注册音乐文件')
  return ensureSong(file)
}

const downloadTasks = new Map<string, Promise<IpcResponse<string>>>()

export function downloadSingleSong(config: WebDavConfig, input: Song): Promise<IpcResponse<string>> {
  const key = `${config.url}\n${config.username}\n${input.remotePath || input.id}`
  const running = downloadTasks.get(key)
  if (running) return running
  const task = downloadSingleSongImpl(config, input).finally(() => downloadTasks.delete(key))
  downloadTasks.set(key, task)
  return task
}

async function downloadSingleSongImpl(config: WebDavConfig, input: Song): Promise<IpcResponse<string>> {
  if (!input.remotePath) return { success: false, error: '歌曲缺少远程路径' }
  const file: WebDavFile = {
    href: input.remotePath, name: basename(input.remotePath), size: Math.max(0, input.remoteSize || 0),
    lastModified: input.remoteModified || '', isDirectory: false, isMusic: true
  }
  const song = ensureSong(file)
  const cache = new CacheManager()
  if (song.localCachePath && existsSync(song.localCachePath)) {
    const localSize = statSync(song.localCachePath).size
    const cacheMatches = (!song.cacheSize || localSize === song.cacheSize) &&
      (!song.remoteSize || localSize === song.remoteSize)
    if (cacheMatches) {
      cache.touch(song.id)
      return { success: true, data: song.localCachePath }
    }
    try { unlinkSync(song.localCachePath) } catch { /* best effort */ }
    getDb().prepare(`UPDATE songs SET localCachePath = NULL, filePath = remotePath,
      cacheSize = 0, lastCachedAt = NULL WHERE id = ?`).run(song.id)
  }
  try { cache.evictIfNeeded(file.size || 16 * 1024 * 1024, song.id) } catch (error) { return { success: false, error: errorMessage(error) } }
  const suffix = extname(file.name).replace(/[^.a-z0-9]/gi, '').toLowerCase()
  const localPath = join(CACHE_DIR, `${song.id}${suffix}`)
  const result = await new WebDavClient(config).downloadFile(file.href, localPath)
  if (!result.success) return result
  const size = statSync(localPath).size
  if (song.remoteSize && size !== song.remoteSize) {
    try { unlinkSync(localPath) } catch { /* best effort */ }
    return { success: false, error: `下载不完整（应为 ${song.remoteSize} 字节，实际 ${size} 字节）` }
  }
  try {
    cache.evictIfNeeded(size, song.id)
  } catch (error) {
    try { unlinkSync(localPath) } catch { /* best effort */ }
    return { success: false, error: errorMessage(error) }
  }
  cache.recordCache(song.id, localPath, size)
  try {
    const metadata = await parseFile(localPath, { duration: true })
    let coverPath: string | null = null
    const picture = metadata.common.picture?.[0]
    if (picture?.data?.length) {
      const extension = picture.format.includes('png') ? '.png' : picture.format.includes('webp') ? '.webp' : '.jpg'
      coverPath = join(COVER_DIR, `${song.id}${extension}`)
      writeFileSync(coverPath, picture.data)
    }
    const lyric = metadata.common.lyrics?.[0]
    const lyricsText = lyric?.syncText?.length
      ? lyric.syncText.map((line) => {
        const seconds = Math.max(0, Number(line.timestamp || 0) / 1000)
        const minutes = Math.floor(seconds / 60).toString().padStart(2, '0')
        const rest = (seconds % 60).toFixed(2).padStart(5, '0')
        return `[${minutes}:${rest}]${line.text}`
      }).join('\n')
      : lyric?.text || null
    getDb().prepare(`UPDATE songs SET title = ?, artist = ?, album = ?, year = ?, genre = ?, duration = ?, format = ?,
      coverPath = COALESCE(?, coverPath), customLyrics = COALESCE(NULLIF(customLyrics, ''), ?) WHERE id = ?`)
      .run(metadata.common.title || song.title, metadata.common.artist || '未知艺术家', metadata.common.album || '',
        metadata.common.year || null, metadata.common.genre?.join(', ') || '', metadata.format.duration || 0,
        (metadata.format.container || song.format || suffix.slice(1)).toUpperCase(), coverPath, lyricsText, song.id)
  } catch { /* metadata is optional */ }
  return { success: true, data: localPath }
}

let syncState: SyncProgress = { total: 0, completed: 0, currentFile: '', bytesDownloaded: 0, totalBytes: 0, isRunning: false, errors: [] }
export function getSyncProgress(): SyncProgress { return { ...syncState } }
export function cancelSync(): void { syncState.isRunning = false }
export async function indexWebDavLibrary(client: WebDavClient, rootPath = ''): Promise<IpcResponse<{ indexed: number; directories: number; errors: string[] }>> {
  const queue = [rootPath]
  const visited = new Set<string>()
  let indexed = 0
  let directories = 0
  syncState = { total: 0, completed: 0, currentFile: '正在扫描 WebDAV…', bytesDownloaded: 0, totalBytes: 0, isRunning: true, errors: [] }

  while (queue.length && syncState.isRunning) {
    const path = queue.shift() || ''
    const key = path.replace(/\/+$/, '')
    if (visited.has(key)) continue
    visited.add(key)
    if (visited.size > 10_000) {
      syncState.errors.push('目录数量超过 10000，已停止扫描')
      break
    }
    syncState.currentFile = path || 'WebDAV 根目录'
    const response = await client.listFiles(path)
    if (!response.success || !response.data) {
      syncState.errors.push(`${path || '/'}: ${response.error || '读取失败'}`)
      continue
    }
    directories++
    for (const file of response.data) {
      if (file.isDirectory) queue.push(file.href)
      else if (file.isMusic) {
        ensureSong(file)
        indexed++
      }
    }
    syncState.total = visited.size + queue.length
    syncState.completed = visited.size
  }
  syncState.isRunning = false
  return { success: true, data: { indexed, directories, errors: [...syncState.errors] } }
}

export async function startSync(client: WebDavClient, files: WebDavFile[], _cache: CacheManager): Promise<IpcResponse<{ synced: number; errors: string[] }>> {
  const music = files.filter(file => file.isMusic)
  syncState = { total: music.length, completed: 0, currentFile: '', bytesDownloaded: 0, totalBytes: music.reduce((n, f) => n + f.size, 0), isRunning: true, errors: [] }
  let synced = 0
  for (const file of music) {
    if (!syncState.isRunning) break
    syncState.currentFile = file.name
    const song = ensureSong(file)
    const result = await downloadSingleSong(client.config, { ...song, cacheSize: file.size })
    if (result.success) synced++; else syncState.errors.push(`${file.name}: ${result.error}`)
    syncState.completed++
  }
  syncState.isRunning = false
  return { success: true, data: { synced, errors: syncState.errors } }
}
