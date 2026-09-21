import { app, protocol, safeStorage } from 'electron'
import { statSync, existsSync, createReadStream } from 'fs'
import { extname, join, resolve, sep } from 'path'
import { Readable } from 'stream'
import { getDb } from './database'

const MIME_MAP: Record<string, string> = {
  '.mp3': 'audio/mpeg', '.mpeg': 'audio/mpeg', '.mpga': 'audio/mpeg',
  '.flac': 'audio/flac',
  '.ogg': 'audio/ogg', '.oga': 'audio/ogg', '.opus': 'audio/ogg',
  '.wav': 'audio/wav', '.wave': 'audio/wav',
  '.wma': 'audio/x-ms-wma',
  '.aac': 'audio/aac',
  '.m4a': 'audio/mp4', '.mp4': 'audio/mp4', '.m4b': 'audio/mp4',
  '.webm': 'audio/webm', '.weba': 'audio/webm',
  '.ape': 'audio/ape',
  '.aiff': 'audio/aiff', '.aif': 'audio/aiff',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp',
}

function getMimeType(filePath: string): string {
  return MIME_MAP[extname(filePath).toLowerCase()] || 'audio/mpeg'
}

function isManagedMediaPath(filePath: string): boolean {
  const candidate = resolve(filePath)
  const roots = [
    resolve(join(app.getPath('userData'), 'webdav-cache')),
    resolve(join(app.getPath('userData'), 'covers')),
    resolve(join(app.getPath('userData'), 'decoded-cache')),
    resolve(join(app.getPath('userData'), 'online-assets')),
  ]
  const normalize = (value: string) => process.platform === 'win32' ? value.toLocaleLowerCase() : value
  const normalizedCandidate = normalize(candidate)
  return roots.some((root) => {
    const normalizedRoot = normalize(root)
    return normalizedCandidate === normalizedRoot || normalizedCandidate.startsWith(`${normalizedRoot}${sep}`)
  })
}

/** Readable stream → Response (流式传输，不占内存) */
function streamToResponse(stream: Readable, size: number, mime: string, range?: { start: number; end: number }): Response {
  // 使用 Electron 的 ReadableStream 包装 Node.js stream
  const webStream = Readable.toWeb(stream) as ReadableStream
  const headers: Record<string, string> = {
    'Content-Type': mime,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-cache',
    'Access-Control-Allow-Origin': '*',
  }
  if (range) {
    headers['Content-Range'] = `bytes ${range.start}-${range.end}/${size}`
    headers['Content-Length'] = String(range.end - range.start + 1)
    return new Response(webStream, { status: 206, headers })
  }
  headers['Content-Length'] = String(size)
  return new Response(webStream, { status: 200, headers })
}

export function registerLocalProtocol(): void {
  protocol.handle('local-protocol', (request) => {
    try {
      const reqUrl = new URL(request.url)
      let filePath = decodeURIComponent(reqUrl.pathname)
      if (process.platform === 'win32' && /^\/[a-zA-Z]:/.test(filePath)) {
        filePath = filePath.slice(1)
      }

      if (!isManagedMediaPath(filePath)) {
        return new Response(null, { status: 403 })
      }

      if (!existsSync(filePath)) {
        return new Response(null, { status: 404 })
      }

      const stat = statSync(filePath)
      if (!stat.isFile()) {
        return new Response(null, { status: 404 })
      }
      const fileSize = stat.size
      const mimeType = getMimeType(filePath)

      // Range 请求（拖动进度条时）
      const rangeHeader = request.headers.get('Range')
      if (rangeHeader) {
        const match = rangeHeader.match(/bytes=(\d+)-(\d*)/)
        if (match) {
          const start = parseInt(match[1], 10)
          if (start >= fileSize) {
            return new Response(null, {
              status: 416,
              headers: { 'Content-Range': `bytes */${fileSize}` }
            })
          }
          const end = match[2] ? Math.min(parseInt(match[2], 10), fileSize - 1) : fileSize - 1
          const stream = createReadStream(filePath, { start, end })
          return streamToResponse(stream, fileSize, mimeType, { start, end })
        }
      }

      // 完整文件：流式传输（不读入内存）
      const stream = createReadStream(filePath)
      return streamToResponse(stream, fileSize, mimeType)
    } catch (err) {
      console.error('[local-protocol]', err)
      return new Response(null, { status: 500 })
    }
  })

  protocol.handle('webdav-audio', async (request) => {
    try {
      const requestUrl = new URL(request.url)
      const songId = decodeURIComponent(requestUrl.pathname.replace(/^\/+/, ''))
      if (!songId) return new Response(null, { status: 400 })
      const db = getDb()
      const song = db.prepare('SELECT remotePath FROM songs WHERE id = ?').get(songId) as { remotePath: string } | undefined
      if (!song?.remotePath) return new Response(null, { status: 404 })
      const rows = db.prepare('SELECT key, value FROM settings WHERE key LIKE ?').all('webdav_%') as Array<{ key: string; value: string }>
      const settings = Object.fromEntries(rows.map((row) => [row.key, row.value]))
      const base = new URL(settings.webdav_url || '')
      let remoteUrl: URL
      if (/^https?:\/\//i.test(song.remotePath)) remoteUrl = new URL(song.remotePath)
      else if (song.remotePath.startsWith('/')) remoteUrl = new URL(song.remotePath, base.origin)
      else remoteUrl = new URL(song.remotePath.replace(/^\/+/, ''), `${base.href.replace(/\/+$/, '')}/`)
      if (remoteUrl.origin !== base.origin) return new Response(null, { status: 403 })

      let password = settings.webdav_password || ''
      if (password.startsWith('encrypted:')) {
        if (!safeStorage.isEncryptionAvailable()) return new Response(null, { status: 401 })
        try { password = safeStorage.decryptString(Buffer.from(password.slice(10), 'base64')) }
        catch { return new Response(null, { status: 401 }) }
      }
      const headers: Record<string, string> = {
        Authorization: `Basic ${Buffer.from(`${settings.webdav_username || ''}:${password}`).toString('base64')}`,
        'User-Agent': 'AuroraMusic/1.0',
      }
      const range = request.headers.get('Range')
      if (range) headers.Range = range
      const upstream = await fetch(remoteUrl, { headers, signal: request.signal })
      if (!upstream.ok && upstream.status !== 206) return new Response(null, { status: upstream.status })
      const responseHeaders = new Headers()
      for (const name of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'etag', 'last-modified']) {
        const value = upstream.headers.get(name)
        if (value) responseHeaders.set(name, value)
      }
      const upstreamType = responseHeaders.get('content-type') || ''
      if (!upstreamType || /octet-stream|binary/i.test(upstreamType)) {
        responseHeaders.set('Content-Type', getMimeType(song.remotePath))
      }
      responseHeaders.set('Accept-Ranges', 'bytes')
      responseHeaders.set('Cache-Control', 'no-store')
      responseHeaders.set('Access-Control-Allow-Origin', '*')
      return new Response(upstream.body, { status: upstream.status, headers: responseHeaders })
    } catch (error) {
      console.error('[webdav-audio]', error)
      return new Response(null, { status: 502 })
    }
  })
}

