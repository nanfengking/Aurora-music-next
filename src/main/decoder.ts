import { app } from 'electron'
import { execFile } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, renameSync, statSync, unlinkSync } from 'node:fs'
import { join, resolve, sep } from 'node:path'
import { getDb } from './database'
import { downloadSingleSong } from './webdav'
import { decryptSecret } from './secrets'
import type { Song } from '../types/music'

const tasks = new Map<string, Promise<string>>()
let serial: Promise<unknown> = Promise.resolve()
const LIMIT = 512 * 1024 * 1024
function directory() { const path = join(app.getPath('userData'), 'decoded-cache'); mkdirSync(path, { recursive: true }); return path }
function initialize() { getDb().exec('CREATE TABLE IF NOT EXISTS decoded_audio (songId TEXT PRIMARY KEY, fingerprint TEXT, path TEXT, size INTEGER, used INTEGER)') }
function remove(path: string) {
  if (!resolve(path).startsWith(resolve(directory()) + sep)) throw new Error('拒绝清理非解码缓存文件')
  try { if (existsSync(path)) unlinkSync(path) } catch { /* locked by player, retry later */ }
}
export function clearDecodedCache(): number {
  initialize()
  if (tasks.size) throw new Error('正在兼容解码，请稍后清空缓存')
  const rows = getDb().prepare('SELECT path FROM decoded_audio').all() as { path: string }[]
  let freed = 0
  for (const row of rows) { if (existsSync(row.path)) freed += statSync(row.path).size; remove(row.path) }
  getDb().exec('DELETE FROM decoded_audio')
  return freed
}
export function isDecodedPath(path: string): boolean { initialize(); return Boolean(getDb().prepare('SELECT 1 FROM decoded_audio WHERE path = ?').get(path)) }

export function prepareCompatibleAudio(id: string): Promise<string> {
  if (tasks.has(id)) return tasks.get(id)!
  const task = serial.catch(() => {}).then(() => convert(id))
  serial = task
  tasks.set(id, task)
  void task.finally(() => tasks.delete(id)).catch(() => {})
  return task
}
async function convert(id: string): Promise<string> {
  initialize()
  const db = getDb()
  let song = db.prepare('SELECT * FROM songs WHERE id = ?').get(id) as Song | undefined
  if (!song) throw new Error('歌曲不存在')
  if (!song.localCachePath || !existsSync(song.localCachePath)) {
    const settings = Object.fromEntries((db.prepare("SELECT key,value FROM settings WHERE key LIKE 'webdav_%'").all() as {key:string;value:string}[]).map(row => [row.key, row.value]))
    if (!settings.webdav_url) throw new Error('请先连接 WebDAV，以下载兼容解码所需的原始音频')
    const downloaded = await downloadSingleSong({ url: settings.webdav_url, username: settings.webdav_username || '', password: decryptSecret(settings.webdav_password || '') }, song)
    if (!downloaded.data) throw new Error(downloaded.error || '原始音频下载失败')
    song = db.prepare('SELECT * FROM songs WHERE id = ?').get(id) as Song
  }
  const input = song.localCachePath!
  const cacheRoot = resolve(join(app.getPath('userData'), 'webdav-cache')) + sep
  if (!resolve(input).startsWith(cacheRoot)) throw new Error('只能解码应用管理的音频缓存')
  const stat = statSync(input)
  const fingerprint = createHash('sha256').update(`${input}:${stat.size}:${stat.mtimeMs}:${song.remoteModified}`).digest('hex')
  const old = db.prepare('SELECT * FROM decoded_audio WHERE songId = ?').get(id) as {fingerprint:string;path:string} | undefined
  if (old?.fingerprint === fingerprint && existsSync(old.path)) { db.prepare('UPDATE decoded_audio SET used = ? WHERE songId = ?').run(Date.now(), id); return old.path }
  const executable = __dirname.includes('app.asar') ? join(__dirname, '../../../ffmpeg/ffmpeg.exe') : join(__dirname, '../../resources/ffmpeg/ffmpeg.exe')
  if (!existsSync(executable)) throw new Error('缺少兼容解码器，请重新安装完整版本')
  const output = join(directory(), `${fingerprint}.flac`)
  const temporary = join(directory(), `${fingerprint}.partial.flac`)
  try {
    await new Promise<void>((resolveTask, reject) => {
      const child = execFile(executable, ['-nostdin', '-hide_banner', '-v', 'error', '-y', '-protocol_whitelist', 'file,pipe', '-i', input, '-map', '0:a:0', '-vn', '-sn', '-dn', '-c:a', 'flac', '-compression_level', '3', temporary], { windowsHide: true, timeout: 180_000, maxBuffer: 1024 * 1024 }, error => {
        clearInterval(watch)
        if (error) reject(new Error('兼容解码失败：文件可能损坏、受 DRM 保护或不是有效音频。原文件未改动。'))
        else resolveTask()
      })
      const watch = setInterval(() => { if (existsSync(temporary) && statSync(temporary).size > LIMIT) child.kill() }, 1000)
    })
    if (!existsSync(temporary) || statSync(temporary).size < 64) throw new Error('解码器没有生成有效音频')
    if (statSync(temporary).size > LIMIT) throw new Error('兼容音频超过 512 MB 解码缓存限制')
    if (old) remove(old.path)
    renameSync(temporary, output)
    db.prepare('INSERT OR REPLACE INTO decoded_audio VALUES (?, ?, ?, ?, ?)').run(id, fingerprint, output, statSync(output).size, Date.now())
    const rows = db.prepare('SELECT songId,path,size FROM decoded_audio ORDER BY used DESC').all() as {songId:string;path:string;size:number}[]
    let used = 0
    for (const row of rows) { used += row.size; if (used > LIMIT && row.songId !== id) { remove(row.path); db.prepare('DELETE FROM decoded_audio WHERE songId = ?').run(row.songId) } }
    return output
  } finally { remove(temporary) }
}
