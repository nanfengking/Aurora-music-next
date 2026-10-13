import { Capacitor } from '@capacitor/core'
export const seconds = (value: number) => `${Math.floor(Math.max(0, value) / 60)}:${Math.floor(Math.max(0, value) % 60).toString().padStart(2, '0')}`
export const bytes = (value: number) => value < 1024 ** 2 ? `${Math.round(value / 1024)} KB` : value < 1024 ** 3 ? `${(value / 1024 ** 2).toFixed(1)} MB` : `${(value / 1024 ** 3).toFixed(1)} GB`
export const imageUrl = (path: string | null, revision = 0) => path ? (Capacitor.isNativePlatform() ? Capacitor.convertFileSrc(path) : path) + `?rev=${revision}` : ''

export interface LyricLine { time: number; text: string }
export function parseLyrics(text: string | null): LyricLine[] {
  if (!text) return []
  let value = text
  // Windows exports may store a JSON lyric result rather than raw LRC.
  try { const parsed = JSON.parse(text); if (typeof parsed?.lyrics === 'string') value = parsed.lyrics } catch { /* raw LRC */ }
  value = value.slice(0, 100000)
  const offset = Math.max(-60000, Math.min(60000, Number(value.match(/\[offset:([+-]?\d+)\]/i)?.[1] || 0))) / 1000
  const lines: LyricLine[] = []
  for (const row of value.split(/\r?\n/)) {
    const times = [...row.matchAll(/\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g)]
    const last = times[times.length - 1]
    const content = row.slice(last ? (last.index || 0) + last[0].length : 0).replace(/<\d{1,3}:\d{2}\.\d{1,3}>/g, '').trim()
    for (const match of times) if (Number(match[2]) < 60) lines.push({ time: Math.max(0, Number(match[1]) * 60 + Number(match[2]) + Number((match[3] || '0').padEnd(3, '0')) / 1000 + offset), text: content })
    if (lines.length >= 2000) break
  }
  return lines.sort((a, b) => a.time - b.time).slice(0, 2000)
}
